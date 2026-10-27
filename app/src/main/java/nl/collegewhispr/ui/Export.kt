package nl.collegewhispr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import nl.collegewhispr.data.Lecture
import nl.collegewhispr.data.LectureRepository
import nl.collegewhispr.data.Settings
import nl.collegewhispr.transcribe.Paragraph
import nl.collegewhispr.transcribe.TextFormat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Export {
    private val dateFmt get() = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale("nl", "NL"))

    fun subtitle(l: Lecture) = dateFmt.format(Date(l.createdAt)) + " · " + TextFormat.time(l.durationMs)

    fun safeName(s: String) = s.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(80).ifEmpty { "transcript" }

    fun text(l: Lecture, paras: List<Paragraph>, md: Boolean): String {
        val ts = Settings.current.exportTimestamps
        return if (md) TextFormat.markdown(l.title, subtitle(l), paras, ts)
        else TextFormat.plainText(l.title, subtitle(l), paras, ts)
    }

    fun copy(ctx: Context, l: Lecture, paras: List<Paragraph>) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(l.title, text(l, paras, md = false)))
    }

    fun share(ctx: Context, l: Lecture, paras: List<Paragraph>, md: Boolean) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, safeName(l.title) + if (md) ".md" else ".txt")
        f.writeText(text(l, paras, md))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND)
            .setType(if (md) "text/markdown" else "text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, l.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Transcriptie delen"))
    }

    fun shareAudio(ctx: Context, l: Lecture) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", LectureRepository.audio(l.id))
        val i = Intent(Intent.ACTION_SEND).setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Opname delen"))
    }

    /** Saves to the public Downloads folder. Returns the file name, or null if unsupported. */
    fun saveToDownloads(ctx: Context, l: Lecture, paras: List<Paragraph>, md: Boolean): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val name = safeName(l.title) + if (md) ".md" else ".txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, if (md) "text/markdown" else "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/CollegeWhispr")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text(l, paras, md).toByteArray()) }
        return "Download/CollegeWhispr/$name"
    }
}
