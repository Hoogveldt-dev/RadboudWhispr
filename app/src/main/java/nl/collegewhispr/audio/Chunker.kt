package nl.collegewhispr.audio

/**
 * Splits a long recording into pieces of ~10 minutes, cutting at the quietest
 * moment near each boundary so no word gets chopped in half.
 * 10.5 min of 16 kHz PCM16 WAV is ~20 MB, under Groq's 25 MB upload limit.
 */
object Chunker {
    const val TARGET_S = 600
    const val WINDOW_S = 30
    private const val FRAME = SAMPLE_RATE * 4 / 10 // 0.4 s
    private const val HOP = SAMPLE_RATE / 10 // 0.1 s

    fun plan(totalSamples: Long, read: (start: Long, count: Int) -> ShortArray): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var start = 0L
        val target = TARGET_S.toLong() * SAMPLE_RATE
        val window = WINDOW_S.toLong() * SAMPLE_RATE
        while (totalSamples - start > target + window) {
            val regionStart = start + target - window
            val regionLen = (2 * window).toInt()
            val s = read(regionStart, regionLen)
            val cut = regionStart + quietestPoint(s)
            out += start until cut
            start = cut
        }
        if (totalSamples > start) out += start until totalSamples
        return out
    }

    /** Index (within [s]) of the centre of the lowest-energy 0.4 s frame. */
    fun quietestPoint(s: ShortArray): Int {
        if (s.size <= FRAME) return s.size / 2
        var best = Double.MAX_VALUE
        var bestIdx = s.size / 2
        var i = 0
        while (i + FRAME <= s.size) {
            var sum = 0.0
            var j = i
            while (j < i + FRAME) { val v = s[j].toDouble(); sum += v * v; j += 4 }
            if (sum < best) { best = sum; bestIdx = i + FRAME / 2 }
            i += HOP
        }
        return bestIdx
    }
}
