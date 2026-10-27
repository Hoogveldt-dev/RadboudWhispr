package nl.collegewhispr.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming downsampler: windowed-sinc low-pass (cut-off ~7.2 kHz) followed by
 * linear interpolation to the target rate. Keeps state between calls.
 */
class Decimator(private val srcRate: Int, private val dstRate: Int = SAMPLE_RATE) {
    private val taps: FloatArray
    private val history: FloatArray
    private var histPos = 0
    private val step = srcRate.toDouble() / dstRate
    private var pos = 0.0 // position of next output sample, in input samples relative to `prev`
    private var prev = 0f
    private var cur = 0f
    private var haveCur = false

    init {
        val n = 63
        val fc = minOf(7200.0 / srcRate, 0.45) // upsampling (e.g. 8 kHz): nearly pass-through
        taps = FloatArray(n) { i ->
            val m = i - (n - 1) / 2.0
            val sinc = if (m == 0.0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
            val w = 0.54 - 0.46 * cos(2 * PI * i / (n - 1))
            (sinc * w).toFloat()
        }
        val sum = taps.sum()
        for (i in taps.indices) taps[i] /= sum
        history = FloatArray(n)
    }

    private fun filter(x: Float): Float {
        history[histPos] = x
        var acc = 0f
        var idx = histPos
        for (t in taps) {
            acc += t * history[idx]
            idx = if (idx == 0) history.size - 1 else idx - 1
        }
        histPos = (histPos + 1) % history.size
        return acc
    }

    fun process(input: ShortArray, n: Int = input.size): ShortArray {
        if (srcRate == dstRate) return input.copyOf(n)
        val out = ShortArray((n / step).toInt() + 2)
        var o = 0
        for (i in 0 until n) {
            val y = filter(input[i].toFloat())
            if (!haveCur) { cur = y; prev = y; haveCur = true; continue }
            prev = cur
            cur = y
            // emit samples lying between prev (pos 0) and cur (pos 1)
            while (pos < 1.0) {
                val v = prev + (cur - prev) * pos.toFloat()
                if (o < out.size) out[o++] = v.toInt().coerceIn(-32768, 32767).toShort()
                pos += step
            }
            pos -= 1.0
        }
        return out.copyOf(o)
    }
}
