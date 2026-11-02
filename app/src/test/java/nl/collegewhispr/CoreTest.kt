package nl.collegewhispr

import nl.collegewhispr.audio.Chunker
import nl.collegewhispr.audio.Decimator
import nl.collegewhispr.audio.Dsp
import nl.collegewhispr.audio.SAMPLE_RATE
import nl.collegewhispr.audio.Wav
import nl.collegewhispr.data.Segment
import nl.collegewhispr.data.CloudProvider
import nl.collegewhispr.data.Settings
import nl.collegewhispr.data.SettingsState
import nl.collegewhispr.transcribe.DeepgramClient
import nl.collegewhispr.transcribe.Http
import nl.collegewhispr.transcribe.TextFormat
import nl.collegewhispr.transcribe.VerboseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class CoreTest {

    /** 95 minutes of "speech" (noise) with short silent gaps at known places. */
    @Test
    fun chunkerCutsAtSilenceAndStaysUnderGroqLimit() {
        val total = 95L * 60 * SAMPLE_RATE
        val silences = listOf(590L, 1215L, 1790L, 2410L, 3000L, 3610L, 4200L, 4810L, 5400L).map { it * SAMPLE_RATE }
        val rnd = java.util.Random(1)
        val reader = { start: Long, count: Int ->
            ShortArray(count) { i ->
                val pos = start + i
                if (silences.any { pos in it until it + SAMPLE_RATE / 2 }) 0
                else (rnd.nextGaussian() * 3000).toInt().toShort()
            }
        }
        val plan = Chunker.plan(total, reader)
        assertEquals(0L, plan.first().first)
        assertEquals(total - 1, plan.last().last)
        for (i in 1 until plan.size) assertEquals(plan[i - 1].last + 1, plan[i].first)
        for (r in plan) {
            val bytes = Wav.HEADER_SIZE + (r.last - r.first + 1) * 2
            assertTrue("chunk too big: $bytes", bytes < 25L * 1024 * 1024)
        }
        // every cut except the last one should land inside a planted silence
        plan.dropLast(1).forEach { r ->
            val cut = r.last + 1
            assertTrue("cut at ${cut / SAMPLE_RATE}s not in silence", silences.any { cut in it until it + SAMPLE_RATE / 2 })
        }
        println("chunks: " + plan.joinToString { "%.1f min".format((it.last - it.first + 1) / 16000.0 / 60) })
    }

    @Test
    fun decimatorKeepsSpeechBandAndRemovesAliasing() {
        fun tone(freq: Double, rate: Int, n: Int) = ShortArray(n) { (sin(2 * PI * freq * it / rate) * 10000).toInt().toShort() }
        fun rms(s: ShortArray) = sqrt(s.drop(200).sumOf { it.toDouble() * it } / (s.size - 200))
        for (rate in intArrayOf(48000, 44100, 32000)) {
            val d = Decimator(rate)
            val src = tone(1000.0, rate, rate * 2)
            // feed in uneven blocks like AudioRecord does
            val out = mutableListOf<Short>()
            var i = 0
            while (i < src.size) { val n = minOf(1234, src.size - i); out += d.process(src.copyOfRange(i, i + n)).toList(); i += n }
            val expected = src.size * 16000.0 / rate
            assertTrue("length ${out.size} vs $expected", abs(out.size - expected) < 3)
            val passRms = rms(out.toShortArray())
            assertTrue("1 kHz attenuated: $passRms", passRms > 6500)
            val stop = Decimator(rate).process(tone(11000.0, rate, rate * 2))
            assertTrue("11 kHz not filtered: ${rms(stop)}", rms(stop) < 400)
        }
    }

    @Test
    fun wavRoundTripAndHeaderRepair() {
        val f = File.createTempFile("wavtest", ".wav")
        val samples = ShortArray(16000) { (it % 200 - 100).toShort() }
        f.outputStream().use { Wav.write(it, samples) }
        val info = Wav.info(f)
        assertEquals(16000L, info.totalSamples)
        assertEquals(1000L, info.durationMs)
        assertEquals(samples.toList(), Wav.readSamples(f, info, 0, 16000).toList())
        // simulate a crash: header says 0 bytes but data is there
        RandomAccessFile(f, "rw").use { it.seek(0); it.write(Wav.header(0)) }
        assertEquals(16000L, Wav.info(f).totalSamples)
        Wav.fixHeader(f)
        assertEquals(16000L, Wav.info(f).totalSamples)
        f.delete()
    }

    @Test
    fun normalizeBoostsQuietAudioOnly() {
        val quiet = ShortArray(16000) { (sin(it / 10.0) * 1000).toInt().toShort() }
        val loud = ShortArray(16000) { (sin(it / 10.0) * 30000).toInt().toShort() }
        assertTrue(Dsp.normalize(quiet).maxOf { abs(it.toInt()) } > 7000)
        assertTrue(Dsp.normalize(loud) === loud)
    }

    @Test
    fun groqParseFiltersHallucinations() {
        val body = """{"text":"x","segments":[
          {"start":0.0,"end":4.2,"text":" Goedemorgen allemaal.","no_speech_prob":0.01,"avg_logprob":-0.2,"compression_ratio":1.3},
          {"start":4.2,"end":30.0,"text":" Ondertiteling TV Gelderland 2021","no_speech_prob":0.95,"avg_logprob":-1.1,"compression_ratio":1.0},
          {"start":30.0,"end":34.0,"text":" ja ja ja ja ja ja ja ja ja ja ja ja","no_speech_prob":0.1,"avg_logprob":-0.5,"compression_ratio":4.0},
          {"start":34.0,"end":38.5,"text":" Vandaag behandelen we hoofdstuk drie.","no_speech_prob":0.02,"avg_logprob":-0.3,"compression_ratio":1.4}
        ]}"""
        val segs = VerboseJson.parse(body)
        assertEquals(listOf("Goedemorgen allemaal.", "Vandaag behandelen we hoofdstuk drie."), segs.map { it.text })
        assertEquals(34000L, segs[1].startMs)
    }

    @Test
    fun textFormatCleansAndBuildsParagraphs() {
        val segs = listOf(
            Segment(0, 3000, "Welkom bij dit college."),
            Segment(3000, 6000, "We beginnen met de casus."),
            Segment(6000, 9000, "We beginnen met de casus."),
            Segment(9000, 11000, "Ondertiteld door de Amara.org gemeenschap"),
            Segment(15000, 18000, "Dan het tweede onderwerp."),
            Segment(18000, 19000, "..."),
        )
        val paras = TextFormat.paragraphs(TextFormat.dropHallucinations(segs))
        assertEquals(2, paras.size)
        assertEquals("Welkom bij dit college. We beginnen met de casus.", paras[0].text)
        assertEquals(15000L, paras[1].startMs)
        val txt = TextFormat.plainText("Titel", "datum", paras, true)
        assertTrue(txt.contains("[00:15] Dan het tweede onderwerp."))
        assertEquals("1:01:01", TextFormat.time(3661_000))
    }

    @Test
    fun deepgramParseUsesUtterances() {
        val body = """{"metadata":{},"results":{
          "channels":[{"alternatives":[{"transcript":"Goedemorgen allemaal. Vandaag hoofdstuk drie.","confidence":0.98}]}],
          "utterances":[
            {"start":0.32,"end":2.1,"transcript":"Goedemorgen allemaal.","speaker":0,"channel":0},
            {"start":3.0,"end":5.75,"transcript":" Vandaag hoofdstuk drie. ","speaker":0,"channel":0},
            {"start":6.0,"end":6.5,"transcript":"","speaker":0,"channel":0}
          ]}}"""
        val segs = DeepgramClient.parse(body)
        assertEquals(listOf("Goedemorgen allemaal.", "Vandaag hoofdstuk drie."), segs.map { it.text })
        assertEquals(320L, segs[0].startMs)
        assertEquals(5750L, segs[1].endMs)
        // Deepgram's own sentences win over utterances when paragraphs=true returned them
        val withParagraphs = """{"results":{
          "channels":[{"alternatives":[{"transcript":"x","paragraphs":{"transcript":"x","paragraphs":[
            {"sentences":[{"text":"Welkom bij het college.","start":0.5,"end":2.0},{"text":"Kijk op www.uu.nl.","start":2.2,"end":3.9}],"start":0.5,"end":3.9},
            {"sentences":[{"text":"Dan de casus.","start":9.0,"end":10.25}],"start":9.0,"end":10.25}
          ]}}]}],
          "utterances":[{"start":0.5,"end":10.25,"transcript":"Welkom bij het college. Kijk op www.uu.nl. Dan de casus."}]}}"""
        val sentences = DeepgramClient.parse(withParagraphs)
        assertEquals(listOf("Welkom bij het college.", "Kijk op www.uu.nl.", "Dan de casus."), sentences.map { it.text })
        assertEquals(9000L, sentences[2].startMs)
        // without utterances, fall back to the whole transcript
        val plain = """{"results":{"channels":[{"alternatives":[{"transcript":"Alleen tekst."}]}]}}"""
        assertEquals(listOf("Alleen tekst."), DeepgramClient.parse(plain).map { it.text })
    }

    @Test
    fun voxtralStyleSegmentsWithoutWhisperFields() {
        // Mistral returns OpenAI-style segments without no_speech_prob and friends
        val body = """{"model":"voxtral-mini-latest","text":"Welkom. Dit is college twee.","language":"nl",
          "segments":[{"text":" Welkom.","start":0.0,"end":1.2},{"text":" Dit is college twee.","start":1.5,"end":3.4}]}"""
        val segs = VerboseJson.parse(body)
        assertEquals(listOf("Welkom.", "Dit is college twee."), segs.map { it.text })
        assertEquals(1500L, segs[1].startMs)
    }

    @Test
    fun errorMessagesFromAllServices() {
        assertEquals("Invalid API Key", Http.errorMessage("""{"error":{"message":"Invalid API Key","type":"invalid_request_error"}}"""))
        assertEquals("Invalid credentials.", Http.errorMessage("""{"err_code":"INVALID_AUTH","err_msg":"Invalid credentials.","request_id":"x"}"""))
        assertEquals("Unauthorized", Http.errorMessage("""{"object":"error","message":"Unauthorized","type":"unauthorized"}"""))
        assertEquals("Bad Gateway", Http.errorMessage("Bad Gateway"))
    }

    @Test
    fun vocabularySplitsIntoTerms() {
        assertEquals(
            listOf("prof. Jansen", "dr. El Amrani", "Rawls", "casus Lindenbaum/Cohen"),
            Settings.splitTerms("prof. Jansen, dr. El Amrani;Rawls\n\n casus Lindenbaum/Cohen , Rawls"),
        )
        assertTrue(Settings.splitTerms("  ").isEmpty())
    }

    @Test
    fun hallucinationFilterIsSeparateFromCleaning() {
        // A lecturer may really say a web address; only Whisper output goes through dropHallucinations.
        val real = listOf(Segment(0, 2000, "Kijk op www.uu.nl."))
        assertEquals(1, TextFormat.paragraphs(real).size)
        assertTrue(TextFormat.dropHallucinations(real).isEmpty())
        assertEquals(1, TextFormat.dropHallucinations(listOf(Segment(0, 1, "Ondertiteling TV Gelderland"), Segment(1, 2, "Goedemorgen."))).size)
    }

    @Test
    fun whisperGetsBaseLanguageCode() {
        assertEquals("nl", SettingsState(language = "nl-BE").whisperLanguage)
        assertEquals("nl", SettingsState(language = "nl").whisperLanguage)
        assertEquals("auto", SettingsState(language = "auto").whisperLanguage)
        assertEquals(CloudProvider.DEEPGRAM, SettingsState().cloud)
    }
}
