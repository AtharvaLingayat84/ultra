package com.example.ultrasonicchat

object Decoder {
    private const val MAX_STREAM_SHIFT = 1
    private const val MIN_READABLE_RATIO = 0.60

    private data class Candidate(
        val text: String,
        val startIndex: Int,
        val endIndex: Int,
        val startMatches: Int,
        val endMatches: Int,
        val byteShift: Int,
        val streamShift: Int,
        val exact: Boolean,
        val score: Int,
    )

    fun decodeBits(bits: List<Char>, onDebug: (String) -> Unit = {}): String {
        if (bits.isEmpty()) {
            onDebug("Decode reject: empty bitstream")
            return ""
        }

        val bitstream = bits.joinToString(separator = "")
        onDebug("Decode stream: bits=${bits.size} bitstream=${bitstream.length}")

        val candidates = buildCandidates(bitstream)
        if (candidates.isEmpty()) {
            onDebug("Decode reject: no plausible framed payload")
            return ""
        }

        val best = candidates.maxWithOrNull(
            compareBy<Candidate> { it.score }
                .thenByDescending { it.exact }
                .thenByDescending { it.startMatches + it.endMatches }
                .thenBy { it.byteShift }
                .thenBy { it.streamShift },
        ) ?: return ""

        onDebug(
            "Decode select: exact=${best.exact} start=${best.startIndex} end=${best.endIndex} startMatches=${best.startMatches} endMatches=${best.endMatches} byteShift=${best.byteShift} streamShift=${best.streamShift} score=${best.score} textLen=${best.text.length}",
        )
        onDebug("Decode text: text='${best.text}' bytes=${textToByteLog(best.text)}")
        return best.text
    }

    private fun buildCandidates(bitstream: String): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        for (streamShift in 0..MAX_STREAM_SHIFT) {
            val variant = if (streamShift == 0) bitstream else bitstream.drop(streamShift)
            if (variant.length < Constants.START_MARKER.length + Constants.END_MARKER.length) {
                continue
            }
            candidates += exactCandidates(variant, streamShift)
        }
        return candidates
            .filter { it.score >= 0 }
            .sortedByDescending { it.score }
    }

    private fun exactCandidates(variant: String, streamShift: Int): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        var start = variant.indexOf(Constants.START_MARKER)
        while (start != -1) {
            var end = variant.indexOf(Constants.END_MARKER, start + Constants.START_MARKER.length)
            while (end != -1) {
                candidates += evaluateFrame(
                    variant = variant,
                    startIndex = start,
                    endIndex = end,
                    startMatches = Constants.START_MARKER.length,
                    endMatches = Constants.END_MARKER.length,
                    exact = true,
                    streamShift = streamShift,
                )
                end = variant.indexOf(Constants.END_MARKER, end + 1)
            }
            start = variant.indexOf(Constants.START_MARKER, start + 1)
        }
        return candidates
    }

    private fun evaluateFrame(
        variant: String,
        startIndex: Int,
        endIndex: Int,
        startMatches: Int,
        endMatches: Int,
        exact: Boolean,
        streamShift: Int,
    ): Candidate {
        var bestCandidate = Candidate("", startIndex, endIndex, startMatches, endMatches, 0, streamShift, exact, -1)

        val payloadBits = variant.substring(startIndex + Constants.START_MARKER.length, endIndex)
        val text = decodePayload(payloadBits)
        if (text.isEmpty()) {
            return bestCandidate
        }

        val score = scoreDecodedText(
            text = text,
            exact = exact,
            startMatches = startMatches,
            endMatches = endMatches,
            payloadBits = payloadBits.length,
            byteShift = 0,
        )
        if (score > bestCandidate.score) {
            bestCandidate = Candidate(
                text = text,
                startIndex = startIndex,
                endIndex = endIndex,
                startMatches = startMatches,
                endMatches = endMatches,
                byteShift = 0,
                streamShift = streamShift,
                exact = exact,
                score = score,
            )
        }

        return bestCandidate
    }

    private fun decodePayload(payloadBits: String): String {
        if (payloadBits.length < 8 || payloadBits.length % 8 != 0) {
            return ""
        }
        val chars = StringBuilder()
        var index = 0
        while (index + 8 <= payloadBits.length) {
            val byte = payloadBits.substring(index, index + 8)
            chars.append(byte.toInt(2).toChar())
            index += 8
        }
        return chars.toString()
    }

    private fun textToByteLog(text: String): String {
        if (text.isEmpty()) return "[]"
        return text.map { ch ->
            val bits = ch.code.toString(2).padStart(8, '0')
            "${ch.ifVisible()}:$bits"
        }.joinToString(prefix = "[", postfix = "]")
    }

    private fun Char.ifVisible(): String {
        return if (this.code in 32..126) toString() else "\\u${code.toString(16).padStart(4, '0')}"
    }

    private fun scoreDecodedText(
        text: String,
        exact: Boolean,
        startMatches: Int,
        endMatches: Int,
        payloadBits: Int,
        byteShift: Int,
    ): Int {
        if (text.isEmpty()) return -1

        var printable = 0
        var lettersOrDigits = 0
        var spaces = 0
        var weird = 0
        for (ch in text) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> {
                    printable++
                    spaces++
                }
                ch.code in 32..126 -> {
                    printable++
                    if (ch.isLetterOrDigit()) lettersOrDigits++
                    if (ch == ' ') spaces++
                }
                else -> weird++
            }
        }

        val printableRatio = printable.toDouble() / text.length.toDouble()
        if (printableRatio < MIN_READABLE_RATIO) return -1

        if (text.length < 2) return -1

        val alnumRatio = lettersOrDigits.toDouble() / text.length.toDouble()
        if (lettersOrDigits == 0 || alnumRatio < 0.4) return -1

        var score = 0
        score += (printableRatio * 2000).toInt()
        score += printable * 40
        score += lettersOrDigits * 10
        score += spaces * 8
        score += text.length * 10
        score += if (payloadBits % 8 == 0) 180 else 0
        score += startMatches * 40 + endMatches * 40
        score += if (exact) 1500 else 0
        score -= byteShift * 30
        score -= weird * 250
        return score
    }

    fun decodeAudio(audio: FloatArray, config: AudioConfig, onDebug: (String) -> Unit = {}): String {
        return decodeBits(Demodulator.demodulateBits(audio, config, onDebug), onDebug)
    }
}
