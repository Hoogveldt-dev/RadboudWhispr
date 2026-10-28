package nl.collegewhispr.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.collegewhispr.data.CloudProvider
import nl.collegewhispr.data.EngineMode
import nl.collegewhispr.data.Settings
import nl.collegewhispr.data.SettingsState
import nl.collegewhispr.transcribe.DeepgramClient
import nl.collegewhispr.transcribe.GroqClient
import nl.collegewhispr.transcribe.VoxtralClient
import nl.collegewhispr.whisper.ModelInfo
import nl.collegewhispr.whisper.ModelManager
import nl.collegewhispr.whisper.WhisperLib

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ios = LocalIos.current
    val ctx = LocalContext.current
    val s by Settings.state.collectAsState()
    val installed by ModelManager.installed.collectAsState()
    val downloads by ModelManager.downloads.collectAsState()
    val scroll = rememberScrollState()
    val collapsed by remember { derivedStateOf { scroll.value > 120 } }

    LaunchedEffect(Unit) {
        while (true) { ModelManager.poll(ctx); delay(1000) }
    }

    Scaffold(
        containerColor = ios.background,
        topBar = { NavBar(title = if (collapsed) "Instellingen" else null, onBack = onBack, backLabel = "Colleges") },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(scroll).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LargeTitle("Instellingen")

            ProviderGroup(
                CloudProvider.DEEPGRAM, s,
                header = "Online transcriptie",
                subtitle = "Aanbevolen · eigen model voor Nederlands en Vlaams",
                footer = "Deepgram zet je opname na het college om naar tekst. Bij een nieuw account krijg je gratis " +
                    "startegoed; daarna kost een college van 90 minuten ongeveer \$0,40. Sleutel aanmaken: maak een " +
                    "account aan, ga naar API Keys, maak een nieuwe sleutel aan en kopieer die hierheen.",
                check = { key -> DeepgramClient.checkKey(key) ?: DeepgramClient.balance(key)?.let { "Sleutel werkt. $it" } },
            )

            var others by remember {
                mutableStateOf(s.cloud != CloudProvider.DEEPGRAM || s.groqKey.isNotBlank() || s.mistralKey.isNotBlank())
            }
            Group(footer = if (others) null else "Alleen nodig als je wilt vergelijken.") {
                IosRow({ others = !others }) {
                    Text("Andere diensten", style = IosType.body, color = ios.label, modifier = Modifier.weight(1f))
                    Text("Groq, Voxtral", style = IosType.body, color = ios.secondaryLabel)
                    Spacer(Modifier.width(6.dp))
                    Icon(if (others) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = ios.tertiaryLabel)
                }
            }
            if (others) {
                ProviderGroup(
                    CloudProvider.GROQ, s,
                    header = "Groq",
                    subtitle = "Gratis: ongeveer 8 uur audio per dag (Whisper)",
                    footer = "Vergelijken: open een college, tik op ··· en kies Opnieuw transcriberen met Groq.",
                    check = { GroqClient.checkKey(it) },
                ) {
                    Separator()
                    CheckRow("whisper-large-v3", s.groqModel == "whisper-large-v3", "Nauwkeurigst") {
                        Settings.update { it.copy(groqModel = "whisper-large-v3") }
                    }
                    Separator()
                    CheckRow("whisper-large-v3-turbo", s.groqModel == "whisper-large-v3-turbo", "Sneller, iets minder nauwkeurig") {
                        Settings.update { it.copy(groqModel = "whisper-large-v3-turbo") }
                    }
                }
                ProviderGroup(
                    CloudProvider.VOXTRAL, s,
                    header = "Voxtral (Mistral)",
                    subtitle = "Vraagt om een betaald plan",
                    footer = "Herkent de taal zelf; vaktermen werken voor Nederlands nog experimenteel.",
                    check = { VoxtralClient.checkKey(it) },
                )
            }

            Group(header = "Taal") {
                val langs = listOf("nl" to "Nederlands", "nl-BE" to "Nederlands (Vlaams)", "en" to "Engels", "auto" to "Automatisch herkennen")
                langs.forEachIndexed { i, (code, label) ->
                    if (i > 0) Separator()
                    CheckRow(label, s.language == code) { Settings.update { it.copy(language = code) } }
                }
            }

            Group(
                header = "Vaktermen en namen",
                footer = "Namen van docenten, afkortingen en vaktermen die vaak terugkomen, gescheiden door komma's. " +
                    "Deepgram herkent ze dan beter. Maximaal 400 tekens; pas ze per vak aan.",
            ) {
                var vocab by remember { mutableStateOf(s.vocabulary) }
                IosTextField(
                    value = vocab,
                    onChange = { if (it.length <= 400) { vocab = it; Settings.update { st -> st.copy(vocabulary = it) } } },
                    placeholder = "Bijv. prof. Jansen, utilitarisme, Lindenbaum/Cohen",
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            Group(header = "Algemeen") {
                SwitchRow("Automatisch transcriberen na opname", s.autoTranscribe) {
                    Settings.update { st -> st.copy(autoTranscribe = it) }
                }
                Separator()
                SwitchRow("Tijdstempels in export", s.exportTimestamps) {
                    Settings.update { st -> st.copy(exportTimestamps = it) }
                }
            }

            BatteryGroup()

            Group(
                header = "Online of offline",
                footer = "Bij \"Online, anders offline\" valt de app terug op het offline model als er geen internet is " +
                    "of ${s.cloud.label} niet werkt, bijvoorbeeld als het tegoed op is.",
            ) {
                EngineMode.entries.forEachIndexed { i, m ->
                    if (i > 0) Separator()
                    CheckRow(m.label, s.engine == m) { Settings.update { it.copy(engine = m) } }
                }
            }

            Group(
                header = "Offline spraakmodel (optioneel)",
                footer = "Reserve als er geen internet is of ${s.cloud.label} niet werkt. Draait op de tablet zelf: een " +
                    "college van 90 minuten kan ongeveer net zo lang duren. Houd de tablet aan de lader en download " +
                    "via wifi; stiltedetectie (1 MB) wordt automatisch meegeladen.",
            ) {
                ModelManager.models.forEachIndexed { i, m ->
                    if (i > 0) Separator()
                    ModelRow(m, selected = s.offlineModel == m.id, installed = m.id in installed, download = downloads[m.id]?.let {
                        if (it.failed) null else it.bytes to it.total
                    }, failed = downloads[m.id]?.failed == true, onSelect = { Settings.update { it.copy(offlineModel = m.id) } },
                        onDownload = {
                            ModelManager.download(ctx, m)
                            if (!ModelManager.isInstalled(ctx, ModelManager.vad.id) && downloads[ModelManager.vad.id] == null) {
                                ModelManager.download(ctx, ModelManager.vad)
                            }
                            Settings.update { it.copy(offlineModel = m.id) }
                        },
                        onCancel = { ModelManager.cancel(ctx, m) },
                        onDelete = { ModelManager.delete(ctx, m) },
                    )
                }
            }

            val info = remember { runCatching { WhisperLib.variant }.getOrDefault("niet geladen") }
            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
            Text(
                "CollegeWhispr ${version ?: ""}\nGebaseerd op OpenWhispr en whisper.cpp · engine: $info",
                style = IosType.footnote, color = ios.secondaryLabel, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * One online service: "use this one" with a checkmark, the key field (hidden by default),
 * a link to create a key and a key test whose result shows green or red.
 */
@Composable
private fun ProviderGroup(
    p: CloudProvider,
    s: SettingsState,
    header: String,
    subtitle: String,
    footer: String,
    check: (String) -> String?,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val ios = LocalIos.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(s.keyFor(p)) }
    var show by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Group(header = header, footer = footer) {
        CheckRow("Gebruik ${p.label}", s.cloud == p, subtitle) { Settings.update { it.copy(cloud = p) } }
        Separator()
        IosRow {
            IosTextField(
                value = key,
                onChange = { key = it.trim(); Settings.update { st -> st.withKey(p, key) }; result = null },
                placeholder = "API-sleutel",
                modifier = Modifier.weight(1f),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Icon(
                if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Verbergen" else "Tonen",
                tint = ios.tertiaryLabel,
                modifier = Modifier.padding(start = 8.dp).size(22.dp).clip(CircleShape).clickable { show = !show },
            )
        }
        Separator()
        ActionRow("Sleutel aanmaken", icon = Icons.Filled.OpenInNew, onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keysUrl))) }
        })
        Separator()
        ActionRow(if (busy) "Bezig met controleren…" else "Sleutel testen", enabled = key.isNotBlank() && !busy, onClick = {
            busy = true
            result = null
            scope.launch {
                result = withContext(Dispatchers.IO) { check(key) } ?: "Sleutel werkt"
                busy = false
            }
        })
        result?.let { r ->
            val ok = r.startsWith("Sleutel werkt")
            Separator()
            IosRow {
                Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, tint = if (ok) ios.green else ios.red,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(r, style = IosType.subhead, color = ios.label)
            }
        }
        extra()
    }
}

