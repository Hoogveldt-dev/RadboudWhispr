package nl.collegewhispr.data

import org.json.JSONArray
import org.json.JSONObject

enum class LectureStatus(val label: String) {
    RECORDING("Opname loopt"),
    IMPORTING("Wordt geïmporteerd"),
    RECORDED("Opgenomen"),
    QUEUED("In de wachtrij"),
    TRANSCRIBING("Bezig met transcriberen"),
    WAITING("Wacht op internet of offline model"),
    DONE("Klaar"),
    ERROR("Fout");
}

data class Segment(val startMs: Long, val endMs: Long, val text: String) {
    fun toJson() = JSONObject().put("s", startMs).put("e", endMs).put("t", text)

    companion object {
        fun fromJson(o: JSONObject) = Segment(o.getLong("s"), o.getLong("e"), o.getString("t"))
    }
}

data class Chunk(
    val startSample: Long,
    val endSample: Long,
    val done: Boolean = false,
    val engine: String? = null,
    val segments: List<Segment> = emptyList(),
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        segments.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("start", startSample).put("end", endSample)
            .put("done", done).put("engine", engine ?: JSONObject.NULL)
            .put("segments", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Chunk {
            val arr = o.optJSONArray("segments") ?: JSONArray()
            return Chunk(
                startSample = o.getLong("start"),
                endSample = o.getLong("end"),
                done = o.optBoolean("done"),
                engine = if (o.isNull("engine")) null else o.optString("engine"),
                segments = (0 until arr.length()).map { Segment.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}

data class Lecture(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    val status: LectureStatus = LectureStatus.RECORDED,
    val message: String? = null,
    val progress: Float = 0f,
    val engines: String? = null,
    val hasAudio: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("createdAt", createdAt)
        .put("durationMs", durationMs).put("status", status.name)
        .put("message", message ?: JSONObject.NULL).put("progress", progress.toDouble())
        .put("engines", engines ?: JSONObject.NULL).put("hasAudio", hasAudio)

    companion object {
        fun fromJson(o: JSONObject) = Lecture(
            id = o.getString("id"),
            title = o.getString("title"),
            createdAt = o.getLong("createdAt"),
            durationMs = o.optLong("durationMs"),
            status = runCatching { LectureStatus.valueOf(o.getString("status")) }
                .getOrDefault(LectureStatus.RECORDED),
            message = if (o.isNull("message")) null else o.optString("message"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            engines = if (o.isNull("engines")) null else o.optString("engines"),
            hasAudio = o.optBoolean("hasAudio", true),
        )
    }
}
