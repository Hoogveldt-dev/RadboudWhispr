package nl.collegewhispr.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nl.collegewhispr.data.CloudProvider
import nl.collegewhispr.data.EngineMode
import nl.collegewhispr.data.LectureRepository
import nl.collegewhispr.data.LectureStatus
import nl.collegewhispr.service.TranscriptionService
import nl.collegewhispr.transcribe.Paragraph
import nl.collegewhispr.transcribe.TextFormat

@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lectures by LectureRepository.lectures.collectAsState()
    val tx by TranscriptionService.state.collectAsState()
    val lecture = lectures.firstOrNull { it.id == id }
    if (lecture == null) { LaunchedEffect(Unit) { onBack() }; return }

    // Earlier transcripts (kept when re-transcribing) to compare engines; null shows the current one.
    var versionsTick by remember { mutableIntStateOf(0) }
    val versions = remember(lecture.status, versionsTick) { LectureRepository.versions(id) }
    var picked by remember { mutableStateOf<String?>(null) }
    val version = picked?.takeIf { it in versions }
    // Re-read segments whenever progress or status changes.
    val paras = remember(lecture.status, lecture.progress, version) {
        TextFormat.paragraphs(version?.let { LectureRepository.versionSegments(id, it) } ?: LectureRepository.segments(id))
    }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    // --- audio player ---
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    val recording = lecture.status == LectureStatus.RECORDING
    DisposableEffect(id, lecture.hasAudio, recording) {
        val f = LectureRepository.audio(id)
        player = if (lecture.hasAudio && f.exists() && !recording) {
            runCatching { MediaPlayer().apply { setDataSource(f.absolutePath); prepare() } }.getOrNull()
        } else null
        onDispose { player?.release(); player = null; playing = false }
    }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { position = it.currentPosition.toFloat() }
            if (player?.isPlaying != true) playing = false
            delay(250)
        }
    }
    fun seekPlay(ms: Long) {
        player?.let { it.seekTo(ms.toInt()); it.start(); playing = true }
    }

    val ios = LocalIos.current
    val list = rememberLazyListState()
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }

    Scaffold(
        containerColor = ios.background,
        topBar = {
            NavBar(title = if (collapsed) lecture.title else null, onBack = onBack, backLabel = "Colleges") {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(Icons.Filled.Search, "Zoeken", tint = ios.accent)
                }
                IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, "Naam wijzigen", tint = ios.accent) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreHoriz, "Meer", tint = ios.accent) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        CloudProvider.entries.forEach { p ->
                            MenuItem("Opnieuw transcriberen met ${p.label}", enabled = lecture.hasAudio) {
                                menu = false; picked = null
                                TranscriptionService.enqueue(ctx, id, reset = true, engine = EngineMode.ONLINE_ONLY, provider = p)
                            }
                        }
                        MenuItem("Opnieuw transcriberen (offline)", enabled = lecture.hasAudio) {
                            menu = false; picked = null
                            TranscriptionService.enqueue(ctx, id, reset = true, engine = EngineMode.OFFLINE_ONLY)
                        }
                        MenuItem("Opname delen (.wav)", enabled = lecture.hasAudio) {
                            menu = false; Export.shareAudio(ctx, lecture)
                        }
                        MenuItem("Delen als Markdown", enabled = paras.isNotEmpty()) {
                            menu = false; Export.share(ctx, lecture, paras, md = true)
                        }
                        MenuItem(
                            "Audio verwijderen (tekst blijft)", destructive = true,
                            enabled = lecture.hasAudio && lecture.status == LectureStatus.DONE,
                        ) { menu = false; player?.release(); player = null; LectureRepository.deleteAudio(id) }
                        MenuItem("College verwijderen", destructive = true) { menu = false; confirmDelete = true }
                    }
                }
            }
        },
        bottomBar = {
            player?.let { p ->
                Column(Modifier.background(ios.card).windowInsetsPadding(WindowInsets.navigationBars)) {
                    Separator(inset = 0.dp)
                    Row(
                        Modifier.widthIn(max = ContentWidth).fillMaxWidth().align(Alignment.CenterHorizontally)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(ios.accent).clickable {
                                if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Afspelen", tint = Color.White) }
                        Spacer(Modifier.width(14.dp))
                        Text(TextFormat.time(position.toLong()), style = IosType.footnote.copy(fontFeatureSettings = "tnum"), color = ios.secondaryLabel)
                        IosSlider(
                            value = position, max = maxOf(1f, p.duration.toFloat()),
                            onChange = { position = it; p.seekTo(it.toInt()) },
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text(TextFormat.time(p.duration.toLong()), style = IosType.footnote.copy(fontFeatureSettings = "tnum"), color = ios.secondaryLabel)
                    }
                }
            }
        },
    ) { pad ->
        val shown = if (query.isBlank()) paras else paras.filter { it.text.contains(query, ignoreCase = true) }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            state = list,
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { LargeTitle(lecture.title) }
            item {
                Group(footer = lecture.engines?.let { "Getranscribeerd met $it" }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(Export.subtitle(lecture), style = IosType.subhead, color = ios.secondaryLabel)
                        Spacer(Modifier.height(6.dp))
                        StatusLine(lecture, if (tx.lectureId == id) tx.chunk to tx.chunks else null)
                        when (lecture.status) {
                            LectureStatus.TRANSCRIBING, LectureStatus.QUEUED -> {
                                Spacer(Modifier.height(12.dp))
                                PillButton("Stoppen", onClick = { TranscriptionService.cancel(ctx, id) }, filled = false, color = ios.red)
                            }
                            LectureStatus.RECORDED, LectureStatus.WAITING, LectureStatus.ERROR -> if (lecture.hasAudio) {
                                Spacer(Modifier.height(12.dp))
                                PillButton(
                                    if (lecture.status == LectureStatus.RECORDED) "Transcriberen" else "Opnieuw proberen",
                                    onClick = { TranscriptionService.enqueue(ctx, id) },
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
            if (versions.isNotEmpty()) item {
                Group(header = "Versies", footer = "Eerdere transcripties, om diensten te vergelijken.") {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Capsule("Nu: " + (lecture.engines ?: lecture.status.label), version == null) { picked = null }
                        versions.asReversed().forEach { v -> Capsule(v, version == v) { picked = v } }
                    }
                    version?.let { v ->
                        Separator()
                        ActionRow("Deze versie verwijderen", color = ios.red, onClick = {
                            LectureRepository.deleteVersion(id, v); picked = null; versionsTick++
                        })
                    }
                }
            }
            if (paras.isNotEmpty()) item {
                Row(
                    Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ActionTile(Icons.Filled.ContentCopy, "Kopiëren", Modifier.weight(1f)) {
                        Export.copy(ctx, lecture, paras)
                        Toast.makeText(ctx, "Gekopieerd", Toast.LENGTH_SHORT).show()
                    }
                    ActionTile(Icons.Filled.Share, "Delen", Modifier.weight(1f)) { Export.share(ctx, lecture, paras, md = false) }
                    Box(Modifier.weight(1f)) {
                        ActionTile(Icons.Filled.Download, "Opslaan", Modifier.fillMaxWidth()) { saveMenu = true }
                        DropdownMenu(saveMenu, onDismissRequest = { saveMenu = false }) {
                            listOf(false to "Als tekst (.txt)", true to "Als Markdown (.md)").forEach { (md, label) ->
                                MenuItem(label) {
                                    saveMenu = false
                                    val r = Export.saveToDownloads(ctx, lecture, paras, md)
                                    Toast.makeText(ctx, r?.let { "Opgeslagen in $it" } ?: "Gebruik Delen op deze Android-versie",
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            }
            if (searching) item {
                Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SearchField(query, { query = it }, "Zoeken in transcriptie")
                    if (query.isNotBlank()) {
                        Text(
                            "${shown.size} alinea's gevonden", style = IosType.footnote, color = ios.secondaryLabel,
                            modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                        )
                    }
                }
            }
            if (shown.isNotEmpty()) {
                item {
                    Text(
                        "TRANSCRIPTIE", style = IosType.footnote, color = ios.secondaryLabel,
                        modifier = Modifier.widthIn(max = ContentWidth).fillMaxWidth()
                            .padding(start = 32.dp, top = 14.dp, bottom = 6.dp),
                    )
                }
                itemsIndexed(shown) { i, p ->
                    Column(
                        Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(horizontal = 16.dp)
                            .clip(groupShape(i, shown.size)).background(ios.card),
                    ) {
                        ParagraphView(p, query, canPlay = player != null) { seekPlay(p.startMs) }
                        if (i < shown.size - 1) Separator()
                    }
                }
            }
        }
    }

    if (rename) {
        var title by remember { mutableStateOf(lecture.title) }
        IosAlert(
            title = "Naam wijzigen",
            confirm = "Opslaan",
            onConfirm = {
                if (title.isNotBlank()) LectureRepository.update(id) { it.copy(title = title.trim()) }
                rename = false
            },
            onDismiss = { rename = false },
            body = {
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                        .background(ios.fill).padding(horizontal = 10.dp, vertical = 8.dp),
                ) { IosTextField(title, { title = it }, "Naam", Modifier.fillMaxWidth()) }
            },
        )
    }
    if (confirmDelete) {
        IosAlert(
            title = "College verwijderen?",
            message = "De opname en de transcriptie worden definitief verwijderd.",
            confirm = "Verwijderen",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                if (lecture.status == LectureStatus.QUEUED || lecture.status == LectureStatus.TRANSCRIBING) {
                    TranscriptionService.cancel(ctx, id)
                }
                player?.release(); player = null
                LectureRepository.delete(id)
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val ios = LocalIos.current
    DropdownMenuItem(
        text = {
            Text(
                label, style = IosType.body,
                color = when {
                    !enabled -> ios.tertiaryLabel
                    destructive -> ios.red
                    else -> ios.label
                },
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/** Square-ish button with a symbol above a label, like iOS share actions. */
@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val ios = LocalIos.current
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(ios.card).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = ios.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = IosType.caption.copy(fontWeight = FontWeight.Medium), color = ios.accent)
    }
}

@Composable
private fun ParagraphView(p: Paragraph, query: String, canPlay: Boolean, onPlay: () -> Unit) {
    val ios = LocalIos.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        val tag = Modifier.clip(CircleShape).background(if (canPlay) ios.accent.copy(alpha = 0.12f) else ios.fill)
        Row(
            (if (canPlay) tag.clickable(onClick = onPlay) else tag).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canPlay) {
                Icon(Icons.Filled.PlayArrow, "Afspelen vanaf hier", tint = ios.accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
            }
            Text(
                TextFormat.time(p.startMs),
                style = IosType.caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = if (canPlay) ios.accent else ios.secondaryLabel,
            )
        }
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(highlight(p.text, query), style = IosType.body.copy(lineHeight = 26.sp), color = ios.label)
        }
    }
}

@Composable
private fun highlight(text: String, query: String) = buildAnnotatedString {
    if (query.isBlank()) { append(text); return@buildAnnotatedString }
    var i = 0
    val lower = text.lowercase()
    val q = query.lowercase()
    while (i < text.length) {
        val j = lower.indexOf(q, i)
        if (j < 0) { append(text.substring(i)); break }
        append(text.substring(i, j))
        // iOS "find in page" yellow
        withStyle(SpanStyle(background = Color(0x80FFCC00), fontWeight = FontWeight.SemiBold)) {
            append(text.substring(j, j + q.length))
        }
        i = j + q.length
    }
}