@Composable
private fun ModelRow(
    m: ModelInfo,
    selected: Boolean,
    installed: Boolean,
    download: Pair<Long, Long>?,
    failed: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val ios = LocalIos.current
    IosRow(onSelect, minHeight = 64.dp) {
        Column(Modifier.weight(1f)) {
            Text(m.label, style = IosType.body, color = ios.label)
            Text("${m.description} (${m.approxMb} MB)", style = IosType.footnote, color = ios.secondaryLabel)
            when {
                download != null -> {
                    Spacer(Modifier.height(8.dp))
                    ThinProgress(if (download.second > 0) download.first.toFloat() / download.second else 0f)
                    Text("${download.first / 1_000_000} van ${download.second / 1_000_000} MB",
                        style = IosType.caption, color = ios.secondaryLabel, modifier = Modifier.padding(top = 4.dp))
                }
                failed -> Text("Download mislukt", style = IosType.footnote, color = ios.red)
                installed -> Text("Gedownload", style = IosType.footnote, color = ios.green)
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            download != null -> PillButton("Annuleren", onCancel, filled = false, color = ios.red)
            installed -> Column(horizontalAlignment = Alignment.End) {
                if (selected) Icon(Icons.Filled.CheckCircle, "Gekozen", tint = ios.accent)
                Text("Verwijderen", style = IosType.footnote, color = ios.red,
                    modifier = Modifier.padding(top = 4.dp).clickable(onClick = onDelete))
            }
            else -> PillButton("Downloaden", onDownload, filled = false)
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BatteryGroup() {
    val ios = LocalIos.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val pm = ctx.getSystemService(PowerManager::class.java)
    val exempt = remember(tick) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    Group(
        header = "Batterij",
        footer = if (exempt) "Goed: Android breekt lange opnames niet af."
        else "Zet batterijoptimalisatie uit, zodat Android lange opnames niet afbreekt. Sta op Lenovo-tablets ook " +
            "achtergrondactiviteit en automatisch starten toe in de app-instellingen.",
    ) {
        IosRow {
            Text("Batterijoptimalisatie", style = IosType.body, color = ios.label, modifier = Modifier.weight(1f))
            Text(if (exempt) "Uit" else "Aan", style = IosType.body, color = if (exempt) ios.green else ios.orange)
        }
        if (!exempt) {
            Separator()
            ActionRow("Batterijoptimalisatie uitzetten", onClick = {
                runCatching {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + ctx.packageName)))
                }
            })
        }
        Separator()
        ActionRow("App-instellingen openen", icon = Icons.Filled.OpenInNew, onClick = {
            runCatching {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            }
        })
    }
}
