package nl.collegewhispr.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Decodes any audio file Android supports (m4a, mp3, ogg, wav, video, ...) to 16 kHz mono PCM16 WAV.
 *
 * Long MP4/M4A files (what phones record) are split into parts that are decoded at the same
 * time on several cores; each part is placed in the output by its timestamps. Other formats,
 * whose seeking can be inexact (VBR mp3, raw aac), are decoded in one pass.
 */
object AudioImporter {
    private const val TAG = "AudioImporter"
    /** Decoded before each part's start and thrown away, so decoder and filter are settled. */
    private const val PREROLL_US = 500_000L
    private const val MIN_PART_US = 4 * 60_000_000L
    private const val MAX_PARTS = 4

    private class Source(val track: Int, val format: MediaFormat, val mime: String, val durationUs: Long, val codec: String?)

    fun import(
        ctx: Context, uri: Uri, out: File,
        shouldStop: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): Long {
        val src = probe(ctx, uri)
        val cores = Runtime.getRuntime().availableProcessors()
        val parts = if (src.durationUs > 0 && isMp4(ctx, uri))
            (src.durationUs / MIN_PART_US).toInt().coerceIn(1, minOf(MAX_PARTS, maxOf(1, cores / 2))) else 1
        if (parts > 1) {
            try {
                return decode(ctx, uri, out, src, parts, shouldStop, onProgress)
            } catch (e: Throwable) {
                if (shouldStop()) throw e
                // e.g. the device allows fewer decoders at once: do it in one pass instead
                Log.w(TAG, "Parallel decoding failed, falling back to one pass", e)
            }
        }
        return decode(ctx, uri, out, src, 1, shouldStop, onProgress)
    }

