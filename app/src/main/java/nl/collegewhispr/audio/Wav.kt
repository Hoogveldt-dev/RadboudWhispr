package nl.collegewhispr.audio

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SAMPLE_RATE = 16000

/** Minimal PCM16 mono WAV helpers. */
object Wav {
    const val HEADER_SIZE = 44

    fun header(dataBytes: Long, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val riffSize = (36 + dataBytes).coerceAtMost(0xFFFFFFFFL)
        b.put("RIFF".toByteArray()).putInt(riffSize.toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        b.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        return b.array()
    }

    /** Rewrites the header so its sizes match the actual file length. */
    fun fixHeader(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val data = (raf.length() - HEADER_SIZE).coerceAtLeast(0)
            val even = data - (data % 2)
            raf.seek(0)
            raf.write(header(even))
        }
    }

    data class Info(val dataOffset: Long, val dataBytes: Long, val sampleRate: Int, val channels: Int, val bits: Int) {
        val totalSamples: Long get() = dataBytes / (channels * bits / 8)
        val durationMs: Long get() = totalSamples * 1000 / sampleRate
    }

    fun info(file: File): Info {
        RandomAccessFile(file, "r").use { raf ->
            val hdr = ByteArray(12)
            raf.readFully(hdr)
            require(String(hdr, 0, 4) == "RIFF" && String(hdr, 8, 4) == "WAVE") { "Geen WAV-bestand" }
            var sr = SAMPLE_RATE
            var ch = 1
            var bits = 16
            val buf = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(buf)
                val id = String(buf, 0, 4)
                val size = ByteBuffer.wrap(buf, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val f = ByteArray(16)
                    raf.readFully(f)
                    val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                    bb.short
                    ch = bb.short.toInt()
                    sr = bb.int
                    bb.int; bb.short
                    bits = bb.short.toInt()
                    raf.seek(raf.filePointer + size - 16 + (size % 2))
                } else if (id == "data") {
                    val off = raf.filePointer
                    // Trust the real file length if the header was never finalised.
                    val actual = raf.length() - off
                    val bytes = if (size == 0L || size > actual) actual else size
                    return Info(off, bytes - bytes % 2, sr, ch, bits)
                } else {
                    raf.seek(raf.filePointer + size + (size % 2))
                }
            }
            error("Geen audiodata gevonden")
        }
    }

    /** Reads mono PCM16 samples [start, start+count). */
    fun readSamples(file: File, info: Info, start: Long, count: Int): ShortArray {
        val out = ShortArray(count)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset + start * 2)
            val bytes = ByteArray(count * 2)
            var read = 0
            while (read < bytes.size) {
                val r = raf.read(bytes, read, bytes.size - read)
                if (r <= 0) break
                read += r
            }
            ByteBuffer.wrap(bytes, 0, read).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, read / 2)
        }
        return out
    }

    fun write(out: OutputStream, samples: ShortArray) {
        out.write(header(samples.size * 2L))
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(samples)
        out.write(bb.array())
    }

    fun wavBytes(samples: ShortArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream(HEADER_SIZE + samples.size * 2)
        write(bos, samples)
        return bos.toByteArray()
    }
}

object Dsp {
    /** Peak-normalises quiet recordings (far-away lecturers) up to a max gain of 8x. */
    fun normalize(samples: ShortArray): ShortArray {
        var peak = 1
        // Use the 99.9th percentile-ish peak by ignoring single clicks: take max of block RMS * 4
        val block = 1600
        var maxRms = 1.0
        var i = 0
        while (i < samples.size) {
            val end = minOf(samples.size, i + block)
            var sum = 0.0
            for (j in i until end) {
                val v = samples[j].toDouble(); sum += v * v
                val a = kotlin.math.abs(samples[j].toInt()); if (a > peak) peak = a
            }
            val rms = kotlin.math.sqrt(sum / (end - i))
            if (rms > maxRms) maxRms = rms
            i = end
        }
        val target = 0.9 * 32767
        val ref = minOf(peak.toDouble(), maxRms * 4)
        val gain = (target / ref).coerceIn(1.0, 8.0)
        if (gain < 1.1) return samples
        return ShortArray(samples.size) { k ->
            (samples[k] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun toFloat(samples: ShortArray) = FloatArray(samples.size) { samples[it] / 32768f }
}
