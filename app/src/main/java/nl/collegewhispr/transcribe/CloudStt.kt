package nl.collegewhispr.transcribe

import nl.collegewhispr.data.Segment
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** HTTP error from an online transcription service; [code] is the HTTP status. */
class SttException(message: String, val code: Int, val retryAfterS: Long? = null) : IOException(message)

/** HTTP helpers shared by the Groq, Deepgram and Voxtral clients. */
internal object Http {
    fun open(url: String, readTimeoutMs: Int = 300_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = readTimeoutMs
        }

    /** POSTs the [parts] one after another and returns the response body, or throws [SttException]. */
    fun post(conn: HttpURLConnection, contentType: String, vararg parts: ByteArray): String {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", contentType)
        conn.setFixedLengthStreamingMode(parts.sumOf { it.size.toLong() })
        conn.outputStream.use { out ->
            for (p in parts) {
                var off = 0
                while (off < p.size) {
                    val n = minOf(64 * 1024, p.size - off)
                    out.write(p, off, n)
                    off += n
                }
            }
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val retry = conn.getHeaderField("retry-after")?.toDoubleOrNull()?.toLong()
            throw SttException(errorMessage(body), code, retry)
        }
        return body
    }

    /** Pulls the readable message out of the error bodies of Groq, Deepgram and Mistral. */
    fun errorMessage(body: String): String = runCatching {
        val o = JSONObject(body)
        val e = o.opt("error")
        when {
            e is JSONObject -> e.getString("message")
            o.has("err_msg") -> o.getString("err_msg")
            o.has("message") -> o.get("message").toString()
            o.has("detail") -> o.get("detail").toString()
            e is String -> e
            else -> body.take(200)
        }
    }.getOrDefault(body.take(200))

    /** Checks a key with a GET that costs no audio quota. Returns null when OK, else an error text. */
    fun checkKey(service: String, url: String, auth: String, onOk: (String) -> String?): String? {
        val conn = open(url, 15_000).apply { connectTimeout = 15_000 }
        return try {
            conn.setRequestProperty("Authorization", auth)
            when (val code = conn.responseCode) {
                in 200..299 -> onOk(conn.inputStream.bufferedReader().use { it.readText() })
                401, 403 -> "Ongeldige sleutel"
                else -> "$service antwoordde met code $code"
            }
        } catch (e: IOException) {
            "Geen verbinding met $service (${e.message})"
        } finally {
            conn.disconnect()
        }
    }
}

/** A multipart/form-data body: text fields, then one file whose bytes go between [head] and [tail]. */
internal class Multipart(fields: List<Pair<String, String>>, fileName: String, fileType: String) {
    private val boundary = "----cw" + UUID.randomUUID().toString().replace("-", "")
    val contentType = "multipart/form-data; boundary=$boundary"
    val head: ByteArray = buildString {
        for ((k, v) in fields) {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
            append(v).append("\r\n")
        }
        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
        append("Content-Type: ").append(fileType).append("\r\n\r\n")
    }.toByteArray(Charsets.UTF_8)
    val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
}

/** Parser for the OpenAI-style verbose_json transcripts that Groq and Mistral return. */
object VerboseJson {
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
            val text = s.optString("text").trim()
            if (text.isEmpty()) continue
            out += Segment((s.optDouble("start") * 1000).toLong(), (s.optDouble("end") * 1000).toLong(), text)
        }
        return out
    }
}
