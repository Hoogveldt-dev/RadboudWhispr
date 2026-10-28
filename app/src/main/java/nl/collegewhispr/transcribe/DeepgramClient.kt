package nl.collegewhispr.transcribe

import nl.collegewhispr.data.Segment
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/** Deepgram pre-recorded transcription with Nova-3 (it has dedicated Dutch and Flemish models). */
object DeepgramClient {
    private const val API = "https://api.deepgram.com/v1"

    /**
     * Transcribes one WAV chunk. [language] is a Deepgram code ("nl", "nl-BE", "en") or "auto";
     * [terms] are names and jargon to boost (Deepgram "keyterms").
     * Returned segment times are relative to the chunk start.
     */
    fun transcribe(apiKey: String, wav: ByteArray, language: String?, terms: List<String>): List<Segment> {
        val query = mutableListOf("model=nova-3", "smart_format=true", "paragraphs=true", "utterances=true")
        query += if (language.isNullOrBlank() || language == "auto") "detect_language=true" else "language=" + enc(language)
        terms.forEach { query += "keyterm=" + enc(it) }
        val conn = Http.open("$API/listen?" + query.joinToString("&"))
        try {
            conn.setRequestProperty("Authorization", "Token ${apiKey.trim()}")
            return parse(Http.post(conn, "audio/wav", wav))
        } finally {
            conn.disconnect()
        }
    }

    /** Checks the key without using any audio quota. Returns null when OK, else an error text. */
    fun checkKey(apiKey: String): String? =
        Http.checkKey("Deepgram", "$API/projects", "Token ${apiKey.trim()}") { null }

    /** Remaining credit, e.g. "Tegoed: 199,57 USD", or null if the key may not read billing. */
    fun balance(apiKey: String): String? = runCatching {
        val projects = JSONObject(get("$API/projects", apiKey)).getJSONArray("projects")
        val totals = linkedMapOf<String, Double>()
        for (i in 0 until projects.length()) {
            val id = projects.getJSONObject(i).getString("project_id")
            val balances = JSONObject(get("$API/projects/${enc(id)}/balances", apiKey)).getJSONArray("balances")
            for (j in 0 until balances.length()) {
                val b = balances.getJSONObject(j)
                val units = b.optString("units", "usd").uppercase(Locale.ROOT)
                totals[units] = (totals[units] ?: 0.0) + b.optDouble("amount", 0.0)
            }
        }
        if (totals.isEmpty()) null
        else "Tegoed: " + totals.entries.joinToString(" + ") { (u, a) -> String.format(Locale("nl", "NL"), "%.2f %s", a, u) }
    }.getOrNull()

    private fun get(url: String, apiKey: String): String {
        val conn = Http.open(url, 15_000).apply { connectTimeout = 15_000 }
        try {
            conn.setRequestProperty("Authorization", "Token ${apiKey.trim()}")
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Prefers Deepgram's sentences (from paragraphs=true): whole sentences with their own times
     * make the cleanest segments. Falls back to utterances (speech split at pauses), then to the
     * whole transcript.
     */
    fun parse(body: String): List<Segment> {
        val results = JSONObject(body).getJSONObject("results")
        val alt = results.optJSONArray("channels")?.optJSONObject(0)
            ?.optJSONArray("alternatives")?.optJSONObject(0)

        val paragraphs = alt?.optJSONObject("paragraphs")?.optJSONArray("paragraphs")
        val sentences = paragraphs?.let { p ->
            (0 until p.length()).flatMap { i -> timed(p.getJSONObject(i).optJSONArray("sentences"), "text") }
        }.orEmpty()
        if (sentences.isNotEmpty()) return sentences

        val utterances = timed(results.optJSONArray("utterances"), "transcript")
        if (utterances.isNotEmpty()) return utterances

        val text = alt?.optString("transcript")?.trim().orEmpty()
        return if (text.isEmpty()) emptyList() else listOf(Segment(0, 0, text))
    }

    private fun timed(arr: JSONArray?, textKey: String): List<Segment> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val text = o.optString(textKey).trim()
            if (text.isEmpty()) null
            else Segment((o.optDouble("start") * 1000).toLong(), (o.optDouble("end") * 1000).toLong(), text)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
