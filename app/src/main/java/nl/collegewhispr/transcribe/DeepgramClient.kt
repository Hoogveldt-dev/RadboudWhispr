package nl.collegewhispr.transcribe

import nl.collegewhispr.data.Segment
import org.json.JSONObject
import java.net.URLEncoder

/** Deepgram pre-recorded transcription with Nova-3 (it has a dedicated Dutch model). */
object DeepgramClient {
    private const val URL_LISTEN = "https://api.deepgram.com/v1/listen"

    /**
     * Transcribes one WAV chunk. [terms] are names and jargon to boost (Deepgram "keyterms").
     * Returned segment times are relative to the chunk start.
     */
    fun transcribe(apiKey: String, wav: ByteArray, language: String?, terms: List<String>): List<Segment> {
        val query = mutableListOf("model=nova-3", "smart_format=true", "utterances=true")
        query += if (language.isNullOrBlank() || language == "auto") "detect_language=true" else "language=" + enc(language)
        terms.forEach { query += "keyterm=" + enc(it) }
        val conn = Http.open(URL_LISTEN + "?" + query.joinToString("&"))
        try {
            conn.setRequestProperty("Authorization", "Token ${apiKey.trim()}")
            return parse(Http.post(conn, "audio/wav", wav))
        } finally {
            conn.disconnect()
        }
    }

    /** Checks the key without using any audio quota. Returns null when OK, else an error text. */
    fun checkKey(apiKey: String): String? =
        Http.checkKey("Deepgram", "https://api.deepgram.com/v1/projects", "Token ${apiKey.trim()}") { null }

    /** Uses the utterances (speech split at pauses, with times); falls back to the whole transcript. */
    fun parse(body: String): List<Segment> {
        val results = JSONObject(body).getJSONObject("results")
        val utterances = results.optJSONArray("utterances")
        if (utterances != null && utterances.length() > 0) {
            return (0 until utterances.length()).mapNotNull { i ->
                val u = utterances.getJSONObject(i)
                val text = u.optString("transcript").trim()
                if (text.isEmpty()) null
                else Segment((u.optDouble("start") * 1000).toLong(), (u.optDouble("end") * 1000).toLong(), text)
            }
        }
        val text = results.optJSONArray("channels")?.optJSONObject(0)
            ?.optJSONArray("alternatives")?.optJSONObject(0)
            ?.optString("transcript")?.trim().orEmpty()
        return if (text.isEmpty()) emptyList() else listOf(Segment(0, 0, text))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
