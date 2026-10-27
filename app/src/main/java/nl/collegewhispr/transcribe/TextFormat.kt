package nl.collegewhispr.transcribe

import nl.collegewhispr.data.Segment

data class Paragraph(val startMs: Long, val text: String)

object TextFormat {
    /** Phrases Whisper is known to invent on silence in Dutch (subtitle credits etc). */
    private val hallucinations = listOf(
        "ondertitel", "amara.org", "tv gelderland", "nl-subs", "ondertekst", "subtitles by",
        "www.", "abonneer op", "like en abonneer",
    )

    fun clean(segments: List<Segment>): List<Segment> {
        val out = mutableListOf<Segment>()
        var repeat = 0
        for (s in segments) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            val lower = t.lowercase()
            if (t.length < 90 && hallucinations.any { lower.contains(it) }) continue
            if (t.all { !it.isLetterOrDigit() }) continue
            val prev = out.lastOrNull()
            if (prev != null && prev.text.equals(t, ignoreCase = true)) {
                repeat++
                if (repeat >= 1) continue
            } else repeat = 0
            out += s.copy(text = t)
        }
        return out
    }

    fun paragraphs(segments: List<Segment>): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        val sb = StringBuilder()
        var start = 0L
        var lastEnd = 0L
        for (s in clean(segments)) {
            val gap = s.startMs - lastEnd
            val endsSentence = sb.isNotEmpty() && sb.trimEnd().last() in ".?!"
            val breakHere = sb.isNotEmpty() &&
                ((gap >= 2500 && endsSentence) || (sb.length > 700 && endsSentence) || sb.length > 1400)
            if (breakHere) {
                out += Paragraph(start, sb.toString().trim())
                sb.clear()
            }
            if (sb.isEmpty()) start = s.startMs
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s.text)
            lastEnd = maxOf(lastEnd, s.endMs)
        }
        if (sb.isNotEmpty()) out += Paragraph(start, sb.toString().trim())
        return out
    }

    fun time(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun plainText(title: String, subtitle: String, paras: List<Paragraph>, timestamps: Boolean): String =
        buildString {
            append(title).append('\n').append(subtitle).append("\n\n")
            paras.forEach { p ->
                if (timestamps) append('[').append(time(p.startMs)).append("] ")
                append(p.text).append("\n\n")
            }
        }.trimEnd() + "\n"

    fun markdown(title: String, subtitle: String, paras: List<Paragraph>, timestamps: Boolean): String =
        buildString {
            append("# ").append(title).append("\n\n_").append(subtitle).append("_\n\n")
            paras.forEach { p ->
                if (timestamps) append("**[").append(time(p.startMs)).append("]** ")
                append(p.text).append("\n\n")
            }
        }.trimEnd() + "\n"

    fun bodyOnly(paras: List<Paragraph>): String = paras.joinToString("\n\n") { it.text }
}
