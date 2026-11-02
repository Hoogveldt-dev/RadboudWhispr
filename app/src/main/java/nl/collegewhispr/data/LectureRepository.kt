package nl.collegewhispr.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.collegewhispr.audio.Wav
import nl.collegewhispr.transcribe.TextFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object LectureRepository {
    private lateinit var root: File
    private val _lectures = MutableStateFlow<List<Lecture>>(emptyList())
    val lectures: StateFlow<List<Lecture>> get() = _lectures

    /** Ids whose recording is live in this process (to tell crashes from live recordings). */
    @Volatile var liveRecordingId: String? = null

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "lectures").apply { mkdirs() }
        reload()
        repairInterrupted()
    }

    fun dir(id: String) = File(root, id)
    fun audio(id: String) = File(dir(id), "audio.wav")
    private fun metaFile(id: String) = File(dir(id), "meta.json")
    private fun chunksFile(id: String) = File(dir(id), "chunks.json")

    @Synchronized
    fun reload() {
        val list = root.listFiles()?.mapNotNull { d ->
            runCatching { Lecture.fromJson(JSONObject(File(d, "meta.json").readText())) }.getOrNull()
        } ?: emptyList()
        _lectures.value = list.sortedByDescending { it.createdAt }
    }

    fun get(id: String): Lecture? = _lectures.value.firstOrNull { it.id == id }

    fun newLecture(status: LectureStatus = LectureStatus.RECORDING): Lecture {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("EEEE d MMMM, HH:mm", Locale("nl", "NL"))
        val title = "Hoorcollege " + fmt.format(Date(now))
        val l = Lecture(UUID.randomUUID().toString(), title, now, status = status)
        dir(l.id).mkdirs()
        save(l)
        return l
    }

    @Synchronized
    fun save(l: Lecture) {
        dir(l.id).mkdirs()
        val tmp = File(dir(l.id), "meta.json.tmp")
        tmp.writeText(l.toJson().toString())
        tmp.renameTo(metaFile(l.id))
        _lectures.value = (_lectures.value.filterNot { it.id == l.id } + l).sortedByDescending { it.createdAt }
    }

    fun update(id: String, block: (Lecture) -> Lecture) {
        synchronized(this) {
            val cur = get(id) ?: return
            save(block(cur))
        }
    }

    fun delete(id: String) {
        synchronized(this) {
            dir(id).deleteRecursively()
            _lectures.value = _lectures.value.filterNot { it.id == id }
        }
    }

    fun deleteAudio(id: String) {
        audio(id).delete()
        update(id) { it.copy(hasAudio = false) }
    }

    fun loadChunks(id: String): List<Chunk>? {
        val f = chunksFile(id)
        if (!f.exists()) return null
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Chunk.fromJson(arr.getJSONObject(it)) }
        }.getOrNull()
    }

    @Synchronized
    fun saveChunks(id: String, chunks: List<Chunk>) {
        val arr = JSONArray()
        chunks.forEach { arr.put(it.toJson()) }
        val tmp = File(dir(id), "chunks.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(chunksFile(id))
    }

    fun clearChunks(id: String) {
        chunksFile(id).delete()
    }

    fun segments(id: String): List<Segment> = readable(loadChunks(id) ?: emptyList())

    /** Segments of the finished chunks, with Whisper hallucinations removed where Whisper made them. */
    private fun readable(chunks: List<Chunk>): List<Segment> = chunks.filter { it.done }.flatMap { c ->
        if (c.engine?.startsWith("Deepgram") == true) c.segments else TextFormat.dropHallucinations(c.segments)
    }

    // --- earlier transcripts, kept to compare engines ---

    private fun versionsDir(id: String) = File(dir(id), "versions")

    /** Before a re-run overwrites it, keeps a finished transcript under the name of its engine(s). */
    fun archiveTranscript(id: String) {
        val l = get(id) ?: return
        val src = chunksFile(id)
        if (l.status != LectureStatus.DONE || !src.exists()) return
        val name = (l.engines ?: "Eerdere versie").replace(Regex("[^\\p{L}\\p{N} ()+._-]"), "_").take(80)
        versionsDir(id).mkdirs()
        src.copyTo(File(versionsDir(id), "$name.json"), overwrite = true)
    }

    /** Names of the kept transcripts, oldest first. */
    fun versions(id: String): List<String> =
        versionsDir(id).listFiles { f -> f.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }?.map { it.nameWithoutExtension } ?: emptyList()

    fun versionSegments(id: String, name: String): List<Segment> = runCatching {
        val arr = JSONArray(File(versionsDir(id), "$name.json").readText())
        readable((0 until arr.length()).map { Chunk.fromJson(arr.getJSONObject(it)) })
    }.getOrDefault(emptyList())

    fun deleteVersion(id: String, name: String) {
        File(versionsDir(id), "$name.json").delete()
    }

    /** Recordings cut off by a crash or a killed app: finalise the WAV so nothing is lost. */
    private fun repairInterrupted() {
        _lectures.value.filter {
            it.status == LectureStatus.RECORDING || it.status == LectureStatus.TRANSCRIBING ||
                it.status == LectureStatus.IMPORTING
        }.forEach { l ->
            if (l.status == LectureStatus.IMPORTING) {
                // Only part of the file was converted; the original is still on the device.
                val a = audio(l.id)
                if (a.exists() && a.length() > Wav.HEADER_SIZE) {
                    runCatching { Wav.fixHeader(a) }
                    val dur = runCatching { Wav.info(a).durationMs }.getOrDefault(0L)
                    save(l.copy(status = LectureStatus.RECORDED, durationMs = dur,
                        message = "Importeren werd onderbroken; alleen het begin is omgezet. Importeer het bestand opnieuw."))
                } else {
                    delete(l.id)
                }
            } else if (l.status == LectureStatus.RECORDING) {
                val a = audio(l.id)
                if (a.exists() && a.length() > Wav.HEADER_SIZE) {
                    runCatching { Wav.fixHeader(a) }
                    val dur = runCatching { Wav.info(a).durationMs }.getOrDefault(0L)
                    save(l.copy(status = LectureStatus.RECORDED, durationMs = dur,
                        message = "Opname werd onderbroken; tot dat moment opgeslagen."))
                } else {
                    delete(l.id)
                }
            } else {
                save(l.copy(status = LectureStatus.QUEUED, message = "Transcriptie werd onderbroken; wordt hervat."))
            }
        }
    }
}
