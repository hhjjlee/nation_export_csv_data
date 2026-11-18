package com.kosub.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager
import com.kosub.player.asr.ModelManager
import com.kosub.player.core.EngineOptions
import org.json.JSONArray
import org.json.JSONObject

/** 설정 값 (설정 화면의 키와 같다). */
class Settings(context: Context) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    val model: String get() = prefs.getString(KEY_MODEL, ModelManager.DEFAULT) ?: ModelManager.DEFAULT
    val language: String get() = prefs.getString(KEY_LANGUAGE, "auto") ?: "auto"
    val translator: String get() = prefs.getString(KEY_TRANSLATOR, "google") ?: "google"
    val claudeKey: String get() = prefs.getString(KEY_CLAUDE_KEY, "") ?: ""
    val claudeModel: String get() = prefs.getString(KEY_CLAUDE_MODEL, "claude-opus-5-5") ?: "claude-opus-5-5"
    val claudeEffort: String get() = prefs.getString(KEY_CLAUDE_EFFORT, "low") ?: "low"
    val saveSource: Boolean get() = prefs.getBoolean(KEY_SAVE_SOURCE, true)
    val saveBilingual: Boolean get() = prefs.getBoolean(KEY_SAVE_BILINGUAL, false)
    val autoGenerate: Boolean get() = prefs.getBoolean(KEY_AUTO, true)
    val showSource: Boolean get() = prefs.getBoolean(KEY_SHOW_SOURCE, false)
    val subtitleSize: Int get() = prefs.getInt(KEY_SUB_SIZE, 22)
    val subtitleBackground: Boolean get() = prefs.getBoolean(KEY_SUB_BG, false)
    val saveFolder: Uri?
        get() = prefs.getString(KEY_SAVE_FOLDER, null)?.takeIf { it.isNotEmpty() }?.let(Uri::parse)

    fun setSaveFolder(uri: Uri?) = prefs.edit().putString(KEY_SAVE_FOLDER, uri?.toString() ?: "").apply()
    fun setShowSource(on: Boolean) = prefs.edit().putBoolean(KEY_SHOW_SOURCE, on).apply()

    fun engineOptions() = EngineOptions(
        language = language,
        translatorKind = translator,
        saveSource = saveSource,
        saveBilingual = saveBilingual,
    )

    // -- 최근 파일 / 이어보기 ------------------------------------------------
    data class Recent(val uri: String, val name: String)

    fun recent(): List<Recent> = try {
        val arr = JSONArray(prefs.getString(KEY_RECENT, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Recent(o.getString("uri"), o.getString("name")) } }
    } catch (_: Exception) {
        emptyList()
    }

    fun addRecent(uri: Uri, name: String) {
        val items = recent().filter { it.uri != uri.toString() }.toMutableList()
        items.add(0, Recent(uri.toString(), name))
        val arr = JSONArray()
        items.take(15).forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        prefs.edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    fun position(key: String): Long = prefs.getLong("pos_$key", 0L)

    fun savePosition(key: String, ms: Long, durationMs: Long) {
        val e = prefs.edit()
        if (ms > 10_000 && (durationMs <= 0 || ms < durationMs - 15_000)) e.putLong("pos_$key", ms) else e.remove("pos_$key")
        e.apply()
    }

    companion object {
        const val KEY_MODEL = "model"
        const val KEY_LANGUAGE = "language"
        const val KEY_TRANSLATOR = "translator"
        const val KEY_CLAUDE_KEY = "claude_api_key"
        const val KEY_CLAUDE_MODEL = "claude_model"
        const val KEY_CLAUDE_EFFORT = "claude_effort"
        const val KEY_SAVE_SOURCE = "save_source"
        const val KEY_SAVE_BILINGUAL = "save_bilingual"
        const val KEY_AUTO = "auto_generate"
        const val KEY_SHOW_SOURCE = "show_source"
        const val KEY_SUB_SIZE = "subtitle_size"
        const val KEY_SUB_BG = "subtitle_background"
        const val KEY_SAVE_FOLDER = "save_folder"
        const val KEY_RECENT = "recent"
    }
}
