package com.kosub.player.asr

import com.kosub.player.BuildConfig
import com.kosub.player.core.Asr
import com.kosub.player.core.AsrResult
import com.kosub.player.core.Segment
import com.kosub.player.core.Word
import org.json.JSONObject

/** whisper.cpp JNI 바인딩 (app/src/main/cpp/whisper_jni.cpp). */
object WhisperNative {
    val available: Boolean by lazy {
        BuildConfig.HAS_NATIVE && try {
            System.loadLibrary("kosubwhisper")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    @JvmStatic external fun initContext(modelPath: String): Long
    @JvmStatic external fun freeContext(handle: Long)
    @JvmStatic external fun abort(handle: Long)
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, language: String, threads: Int): ByteArray?
    @JvmStatic external fun systemInfo(): String
}

/** whisper.cpp 음성 인식기. 한 번에 한 구간만 처리한다. */
class WhisperAsr(modelPath: String, override val modelName: String, private val threads: Int) : Asr {
    private var handle: Long

    init {
        if (!WhisperNative.available) throw IllegalStateException("음성 인식 엔진(whisper.cpp)이 이 빌드에 포함되어 있지 않습니다.")
        handle = WhisperNative.initContext(modelPath)
        if (handle == 0L) throw IllegalStateException("음성 인식 모델을 불러오지 못했습니다. 모델 파일이 손상되었을 수 있습니다.")
    }

    @Synchronized
    override fun transcribe(pcm: FloatArray, language: String?): AsrResult {
        val bytes = WhisperNative.transcribe(handle, pcm, language ?: "auto", threads)
            ?: return AsrResult(emptyList(), language, 0.0, aborted = true)
        return parse(String(bytes, Charsets.UTF_8), language)
    }

    override fun abort() {
        val h = handle
        if (h != 0L) WhisperNative.abort(h)
    }

    @Synchronized
    fun close() {
        if (handle != 0L) {
            WhisperNative.freeContext(handle)
            handle = 0L
        }
    }

    companion object {
        /** JNI가 돌려준 JSON → 세그먼트 (시간은 10ms 단위 → 초). */
        fun parse(json: String, requestedLanguage: String?): AsrResult {
            val o = JSONObject(json)
            if (o.optBoolean("aborted", false)) return AsrResult(emptyList(), requestedLanguage, 0.0, aborted = true)
            val segs = ArrayList<Segment>()
            val arr = o.optJSONArray("segments")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val words = ArrayList<Word>()
                    s.optJSONArray("words")?.let { wa ->
                        for (j in 0 until wa.length()) {
                            val w = wa.getJSONArray(j)
                            words.add(Word(w.getLong(0) / 100.0, w.getLong(1) / 100.0, w.getString(2)))
                        }
                    }
                    segs.add(
                        Segment(
                            start = s.getLong("t0") / 100.0,
                            end = s.getLong("t1") / 100.0,
                            text = s.optString("text", ""),
                            words = words.ifEmpty { null },
                            avgLogprob = s.optDouble("logprob", 0.0),
                        )
                    )
                }
            }
            val lang = o.optString("lang", "").ifEmpty { requestedLanguage }
            return AsrResult(segs, lang, o.optDouble("lang_prob", if (requestedLanguage != null) 1.0 else 0.0))
        }
    }
}
