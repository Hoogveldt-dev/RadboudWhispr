package nl.collegewhispr.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming downsampler: windowed-sinc low-pass (cut-off ~7.2 kHz) followed by
 * linear interpolation to the target rate. Keeps state between calls.
 *
 * For integer ratios (48 and 32 kHz, the usual phone rates) the filter is only
 * evaluated for the samples that are kept, which makes it several times faster.
 */
class Decimator(private val srcRate: Int, private val dstRate: Int = SAMPLE_RATE) {
    private val len = 63
    private val taps: FloatArray
    // Every sample is stored twice (at i and i + len) so the last len samples are always
    // one contiguous run: no wrap-around check inside the filter loop.
    private val history = FloatArray(2 * len)
    private var histPos = 0
    private val step = srcRate.toDouble() / dstRate
    private val intRatio = if (srcRate > dstRate && srcRate % dstRate == 0) srcRate / dstRate else 0
    private var phase = 0 // integer path: samples seen since the last kept one
    private var pos = 0.0 // position of next output sample, in input samples relative to `prev`
    private var prev = 0f
    private var cur = 0f
    private var haveCur = false

    init {
        val fc = minOf(7200.0 / srcRate, 0.45) // upsampling (e.g. 8 kHz): nearly pass-through
        taps = FloatArray(len) { i ->
            val m = i - (len - 1) / 2.0
            val sinc = if (m == 0.0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
            val w = 0.54 - 0.46 * cos(2 * PI * i / (len - 1))
            (sinc * w).toFloat()
        }
        val sum = taps.sum()
        for (i in taps.indices) taps[i] /= sum
    }

    private fun push(x: Float) {
        history[histPos] = x
        history[histPos + len] = x
        histPos = if (histPos + 1 == len) 0 else histPos + 1
    }

    /** Filter output for the most recently pushed sample. */
    private fun filtered(): Float {
        // oldest sample is at histPos, newest at histPos + len - 1
        val h = history
        val t = taps
        val base = histPos + len - 1
        var acc = 0f
        for (k in 0 until len) acc += t[k] * h[base - k]
        return acc
    }

    fun process(input: ShortArray, n: Int = input.size): ShortArray {
        if (srcRate == dstRate) return input.copyOf(n)
        if (intRatio > 0) {
            val out = ShortArray(n / intRatio + 1)
            var o = 0
            for (i in 0 until n) {
                push(input[i].toFloat())
                if (phase == 0) out[o++] = filtered().toInt().coerceIn(-32768, 32767).toShort()
                phase = if (phase + 1 == intRatio) 0 else phase + 1
            }
            return out.copyOf(o)
        }
        val out = ShortArray((n / step).toInt() + 2)
        var o = 0
        for (i in 0 until n) {
            push(input[i].toFloat())
            val y = filtered()
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
