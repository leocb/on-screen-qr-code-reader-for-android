package com.leocb.screenqr

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Which screen-reading engine the user prefers. */
enum class CaptureEngine {
    /** Use the accessibility service when it is available, otherwise the projection dialog. */
    AUTO,

    /** Only use the accessibility service (no system dialog, no screen dimming). */
    ACCESSIBILITY,

    /** Only use MediaProjection (the "Start recording" system dialog). */
    PROJECTION,
}

data class Settings(
    val engine: CaptureEngine = CaptureEngine.AUTO,
    val autoCopy: Boolean = false,
    /** Keep one MediaProjection session alive so scans after the first need no dialog. */
    val keepSession: Boolean = true,
)

data class ScanRecord(val raw: String, val time: Long)

object Prefs {

    private const val FILE = "screenqr"
    private const val KEY_ENGINE = "engine"
    private const val KEY_AUTO_COPY = "auto_copy"
    private const val KEY_KEEP_SESSION = "keep_session"
    private const val KEY_HISTORY = "history"
    private const val KEY_LANGUAGE = "language"

    const val MAX_HISTORY = 20

    private lateinit var prefs: SharedPreferences

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _history = MutableStateFlow<List<ScanRecord>>(emptyList())
    val history: StateFlow<List<ScanRecord>> = _history.asStateFlow()

    /** "system", "en" or "pt-BR"; only used directly on API < 33 (see AppLocales). */
    var language: String = "system"
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _settings.value = Settings(
            engine = runCatching {
                CaptureEngine.valueOf(prefs.getString(KEY_ENGINE, CaptureEngine.AUTO.name)!!)
            }.getOrDefault(CaptureEngine.AUTO),
            autoCopy = prefs.getBoolean(KEY_AUTO_COPY, false),
            keepSession = prefs.getBoolean(KEY_KEEP_SESSION, true),
        )
        language = prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        _history.value = readHistory()
    }

    fun setLanguage(tag: String) {
        language = tag
        prefs.edit().putString(KEY_LANGUAGE, tag).apply()
    }

    fun setEngine(engine: CaptureEngine) {
        prefs.edit().putString(KEY_ENGINE, engine.name).apply()
        _settings.value = _settings.value.copy(engine = engine)
    }

    fun setAutoCopy(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_COPY, enabled).apply()
        _settings.value = _settings.value.copy(autoCopy = enabled)
    }

    fun setKeepSession(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SESSION, enabled).apply()
        _settings.value = _settings.value.copy(keepSession = enabled)
    }

    /** Adds a scan to the history, newest first, skipping an immediate duplicate. */
    fun addToHistory(raw: String) {
        if (raw.isBlank()) return
        val now = System.currentTimeMillis()
        val list = _history.value.toMutableList()
        if (list.firstOrNull()?.raw == raw) {
            list[0] = ScanRecord(raw, now)
        } else {
            list.add(0, ScanRecord(raw, now))
        }
        while (list.size > MAX_HISTORY) list.removeAt(list.lastIndex)
        _history.value = list
        writeHistory(list)
    }

    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun readHistory(): List<ScanRecord> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val raw = obj.optString("raw").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ScanRecord(raw, obj.optLong("time", 0L))
            }
        }.getOrDefault(emptyList()).take(MAX_HISTORY)
    }

    private fun writeHistory(list: List<ScanRecord>) {
        val array = JSONArray()
        list.forEach { record ->
            array.put(
                JSONObject()
                    .put("raw", record.raw)
                    .put("time", record.time),
            )
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }
}
