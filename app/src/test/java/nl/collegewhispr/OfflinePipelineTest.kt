package nl.collegewhispr

import nl.collegewhispr.audio.Chunker
import nl.collegewhispr.audio.Dsp
import nl.collegewhispr.audio.SAMPLE_RATE
import nl.collegewhispr.audio.Wav
import nl.collegewhispr.data.Segment
import nl.collegewhispr.transcribe.TextFormat
import nl.collegewhispr.whisper.WhisperLib
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the same steps as LocalEngine against a desktop build of jni.c.
 * Enabled with -Dcw.lib=... -Dcw.model=... -Dcw.vad=... -Dcw.audio=... -Dcw.ref=...
 */
class OfflinePipelineTest {
    @Test
    fun transcribeDutchRecording() {
        val lib = System.getProperty("cw.lib") ?: System.getenv("CW_LIB")
        assumeTrue("set CW_LIB to run", lib != null)
        System.load(lib!!)
        val model = System.getenv("CW_MODEL")!!
        val vad = System.getenv("CW_VAD")
        val audio = File(System.getenv("CW_AUDIO")!!)
        val ref = System.getenv("CW_REF")?.let { File(it).readText() }

        println("system: " + WhisperLib.systemInfo())
        val info = Wav.info(audio)
        // Force a split in the middle to exercise chunk offsets and prompt carry-over.
        val mid = info.totalSamples / 2
        val window = Wav.readSamples(audio, info, mid - 15 * SAMPLE_RATE, 30 * SAMPLE_RATE)
        val cut = mid - 15 * SAMPLE_RATE + Chunker.quietestPoint(window)
        val chunks = listOf(0L until cut, cut until info.totalSamples)
        println("split at %.1f s".format(cut / 16000.0))

        val ctx = WhisperLib.initContext(model)
        check(ctx != 0L) { "model failed to load" }
        val all = mutableListOf<Segment>()
        var prevTail = ""
        val t0 = System.currentTimeMillis()
        for (c in chunks) {
            val samples = Dsp.normalize(Wav.readSamples(audio, info, c.first, (c.last - c.first + 1).toInt()))
            val prompt = ("Dit is een hoorcollege aan een Nederlandse universiteit, met correcte interpunctie en hoofdletters. $prevTail").trim()
            var lastP = -25
            val bytes = WhisperLib.transcribe(ctx, Dsp.toFloat(samples), "nl", prompt, vad, 2) { p ->
                if (p / 25 != lastP / 25) { println("  progress $p%"); lastP = p }
            }!!
            val offset = c.first * 1000 / SAMPLE_RATE
            val segs = String(bytes, Charsets.UTF_8).lines().filter { it.isNotBlank() }.map {
                val p = it.split('\t', limit = 3)
                Segment(p[0].toLong() + offset, p[1].toLong() + offset, p[2].trim())
            }
            all += segs
            prevTail = segs.joinToString(" ") { it.text }.takeLast(200)
        }
        WhisperLib.freeContext(ctx)
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        println("transcribed %.0f s of audio in %.0f s".format(info.durationMs / 1000.0, secs))
        all.forEach { println("  [%s-%s] %s".format(TextFormat.time(it.startMs), TextFormat.time(it.endMs), it.text)) }
        val paras = TextFormat.paragraphs(all)
        println("---- export ----")
        println(TextFormat.plainText("Testcollege", "test", paras, true))
        check(all.isNotEmpty())
        // timestamps must be increasing and inside the audio
        all.zipWithNext().forEach { (a, b) -> check(b.startMs >= a.startMs - 1000) { "timestamps go back: $a -> $b" } }
        check(all.last().endMs <= info.durationMs + 2000)

        if (ref != null) {
            fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
            val r = norm(ref); val h = norm(all.joinToString(" ") { it.text })
            val d = Array(r.size + 1) { IntArray(h.size + 1) }
            for (i in 0..r.size) d[i][0] = i
            for (j in 0..h.size) d[0][j] = j
            for (i in 1..r.size) for (j in 1..h.size)
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
            println("WER vs reference (old spelling, so inflated): %.1f%%".format(100.0 * d[r.size][h.size] / r.size))
        }
    }
}
