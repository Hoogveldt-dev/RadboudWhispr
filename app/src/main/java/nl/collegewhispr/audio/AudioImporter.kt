package nl.collegewhispr.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any audio file Android supports (m4a, mp3, ogg, wav, ...) to 16 kHz mono PCM16 WAV. */
object AudioImporter {

    fun import(ctx: Context, uri: Uri, out: File, onProgress: (Float) -> Unit = {}): Long {
        val ex = MediaExtractor()
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

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        var decimator = Decimator(srcRate)
        var written = 0L
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.write(Wav.header(0))
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            if (durationUs > 0) onProgress((ex.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        decimator = Decimator(srcRate)
                    }
                    outIdx >= 0 -> {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val mono = toMono(ob.slice().order(ByteOrder.nativeOrder()), channels, pcmFloat)
                        val res = decimator.process(mono)
                        val bb = ByteBuffer.allocate(res.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        bb.asShortBuffer().put(res)
                        raf.write(bb.array())
                        written += res.size * 2
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            raf.seek(0)
            raf.write(Wav.header(written))
        }
        codec.stop(); codec.release(); ex.release()
        onProgress(1f)
        return written / 2 * 1000 / SAMPLE_RATE
    }

    private fun toMono(b: ByteBuffer, channels: Int, isFloat: Boolean): ShortArray {
        return if (isFloat) {
            val f = b.asFloatBuffer()
            val frames = f.remaining() / channels
            ShortArray(frames) { i ->
                var s = 0f
                for (c in 0 until channels) s += f.get(i * channels + c)
                (s / channels * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
        } else {
            val sb = b.asShortBuffer()
            val frames = sb.remaining() / channels
            ShortArray(frames) { i ->
                var s = 0
                for (c in 0 until channels) s += sb.get(i * channels + c)
                (s / channels).toShort()
            }
        }
    }
}
