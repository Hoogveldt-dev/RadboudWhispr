package nl.collegewhispr.transcribe

import nl.collegewhispr.data.Segment

/** Mistral's Voxtral transcription endpoint (OpenAI-style multipart upload). */
object VoxtralClient {
    private const val URL_TRANSCRIBE = "https://api.mistral.ai/v1/audio/transcriptions"
    const val MODEL = "voxtral-mini-latest"

    /**
     * Transcribes one WAV chunk. [terms] are names and jargon to bias towards ("context_bias";
     * Mistral calls this experimental for languages other than English).
     * Returned segment times are relative to the chunk start.
     *
     * No language is sent: Mistral does not accept it together with timestamps, and the
     * timestamps are needed for paragraphs and playback. Voxtral detects the language itself,
     * which is reliable on minutes of speech.
     */
    fun transcribe(apiKey: String, wav: ByteArray, terms: List<String>): List<Segment> {
        val fields = mutableListOf("model" to MODEL, "timestamp_granularities" to "segment")
        terms.forEach { fields += "context_bias" to it }
        val body = Multipart(fields, "chunk.wav", "audio/wav")
        val conn = Http.open(URL_TRANSCRIBE)
        try {
            conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            return VerboseJson.parse(Http.post(conn, body.contentType, body.head, wav, body.tail))
        } finally {
            conn.disconnect()
        }
    }

    /** Checks the key without using any audio quota. Returns null when OK, else an error text. */
    fun checkKey(apiKey: String): String? =
        Http.checkKey("Mistral", "https://api.mistral.ai/v1/models", "Bearer ${apiKey.trim()}") { body ->
            if (body.contains("voxtral")) null else "Sleutel werkt, maar geen Voxtral-model beschikbaar"
        }
}
