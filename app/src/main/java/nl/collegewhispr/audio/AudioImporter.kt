package nl.collegewhispr.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any audio file Android supports (m4a, mp3, ogg, wav, ...) to 16 kHz mono PCM16 WAV. */
object AudioImporter {

    fun import(ctx: Context, uri: Uri, out: File, onProgress: (Float) -> Unit = {}): Long {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val bytes = try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; break }
            }
            require(track >= 0) { "Geen audiospoor gevonden in dit bestand" }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()

            val conv = Converter(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT), false)
            var written = 0L
            var lastReported = -1
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            // Buffered: one write per decoded frame (~20 ms of audio) would mean hundreds of
            // thousands of tiny file writes for a long lecture.
            BufferedOutputStream(FileOutputStream(out), 1 shl 18).use { os ->
                os.write(Wav.header(0))
                while (!outputDone) {
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
                            written += conv.convert(ob.slice().order(ByteOrder.nativeOrder()), os)
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (durationUs > 0) {
                            // report in 0.5 % steps so the UI is not flooded with updates
                            val pct = (info.presentationTimeUs * 200 / durationUs).toInt().coerceIn(0, 200)
                            if (pct != lastReported) { lastReported = pct; onProgress(pct / 200f) }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true; break }
                    }
                }
            }
            RandomAccessFile(out, "rw").use { raf -> raf.seek(0); raf.write(Wav.header(written)) }
            written
        } finally {
            // a failed import must not leak the (limited) hardware decoder
            codec?.let { runCatching { it.stop() }; it.release() }
            ex.release()
        }
        onProgress(1f)
        return bytes / 2 * 1000 / SAMPLE_RATE
    }

    /** Turns decoder output into 16 kHz mono PCM16, reusing its buffers between frames. */
    private class Converter(rate: Int, private var channels: Int, private var isFloat: Boolean) {
        private var decimator = Decimator(rate)
        private var mono = ShortArray(0)
        private var raw = ShortArray(0)
        private var rawF = FloatArray(0)
        private var bytes = ByteArray(0)

        fun reset(rate: Int, channels: Int, isFloat: Boolean) {
            decimator = Decimator(rate)
            this.channels = channels
            this.isFloat = isFloat
        }

        /** Returns the number of bytes written. */
        fun convert(b: ByteBuffer, os: java.io.OutputStream): Int {
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
            val res = decimator.process(mono, frames)
            val len = res.size * 2
            if (bytes.size < len) bytes = ByteArray(len)
            for (i in res.indices) {
                val v = res[i].toInt()
                bytes[2 * i] = v.toByte()
                bytes[2 * i + 1] = (v shr 8).toByte()
            }
            os.write(bytes, 0, len)
            return len
        }
    }
}
