package com.example.ultrasonicchat

object Decoder {
    private const val MAX_MARKER_ERRORS = 2
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

        val candidates = buildCandidates(bitstream, onDebug)
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

    private fun buildCandidates(bitstream: String, onDebug: (String) -> Unit): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        for (streamShift in 0..MAX_STREAM_SHIFT) {
            val variant = if (streamShift == 0) bitstream else bitstream.drop(streamShift)
            if (variant.length < Constants.START_MARKER.length + Constants.END_MARKER.length) {
                continue
            }
            candidates += exactCandidates(variant, streamShift)
            candidates += approximateCandidates(variant, streamShift, onDebug)
        }

        val ranked = candidates
            .filter { it.score >= 0 }
            .sortedByDescending { it.score }
        if (ranked.isNotEmpty()) {
            val top = ranked.first()
            val exactCount = ranked.count { it.exact }
            onDebug("Decode candidates: count=${ranked.size} exact=$exactCount topScore=${top.score}")
        }
        return ranked
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

    private fun approximateCandidates(variant: String, streamShift: Int, onDebug: (String) -> Unit): List<Candidate> {
        val startHits = markerHits(variant, Constants.START_MARKER)
            .filter { it.matches >= Constants.START_MARKER.length - MAX_MARKER_ERRORS }
            .take(6)
        if (startHits.isEmpty()) {
            onDebug("Decode fallback: approximate START marker not found shift=$streamShift")
            return emptyList()
        }

        val candidates = mutableListOf<Candidate>()
        for (startHit in startHits) {
            val payloadStart = startHit.index + Constants.START_MARKER.length
            if (payloadStart >= variant.length) continue

            val endHits = markerHits(variant, Constants.END_MARKER, payloadStart)
                .filter { it.matches >= Constants.END_MARKER.length - MAX_MARKER_ERRORS }
                .take(6)
            for (endHit in endHits) {
                candidates += evaluateFrame(
                    variant = variant,
                    startIndex = startHit.index,
                    endIndex = endHit.index,
                    startMatches = startHit.matches,
                    endMatches = endHit.matches,
                    exact = false,
                    streamShift = streamShift,
                )
            }
        }

        if (candidates.isEmpty()) {
            onDebug("Decode fallback: approximate END marker not found shift=$streamShift")
        }
        return candidates
    }

    private data class MarkerHit(val index: Int, val matches: Int)

    private fun markerHits(stream: String, marker: String, fromIndex: Int = 0): List<MarkerHit> {
        if (stream.length < marker.length) return emptyList()
        val hits = mutableListOf<MarkerHit>()
        val lastStart = stream.length - marker.length
        for (index in fromIndex..lastStart) {
            val matches = markerMatches(stream, index, marker)
            if (matches >= marker.length - MAX_MARKER_ERRORS) {
                hits += MarkerHit(index, matches)
            }
        }
        return hits.sortedWith(compareByDescending<MarkerHit> { it.matches }.thenBy { it.index })
    }

    private fun markerMatches(stream: String, index: Int, marker: String): Int {
        var matches = 0
        for (i in marker.indices) {
            if (stream[index + i] == marker[i]) {
                matches++
            }
        }
        return matches
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

        for (byteShift in 0..7) {
            val payloadStart = startIndex + Constants.START_MARKER.length + byteShift
            if (payloadStart > endIndex) break
            val payloadBits = variant.substring(payloadStart, endIndex)
            val text = decodePayload(payloadBits)
            if (text.isEmpty()) continue
            val score = scoreDecodedText(
                text = text,
                exact = exact,
                startMatches = startMatches,
                endMatches = endMatches,
                payloadBits = payloadBits.length,
                byteShift = byteShift,
            )
            if (score > bestCandidate.score) {
                bestCandidate = Candidate(
                    text = text,
                    startIndex = startIndex,
                    endIndex = endIndex,
                    startMatches = startMatches,
                    endMatches = endMatches,
                    byteShift = byteShift,
                    streamShift = streamShift,
                    exact = exact,
                    score = score,
                )
            }
        }

        return bestCandidate
    }

    private fun decodePayload(payloadBits: String): String {
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
