import numpy as np
import sounddevice as sd
from scipy.signal import butter, sosfiltfilt
import time


SAMPLE_RATE = 44100
BIT_DURATION = 0.1
FREQ_0 = 17800
FREQ_1 = 19000
START_MARKER = "101010111100"
END_MARKER = "001111010101"
REPEAT_BITS = 2
CHUNK_SIZE = int(SAMPLE_RATE * BIT_DURATION)
HOP_SIZE = CHUNK_SIZE // 2
BANDPASS_SOS = butter(6, [17500, 19500], btype="bandpass", fs=SAMPLE_RATE, output="sos")
CONFIDENCE_RATIO = 1.2
NOISE_FLOOR_MULTIPLIER = 3.0
RECEIVE_WINDOW_SECONDS = 3.0
POST_DETECT_COOLDOWN_SECONDS = 0.75
POST_DETECT_TAIL_SECONDS = 0.5


def encode_text(text):
    bits = "".join(f"{ord(ch):08b}" for ch in text)
    framed = START_MARKER + bits + END_MARKER
    return "".join(bit * REPEAT_BITS for bit in framed)


def modulate_signal(bits):
    samples_per_bit = int(SAMPLE_RATE * BIT_DURATION)
    t = np.arange(samples_per_bit, dtype=np.float64) / SAMPLE_RATE
    chunks = []
    for bit in bits:
        freq = FREQ_1 if bit == "1" else FREQ_0
        chunks.append(0.5 * np.sin(2 * np.pi * freq * t))
    if not chunks:
        return np.zeros(0, dtype=np.float32)
    signal = np.concatenate(chunks).astype(np.float32)
    fade = max(1, int(0.005 * SAMPLE_RATE))
    envelope = np.ones_like(signal)
    ramp = np.linspace(0.0, 1.0, fade, endpoint=False)
    envelope[:fade] = ramp
    envelope[-fade:] = ramp[::-1]
    return (signal * envelope).astype(np.float32)


def play_and_record(signal):
    tx = np.asarray(signal, dtype=np.float32)
    recorded = sd.playrec(tx, samplerate=SAMPLE_RATE, channels=1, dtype="float32")
    sd.wait()
    return recorded[:, 0]


def bandpass_filter(audio):
    audio = np.asarray(audio, dtype=np.float32)
    if audio.size < 32:
        return audio.copy()
    return sosfiltfilt(BANDPASS_SOS, audio)


def _goertzel_power(chunk, target_freq):
    n = len(chunk)
    if n == 0:
        return 0.0
    omega = 2.0 * np.pi * target_freq / SAMPLE_RATE
    coeff = 2.0 * np.cos(omega)
    s_prev = 0.0
    s_prev2 = 0.0
    for sample in chunk:
        s = sample + coeff * s_prev - s_prev2
        s_prev2 = s_prev
        s_prev = s
    return s_prev2 * s_prev2 + s_prev * s_prev - coeff * s_prev * s_prev2


def detect_frequency(chunk):
    if len(chunk) == 0:
        return None
    chunk = np.asarray(chunk, dtype=np.float32)
    power_0 = _goertzel_power(chunk, FREQ_0)
    power_1 = _goertzel_power(chunk, FREQ_1)
    if power_0 <= 0.0 and power_1 <= 0.0:
        return None
    if power_0 >= power_1:
        ratio = power_0 / (power_1 + 1e-12)
        return "0" if ratio > CONFIDENCE_RATIO else None
    ratio = power_1 / (power_0 + 1e-12)
    return "1" if ratio > CONFIDENCE_RATIO else None


def _chunk_energy(chunk):
    if len(chunk) == 0:
        return 0.0
    return float(np.mean(np.square(chunk, dtype=np.float32)))


def _adaptive_energy_threshold(audio):
    if len(audio) == 0:
        return 0.0
    noise_window = min(len(audio), int(0.5 * SAMPLE_RATE))
    if noise_window <= 0:
        return 0.0
    noise_floor = float(np.mean(np.square(audio[:noise_window], dtype=np.float32)))
    return noise_floor * NOISE_FLOOR_MULTIPLIER


def _score_stream(bitstream):
    if not bitstream:
        return -1
    score = 0
    start = bitstream.find(START_MARKER)
    if start != -1:
        score += 1000
        start += len(START_MARKER)
        end = bitstream.find(END_MARKER, start)
        if end != -1:
            score += 1000
            payload = bitstream[start:end]
            if len(payload) >= 8:
                score += len(payload)
                if len(payload) % 8 == 0:
                    score += 100
    return score


