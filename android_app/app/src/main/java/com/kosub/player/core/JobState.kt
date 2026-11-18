package com.kosub.player.core

import org.json.JSONArray
import org.json.JSONObject

/** 동영상별 자막 작업 상태 (이어서 만들기용, JSON으로 저장). */
class JobState(
    var videoName: String,
    val fingerprint: String,
) {
    var duration: Double? = null
    var language: String? = null
    var languageProb: Double = 0.0
    var languageLocked: Boolean = false
    var model: String = ""
    var translator: String = ""
    val units: LinkedHashMap<Int, SubUnit> = LinkedHashMap()
    var covered: IntervalSet = IntervalSet()
    var nextId: Int = 1
    var transcribed: Boolean = false
    var complete: Boolean = false
    var outputs: List<String> = emptyList()

    fun untranslated(): List<SubUnit> = units.values.filter { it.ko.isNullOrBlank() }

    fun progress(): Double {
        val d = duration ?: return if (transcribed) 1.0 else 0.0
        return if (d <= 0) 0.0 else (covered.total() / d).coerceIn(0.0, 1.0)
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("version", 1)
        o.put("video", videoName)
        o.put("fingerprint", fingerprint)
        o.put("duration", duration ?: JSONObject.NULL)
        o.put("language", language ?: JSONObject.NULL)
        o.put("language_prob", languageProb)
        o.put("language_locked", languageLocked)
        o.put("model", model)
        o.put("translator", translator)
        o.put("next_id", nextId)
        o.put("transcribed", transcribed)
        o.put("complete", complete)
        o.put("outputs", JSONArray(outputs))
        o.put("covered", JSONArray().apply { covered.toList().forEach { put(JSONArray(listOf(it.first, it.second))) } })
        val arr = JSONArray()
        for (u in units.values.sortedWith(compareBy({ it.start }, { it.id }))) arr.put(unitToJson(u))
        o.put("units", arr)
        return o.toString()
    }

    companion object {
        fun unitToJson(u: SubUnit): JSONObject = JSONObject().apply {
            put("id", u.id)
            put("start", u.start)
            put("end", u.end)
            put("text", u.text)
            put("ko", u.ko ?: JSONObject.NULL)
            u.parts?.let { parts ->
                put("parts", JSONArray().apply {
                    parts.forEach { p -> put(JSONObject().put("s", p.start).put("e", p.end).put("t", p.text)) }
                })
            }
            if (u.edited) put("edited", true)
        }

        fun unitFromJson(o: JSONObject): SubUnit {
            val parts = o.optJSONArray("parts")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    SubUnit.Part(p.getDouble("s"), p.getDouble("e"), p.getString("t"))
                }
            }
            return SubUnit(
                id = o.getInt("id"),
                start = o.getDouble("start"),
                end = o.getDouble("end"),
                text = o.optString("text", ""),
                ko = if (o.isNull("ko")) null else o.optString("ko"),
                parts = parts?.takeIf { it.isNotEmpty() },
                edited = o.optBoolean("edited", false),
            )
        }

        fun fromJson(text: String): JobState {
            val o = JSONObject(text)
            val st = JobState(o.optString("video", ""), o.optString("fingerprint", ""))
            st.duration = if (o.isNull("duration")) null else o.optDouble("duration")
            st.language = if (o.isNull("language")) null else o.optString("language")
            st.languageProb = o.optDouble("language_prob", 0.0)
            st.languageLocked = o.optBoolean("language_locked", false)
            st.model = o.optString("model", "")
            st.translator = o.optString("translator", "")
            st.transcribed = o.optBoolean("transcribed", false)
            st.complete = o.optBoolean("complete", false)
            o.optJSONArray("outputs")?.let { a -> st.outputs = (0 until a.length()).map { a.getString(it) } }
            o.optJSONArray("covered")?.let { a ->
                for (i in 0 until a.length()) {
                    val pair = a.getJSONArray(i)
                    st.covered.add(pair.getDouble(0), pair.getDouble(1))
                }
            }
            o.optJSONArray("units")?.let { a ->
                for (i in 0 until a.length()) {
                    val u = unitFromJson(a.getJSONObject(i))
                    st.units[u.id] = u
                }
            }
            st.nextId = maxOf(o.optInt("next_id", 1), (st.units.keys.maxOrNull() ?: 0) + 1)
            return st
        }
    }
}
