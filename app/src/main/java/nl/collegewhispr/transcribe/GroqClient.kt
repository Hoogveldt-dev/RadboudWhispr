package nl.collegewhispr.transcribe

import nl.collegewhispr.data.Segment

/** Groq's OpenAI-compatible Whisper endpoint. */
object GroqClient {
    private const val URL_TRANSCRIBE = "https://api.groq.com/openai/v1/audio/transcriptions"

    /**
     * Transcribes one WAV chunk. Returned segment times are relative to the chunk start.
     */
    fun transcribe(apiKey: String, model: String, wav: ByteArray, language: String?, prompt: String?): List<Segment> {
        val fields = mutableListOf("model" to model, "response_format" to "verbose_json", "temperature" to "0")
        if (!language.isNullOrBlank() && language != "auto") fields += "language" to language
        if (!prompt.isNullOrBlank()) fields += "prompt" to prompt
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
        Http.checkKey("Groq", "https://api.groq.com/openai/v1/models", "Bearer ${apiKey.trim()}") { body ->
            if (body.contains("whisper")) null else "Sleutel werkt, maar geen Whisper-model beschikbaar"
        }
}
