package com.kosub.player.core

/** 16 kHz 모노 오디오를 구간 단위로 읽어 오는 원본. */
interface AudioSource {
    val durationSec: Double?

    /** [start, end) 초 구간. 파일 끝을 넘으면 그만큼 짧아진다. */
    fun read(start: Double, end: Double): FloatArray
}

data class AsrResult(
    val segments: List<Segment>,
    val language: String?,
    val languageProb: Double,
    val aborted: Boolean = false,
)

/** 음성 인식기 (whisper.cpp). 시간은 넘겨준 오디오의 시작 기준. */
interface Asr {
    val modelName: String
    fun transcribe(pcm: FloatArray, language: String?): AsrResult
    fun abort()
}

class TranslatorException(message: String, val fatal: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

interface Translator {
    val name: String
    val label: String
    val batchSize: Int

    /** texts를 한국어로 번역한다. 실패한 항목은 null. 일시적 오류는 TranslatorException(fatal=false). */
    fun translate(texts: List<String>, srcLang: String, context: List<Pair<String, String>>): List<String?>
}

/** 자막 파일을 저장할 곳 (사용자가 고른 폴더 또는 다운로드/KoSubPlayer). */
interface SubtitleSink {
    /** 저장한 위치(사람이 읽을 수 있는 경로)를 돌려준다. 실패하면 예외. */
    fun write(fileName: String, content: String): String
}

interface StateStore {
    fun load(): JobState?
    fun save(state: JobState)
}

/** 작업 진행 상황을 화면에 알린다 (작업 스레드에서 호출됨). */
interface EngineListener {
    fun onState(state: JobState) {}
    fun onUnits(units: List<SubUnit>) {}
    fun onCoverage(intervals: List<Pair<Double, Double>>, active: Pair<Double, Double>?, duration: Double?, speed: Double?) {}
    fun onStatus(phase: String, text: String) {}
    fun onLanguage(code: String, prob: Double) {}
    fun onSaved(files: List<String>) {}
    fun onDone(complete: Boolean, message: String) {}
    fun onError(text: String, fatal: Boolean) {}
}

data class EngineOptions(
    val language: String = "auto",
    val translatorKind: String = "google",
    val saveSource: Boolean = true,
    val saveBilingual: Boolean = false,
)
