package nl.collegewhispr.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class EngineMode(val label: String) {
    AUTO("Online, anders offline"),
    ONLINE_ONLY("Alleen online"),
    OFFLINE_ONLY("Alleen offline");
}

/** Online transcription services, recommended first; [keysUrl] is where a user creates an API key. */
enum class CloudProvider(val label: String, val keysUrl: String) {
    DEEPGRAM("Deepgram", "https://console.deepgram.com/"),
    GROQ("Groq", "https://console.groq.com/keys"),
    VOXTRAL("Voxtral", "https://console.mistral.ai/api-keys");
}

data class SettingsState(
    val groqKey: String = "",
    val groqModel: String = "whisper-large-v3",
    val deepgramKey: String = "",
    val mistralKey: String = "",
    val cloud: CloudProvider = CloudProvider.DEEPGRAM,
    val engine: EngineMode = EngineMode.AUTO,
    val language: String = "nl",
    val offlineModel: String = "large-v3-turbo-q5_0",
    val vocabulary: String = "",
    val autoTranscribe: Boolean = true,
    val exportTimestamps: Boolean = true,
) {
    fun keyFor(p: CloudProvider) = when (p) {
        CloudProvider.GROQ -> groqKey
        CloudProvider.DEEPGRAM -> deepgramKey
        CloudProvider.VOXTRAL -> mistralKey
    }

    fun withKey(p: CloudProvider, key: String) = when (p) {
        CloudProvider.GROQ -> copy(groqKey = key)
        CloudProvider.DEEPGRAM -> copy(deepgramKey = key)
        CloudProvider.VOXTRAL -> copy(mistralKey = key)
    }

    /** [language] for Whisper-based engines, which know "nl" but not regional codes like "nl-BE". */
    val whisperLanguage: String get() = language.substringBefore('-')
}

object Settings {
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> get() = _state
    val current: SettingsState get() = _state.value

    const val DEFAULT_PROMPT =
        "Dit is een hoorcollege aan een Nederlandse universiteit, met correcte interpunctie en hoofdletters."

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _state.value = SettingsState(
            groqKey = prefs.getString("groqKey", "") ?: "",
            groqModel = prefs.getString("groqModel", "whisper-large-v3") ?: "whisper-large-v3",
            deepgramKey = prefs.getString("deepgramKey", "") ?: "",
            mistralKey = prefs.getString("mistralKey", "") ?: "",
            cloud = cloudFromPrefs(),
            engine = engineFromPrefs(prefs.getString("engine", "AUTO")),
            language = prefs.getString("language", "nl") ?: "nl",
            offlineModel = prefs.getString("offlineModel", "large-v3-turbo-q5_0") ?: "large-v3-turbo-q5_0",
            vocabulary = prefs.getString("vocabulary", "") ?: "",
            autoTranscribe = prefs.getBoolean("autoTranscribe", true),
            exportTimestamps = prefs.getBoolean("exportTimestamps", true),
        )
    }

    // Until 1.2 Groq was the default. Switch once to Deepgram, unless Groq was actually set up.
    private fun cloudFromPrefs(): CloudProvider {
        val stored = prefs.getString("cloud", null)
        if (!prefs.getBoolean("deepgramDefault", false)) {
            prefs.edit().putBoolean("deepgramDefault", true).apply()
            if (stored == null || (stored == "GROQ" && prefs.getString("groqKey", "").isNullOrBlank())) {
                return CloudProvider.DEEPGRAM
            }
        }
        return runCatching { CloudProvider.valueOf(stored!!) }.getOrDefault(CloudProvider.DEEPGRAM)
    }

    // 1.0 stored "GROQ_ONLY" before there was a choice of online service.
    private fun engineFromPrefs(v: String?) =
        if (v == "GROQ_ONLY") EngineMode.ONLINE_ONLY
        else runCatching { EngineMode.valueOf(v!!) }.getOrDefault(EngineMode.AUTO)

    fun update(block: (SettingsState) -> SettingsState) {
        val s = block(_state.value)
        _state.value = s
        prefs.edit()
            .putString("groqKey", s.groqKey.trim())
            .putString("groqModel", s.groqModel)
            .putString("deepgramKey", s.deepgramKey.trim())
            .putString("mistralKey", s.mistralKey.trim())
            .putString("cloud", s.cloud.name)
            .putString("engine", s.engine.name)
            .putString("language", s.language)
            .putString("offlineModel", s.offlineModel)
            .putString("vocabulary", s.vocabulary)
            .putBoolean("autoTranscribe", s.autoTranscribe)
            .putBoolean("exportTimestamps", s.exportTimestamps)
            .apply()
    }

    /** Prompt sent to Whisper: a style hint plus the user's own terms. */
    fun basePrompt(): String {
        val s = current
        val vocab = s.vocabulary.trim()
        val style = if (s.whisperLanguage == "nl") DEFAULT_PROMPT else ""
        return listOf(style, vocab).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** The user's terms as a list, for services that take separate keyterms instead of a prompt. */
    fun terms(): List<String> = splitTerms(current.vocabulary)

    fun splitTerms(vocabulary: String): List<String> =
        vocabulary.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(100)
}
