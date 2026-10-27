package nl.collegewhispr.transcribe

import nl.collegewhispr.data.Segment
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class GroqException(message: String, val code: Int, val retryAfterS: Long? = null) : IOException(message)

/** Groq's OpenAI-compatible Whisper endpoint. */
object GroqClient {
    private const val URL_TRANSCRIBE = "https://api.groq.com/openai/v1/audio/transcriptions"

    /**
     * Transcribes one WAV chunk. Returned segment times are relative to the chunk start.
     */
    fun transcribe(apiKey: String, model: String, wav: ByteArray, language: String?, prompt: String?): List<Segment> {
        val boundary = "----cw" + UUID.randomUUID().toString().replace("-", "")
        val parts = mutableListOf<Pair<String, String>>()
        parts += "model" to model
        parts += "response_format" to "verbose_json"
        parts += "temperature" to "0"
        if (!language.isNullOrBlank() && language != "auto") parts += "language" to language
        if (!prompt.isNullOrBlank()) parts += "prompt" to prompt

        val head = StringBuilder()
        for ((k, v) in parts) {
            head.append("--").append(boundary).append("\r\n")
            head.append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
            head.append(v).append("\r\n")
        }
        head.append("--").append(boundary).append("\r\n")
        head.append("Content-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\n")
        head.append("Content-Type: audio/wav\r\n\r\n")
        val headBytes = head.toString().toByteArray(Charsets.UTF_8)
        val tailBytes = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val conn = URL(URL_TRANSCRIBE).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 300_000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(headBytes.size.toLong() + wav.size + tailBytes.size)
            DataOutputStream(conn.outputStream).use { out ->
                out.write(headBytes)
                var off = 0
                while (off < wav.size) {
                    val n = minOf(64 * 1024, wav.size - off)
                    out.write(wav, off, n)
                    off += n
                }
                out.write(tailBytes)
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }
                    .getOrDefault(body.take(200))
                val retry = conn.getHeaderField("retry-after")?.toDoubleOrNull()?.toLong()
                throw GroqException(msg, code, retry)
            }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    /** Checks the key without using any audio quota. Returns null when OK, else an error text. */
    fun checkKey(apiKey: String): String? {
        val conn = URL("https://api.groq.com/openai/v1/models").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            when (val code = conn.responseCode) {
                in 200..299 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (body.contains("whisper")) null else "Sleutel werkt, maar geen Whisper-model beschikbaar"
                }
                401, 403 -> "Ongeldige sleutel"
                else -> "Groq antwoordde met code $code"
            }
        } catch (e: IOException) {
            "Geen verbinding met Groq (${e.message})"
        } finally {
            conn.disconnect()
        }
    }

    fun parse(body: String): List<Segment> {
        val json = JSONObject(body)
        val segs = json.optJSONArray("segments")
        if (segs == null || segs.length() == 0) {
            val text = json.optString("text").trim()
            return if (text.isEmpty()) emptyList() else listOf(Segment(0, 0, text))
        }
        val out = mutableListOf<Segment>()
        for (i in 0 until segs.length()) {
            val s = segs.getJSONObject(i)
            val noSpeech = s.optDouble("no_speech_prob", 0.0)
            val avgLogprob = s.optDouble("avg_logprob", 0.0)
            val compression = s.optDouble("compression_ratio", 1.0)
            // Drop likely hallucinations: silence that Whisper filled in, or looping text.
            if (noSpeech > 0.8 && avgLogprob < -0.7) continue
            if (compression > 2.6) continue
            out += Segment(
                (s.optDouble("start") * 1000).toLong(),
                (s.optDouble("end") * 1000).toLong(),
                s.optString("text").trim(),
            )
        }
        return out
    }
}