    private fun probe(ctx: Context, uri: Uri): Source {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                return Source(i, fmt, mime, dur, softwareDecoder(mime))
            }
        } finally {
            ex.release()
        }
        throw IllegalArgumentException("Geen audiospoor gevonden in dit bestand")
    }

    /** Software audio decoders run in-process friendly, have no instance limit and are the fastest for audio. */
    private fun softwareDecoder(mime: String): String? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && isSoftware(info) && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }?.name
    }.getOrNull()

    private fun isSoftware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
        else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

    /** MP4 family (m4a, mp4, 3gp): has a sample table, so seeking and timestamps are exact. */
    private fun isMp4(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(8)
            var n = 0
            while (n < 8) { val r = s.read(b, n, 8 - n); if (r <= 0) break; n += r }
            n == 8 && String(b, 4, 4, Charsets.US_ASCII) == "ftyp"
        } ?: false
    }.getOrDefault(false)

    private fun usToSample(us: Long) = us * SAMPLE_RATE / 1_000_000

    private fun decode(
        ctx: Context, uri: Uri, out: File, src: Source, parts: Int,
        shouldStop: () -> Boolean, onProgress: (Float) -> Unit,
    ): Long {
        val bounds = LongArray(parts + 1) { src.durationUs * it / parts }
        val doneUs = AtomicLongArray(parts)
        val ends = AtomicLongArray(parts)
        val abort = AtomicBoolean(false)
        val errors = arrayOfNulls<Throwable>(parts)
        val stop = { abort.get() || shouldStop() }
        var total = 0L

        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.write(Wav.header(0))
            val ch = raf.channel
            val threads = (0 until parts).map { k ->
                Thread({
                    try {
                        decodePart(ctx, uri, src, k, parts, bounds, ch, stop, doneUs, ends)
                    } catch (e: Throwable) {
                        errors[k] = e
                        abort.set(true)
                    }
                }, "import-$k").apply { start() }
            }
            var lastReported = -1
            while (threads.any { it.isAlive }) {
                threads.firstOrNull { it.isAlive }?.join(200)
                if (src.durationUs > 0) {
                    var sum = 0L
                    for (k in 0 until parts) sum += doneUs.get(k)
                    // 0.5 % steps, so the UI is not flooded with updates
                    val pct = (sum * 200 / src.durationUs).toInt().coerceIn(0, 200)
                    if (pct != lastReported) { lastReported = pct; onProgress(pct / 200f) }
                }
            }
            errors.firstOrNull { it != null }?.let { throw it }
            check(!shouldStop()) { "Importeren gestopt" }
            for (k in 0 until parts) total = maxOf(total, ends.get(k))
            raf.setLength(Wav.HEADER_SIZE + total * 2)
            raf.seek(0)
            raf.write(Wav.header(total * 2))
        }
        onProgress(1f)
        return total * 1000 / SAMPLE_RATE
    }

    /**
     * Decodes part [k] and writes the samples that fall in its window at their place in [ch].
     * With one part everything is written in order, without relying on timestamps.
     */
    private fun decodePart(
        ctx: Context, uri: Uri, src: Source, k: Int, parts: Int, bounds: LongArray, ch: FileChannel,
        stop: () -> Boolean, doneUs: AtomicLongArray, ends: AtomicLongArray,
    ) {
        val single = parts == 1
        val startUs = bounds[k]
        val last = k == parts - 1
        val startOut = if (single) 0L else usToSample(startUs)
        val endOut = if (last) Long.MAX_VALUE else usToSample(bounds[k + 1])

        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            ex.selectTrack(src.track)
            if (k > 0) ex.seekTo(maxOf(0L, startUs - PREROLL_US), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val dec = src.codec?.let { runCatching { MediaCodec.createByCodecName(it) }.getOrNull() }
                ?: MediaCodec.createDecoderByType(src.mime)
            codec = dec
            dec.configure(src.format, null, null, 0)
            dec.start()

            val conv = Converter(
                src.format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                src.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), false,
            )
            val sink = Sink(ch)
            var base = Long.MIN_VALUE // output index of the first decimated sample
            var produced = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var finished = false

            while (!finished) {
                check(!stop()) { "Importeren gestopt" }
                // Keep the decoder fed: hand it every free input buffer instead of one
                // frame per round trip, so decoding runs ahead while we convert output.
                var progressed = false
                while (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    val buf = dec.getInputBuffer(inIdx)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        dec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                    progressed = true
                }
                // Drain everything that is ready; only wait when there was nothing to do.
                while (true) {
                    val outIdx = dec.dequeueOutputBuffer(info, if (progressed) 0L else 5_000L)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    progressed = true
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = dec.outputFormat
                        conv.reset(
                            of.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            of.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT,
                        )
                        continue
                    }
                    if (outIdx < 0) continue // INFO_OUTPUT_BUFFERS_CHANGED on old devices
                    if (info.size > 0) {
                        val ob = dec.getOutputBuffer(outIdx)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        if (base == Long.MIN_VALUE) base = if (single) 0L else usToSample(info.presentationTimeUs)
                        val res = conv.convert(ob.slice().order(ByteOrder.nativeOrder()))
                        for (i in res.indices) {
                            val g = base + produced + i
                            if (g >= startOut && g < endOut) sink.put(g, res[i])
                        }
                        produced += res.size
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    doneUs.set(k, (info.presentationTimeUs - startUs).coerceIn(0L, (bounds[k + 1] - startUs)))
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                        (base != Long.MIN_VALUE && base + produced >= endOut)
                    ) { finished = true; break }
                }
            }
            sink.flush()
            doneUs.set(k, bounds[k + 1] - startUs)
            ends.set(k, sink.end)
        } finally {
            // a failed import must not leak the (limited) decoder
            codec?.let { runCatching { it.stop() }; it.release() }
            ex.release()
        }
    }

    /** Collects consecutive samples and writes them to their place in the WAV in large blocks. */
    private class Sink(private val ch: FileChannel) {
        private val buf = ByteArray(1 shl 18)
        private var len = 0
        private var start = -1L // sample index of buf[0]
        var end = 0L // one past the last sample written
            private set

        fun put(index: Long, v: Short) {
            if (len == buf.size || (len > 0 && index != start + len / 2)) flush()
            if (len == 0) start = index
            val s = v.toInt()
            buf[len] = s.toByte()
            buf[len + 1] = (s shr 8).toByte()
            len += 2
        }

        fun flush() {
            if (len == 0) return
            val bb = ByteBuffer.wrap(buf, 0, len)
            var pos = Wav.HEADER_SIZE + start * 2
            while (bb.hasRemaining()) pos += ch.write(bb, pos)
            end = maxOf(end, start + len / 2)
            len = 0
        }
    }

    /** Turns decoder output into 16 kHz mono PCM16, reusing its buffers between frames. */
    private class Converter(rate: Int, private var channels: Int, private var isFloat: Boolean) {
        private var decimator = Decimator(rate)
        private var mono = ShortArray(0)
        private var raw = ShortArray(0)
        private var rawF = FloatArray(0)

        fun reset(rate: Int, channels: Int, isFloat: Boolean) {
            decimator = Decimator(rate)
            this.channels = channels
            this.isFloat = isFloat
        }

        fun convert(b: ByteBuffer): ShortArray {
            val frames: Int
            if (isFloat) {
                val f = b.asFloatBuffer()
                val n = f.remaining()
                if (rawF.size < n) rawF = FloatArray(n)
                f.get(rawF, 0, n)
                frames = n / channels
                if (mono.size < frames) mono = ShortArray(frames)
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until channels) s += rawF[i * channels + c]
                    mono[i] = (s / channels * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
            } else {
                val sb = b.asShortBuffer()
                val n = sb.remaining()
                frames = n / channels
                if (channels == 1) {
                    if (mono.size < n) mono = ShortArray(n)
                    sb.get(mono, 0, n)
                } else {
                    if (raw.size < n) raw = ShortArray(n)
                    sb.get(raw, 0, n)
                    if (mono.size < frames) mono = ShortArray(frames)
                    for (i in 0 until frames) {
                        var s = 0
                        for (c in 0 until channels) s += raw[i * channels + c]
                        mono[i] = (s / channels).toShort()
                    }
                }
            }
            return decimator.process(mono, frames)
        }
    }
}