def _collapse_repeats(raw_bits):
    best_stream = ""
    best_score = -1
    for phase in range(REPEAT_BITS):
        reduced = []
        i = phase
        while i + REPEAT_BITS <= len(raw_bits):
            group = raw_bits[i:i + REPEAT_BITS]
            valid = [bit for bit in group if bit in ("0", "1")]
            if valid:
                ones = valid.count("1")
                zeros = len(valid) - ones
                reduced.append("1" if ones >= zeros else "0")
            i += REPEAT_BITS
        stream = "".join(reduced)
        score = _score_stream(stream)
        if score > best_score:
            best_score = score
            best_stream = stream
    return best_stream


def _smooth_detections(raw_bits):
    if len(raw_bits) < 3:
        return raw_bits
    smoothed = []
    for i in range(len(raw_bits) - 2):
        window = [bit for bit in raw_bits[i:i + 3] if bit in ("0", "1")]
        if len(window) < 2:
            smoothed.append(None)
            continue
        ones = window.count("1")
        zeros = len(window) - ones
        if ones > zeros:
            smoothed.append("1")
        elif zeros > ones:
            smoothed.append("0")
        else:
            smoothed.append(None)
    return smoothed


def demodulate_bits(audio):
    filtered = bandpass_filter(audio)
    if len(filtered) < CHUNK_SIZE:
        return []

    energy_threshold = _adaptive_energy_threshold(filtered)

    offsets = range(0, HOP_SIZE, max(1, HOP_SIZE // 8))
    best_stream = ""
    best_score = -1
    for offset in offsets:
        raw_bits = []
        for start in range(offset, len(filtered) - CHUNK_SIZE + 1, HOP_SIZE):
            chunk = filtered[start:start + CHUNK_SIZE]
            if _chunk_energy(chunk) < energy_threshold:
                raw_bits.append(None)
                continue
            bit = detect_frequency(chunk)
            raw_bits.append(bit)
        stream = _collapse_repeats(_smooth_detections(raw_bits))
        score = _score_stream(stream)
        if score > best_score:
            best_score = score
            best_stream = stream
    return list(best_stream)


def decode_bits(bits):
    if not bits:
        return ""

    bitstream = "".join(bits)
    start = bitstream.find(START_MARKER)
    if start == -1:
        return ""
    start += len(START_MARKER)
    end = bitstream.find(END_MARKER, start)
    if end == -1:
        return ""

    payload = bitstream[start:end]
    chars = []
    for i in range(0, len(payload) - len(payload) % 8, 8):
        byte = payload[i:i + 8]
        chars.append(chr(int(byte, 2)))
    return "".join(chars)


def _decode_audio(audio):
    return decode_bits(demodulate_bits(audio))


def _latest_decode_window(buffer):
    window_size = int(SAMPLE_RATE * RECEIVE_WINDOW_SECONDS)
    if len(buffer) <= window_size:
        return buffer
    return buffer[-window_size:]


def send_message(text):
    encoded = encode_text(text)
    signal = modulate_signal(encoded)
    pad = np.zeros(int(0.25 * SAMPLE_RATE), dtype=np.float32)
    tx = np.concatenate([pad, signal, pad])
    return play_and_record(tx)


def receive_message():
    buffer = np.zeros(0, dtype=np.float32)
    max_buffer = int(SAMPLE_RATE * RECEIVE_WINDOW_SECONDS)
    tail_keep = int(SAMPLE_RATE * POST_DETECT_TAIL_SECONDS)
    block_frames = max(1024, HOP_SIZE)
    cooldown_until = 0.0
    with sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="float32") as stream:
        while True:
            block, _ = stream.read(block_frames)
            buffer = np.concatenate([buffer, block[:, 0]])
            if len(buffer) > max_buffer:
                buffer = buffer[-max_buffer:]

            now = time.monotonic()
            if now < cooldown_until:
                continue

            if len(buffer) < CHUNK_SIZE * 3:
                continue

            message = _decode_audio(_latest_decode_window(buffer))
            if message:
                buffer = buffer[-tail_keep:] if len(buffer) > tail_keep else buffer
                cooldown_until = now + POST_DETECT_COOLDOWN_SECONDS
                return message


def main():
    while True:
        print("1. Send Message")
        print("2. Receive Message")
        print("3. Exit")
        choice = input("Select: ").strip()
        if choice == "1":
            text = input("Message: ")
            send_message(text)
            print("Sent")
        elif choice == "2":
            message = receive_message()
            print(message)
        elif choice == "3":
            break


if __name__ == "__main__":
    main()
