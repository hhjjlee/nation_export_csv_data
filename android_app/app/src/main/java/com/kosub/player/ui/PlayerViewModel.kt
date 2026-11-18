package com.kosub.player.ui

import android.app.Application
import android.net.Uri
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kosub.player.AsrHolder
import com.kosub.player.Settings
import com.kosub.player.asr.ModelManager
import com.kosub.player.core.ClaudeTranslator
import com.kosub.player.core.Cue
import com.kosub.player.core.EngineListener
import com.kosub.player.core.GoogleTranslator
import com.kosub.player.core.JobState
import com.kosub.player.core.Languages
import com.kosub.player.core.SubUnit
import com.kosub.player.core.SubtitleSink
import com.kosub.player.core.Subtitles
import com.kosub.player.core.Translator
import com.kosub.player.core.TranslatorException
import com.kosub.player.engine.SubtitleEngine
import com.kosub.player.media.MediaAudioSource
import com.kosub.player.storage.DownloadsSink
import com.kosub.player.storage.FileStateStore
import com.kosub.player.storage.MediaInfo
import com.kosub.player.storage.TreeSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 재생 화면의 자막 작업 상태. 화면을 돌려도 작업이 이어진다. */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    data class Ui(
        val status: String = "",
        val progress: Double = 0.0,
        val downloadFraction: Double? = null,
        val language: String? = null,
        val coverage: List<Pair<Double, Double>> = emptyList(),
        val active: Pair<Double, Double>? = null,
        val duration: Double? = null,
        val speed: Double? = null,
        val running: Boolean = false,
        val savedTo: List<String> = emptyList(),
        val complete: Boolean = false,
    )

    private val settings = Settings(app)
    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues = _cues.asStateFlow()
    private val _units = MutableStateFlow<List<SubUnit>>(emptyList())
    val units = _units.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private val _seekRequests = MutableSharedFlow<Double>(extraBufferCapacity = 4)
    val seekRequests = _seekRequests.asSharedFlow()

    private val unitMap = ConcurrentHashMap<Int, SubUnit>()
    var uri: Uri? = null
        private set
    var name: String = ""
        private set
    private var fingerprint: String? = null
    private var engine: SubtitleEngine? = null
    private var worker: Thread? = null
    @Volatile private var language: String? = null
    @Volatile private var lastPosition = 0.0

    fun needsModelDownload(): ModelManager.Model? {
        val m = ModelManager.find(settings.model)
        return if (ModelManager.isDownloaded(getApplication(), m)) null else m
    }

    /** 동영상을 연다. 저장된 자막이 있으면 바로 보여 주고, 필요하면 자막 만들기를 시작한다. */
    fun open(uri: Uri, startSeconds: Double, autoStart: Boolean) {
        if (this.uri == uri) return
        stopJob(silent = true)
        this.uri = uri
        unitMap.clear()
        publishUnits()
        _ui.value = Ui(status = "자막 준비 중…")
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val info = withContext(Dispatchers.IO) {
                runCatching { MediaInfo.displayName(ctx, uri) to MediaInfo.fingerprint(ctx, uri) }
            }
            val (displayName, fp) = info.getOrElse {
                _ui.update { it.copy(status = "파일을 열 수 없습니다: ${info.exceptionOrNull()?.message}") }
                return@launch
            }
            name = displayName
            fingerprint = fp
            settings.addRecent(uri, displayName)
            val state = withContext(Dispatchers.IO) { FileStateStore.forFingerprint(ctx, fp).load() }
            if (state != null) applyState(state)
            when {
                state?.complete == true -> _ui.update {
                    it.copy(status = "저장해 둔 한국어 자막을 불러왔습니다 (${state.units.size}개).", progress = 1.0, complete = true)
                }
                autoStart -> start(startSeconds)
                else -> _ui.update { it.copy(status = "메뉴(⋮) → 자막 만들기를 누르면 한국어 자막을 만듭니다.") }
            }
        }
    }

    private fun applyState(state: JobState) {
        unitMap.clear()
        state.units.values.forEach { unitMap[it.id] = it.copy() }
        language = state.language
        _ui.update {
            it.copy(
                coverage = state.covered.toList(),
                duration = state.duration,
                language = state.language,
                progress = state.progress(),
                savedTo = state.outputs,
                complete = state.complete,
            )
        }
        publishUnits()
    }

    fun start(positionSeconds: Double = lastPosition, retranslate: Boolean = false, fresh: Boolean = false) {
        val uri = uri ?: return
        val fp = fingerprint ?: return
        stopJob(silent = true)
        val ctx = getApplication<Application>()
        val store = FileStateStore.forFingerprint(ctx, fp)
        if (fresh) {
            ctx.filesDir.resolve("jobs/$fp.json").delete()
            unitMap.clear()
            publishUnits()
            _ui.value = Ui()
        }
        val state = store.load() ?: JobState(name, fp)
        state.videoName = name
        val audio = try {
            MediaAudioSource(ctx, uri)
        } catch (e: Exception) {
            _ui.update { it.copy(status = e.message ?: "오디오를 읽을 수 없습니다", running = false) }
            return
        }
        val sink: SubtitleSink = settings.saveFolder?.let { TreeSink(ctx, it) } ?: DownloadsSink(ctx)
        lateinit var eng: SubtitleEngine
        eng = SubtitleEngine(
            state = state,
            audio = audio,
            asrProvider = {
                AsrHolder.get(ctx, settings.model, { eng.isCancelled }, { s -> setStatus(s) }) { done, total ->
                    _ui.update { it.copy(downloadFraction = if (total > 0) done.toDouble() / total else null) }
                }.also { _ui.update { it.copy(downloadFraction = null) } }
            },
            translatorProvider = { makeTranslator() },
            sink = sink,
            store = store,
            options = settings.engineOptions(),
            listener = listener,
            retranslate = retranslate,
        )
        engine = eng
        _ui.update { it.copy(running = true, complete = false, status = "자막 만들 준비 중…") }
        worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            eng.run(positionSeconds)
            if (engine === eng) _ui.update { it.copy(running = false, active = null) }
        }, "subtitle-engine").apply { start() }
    }

    private fun makeTranslator(): Translator? = when (settings.translator) {
        "none" -> null
        "claude" -> {
            val key = settings.claudeKey
            if (key.isBlank()) throw TranslatorException("Claude 번역을 쓰려면 설정에서 API 키를 입력하세요.", fatal = true)
            ClaudeTranslator(key, settings.claudeModel, settings.claudeEffort)
        }
        else -> GoogleTranslator()
    }

    fun stopJob(silent: Boolean = false) {
        engine?.cancel()
        engine = null
        if (!silent) _ui.update { it.copy(running = false, active = null, status = "자막 만들기를 멈췄습니다. 지금까지 만든 자막은 저장되었습니다.") }
    }

    fun setPosition(seconds: Double) {
        lastPosition = seconds
        engine?.setPosition(seconds)
    }

    fun requestSeek(seconds: Double) {
        _seekRequests.tryEmit(seconds)
    }

    fun edit(unitId: Int, text: String) {
        val eng = engine
        if (eng != null) {
            eng.edit(unitId, text)
            return
        }
        // 작업이 끝난 뒤에는 저장된 기록을 직접 고쳐서 다시 저장한다
        val fp = fingerprint ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val store = FileStateStore.forFingerprint(ctx, fp)
            val state = store.load() ?: return@launch
            val u = state.units[unitId] ?: return@launch
            val clean = text.trim()
            u.ko = clean.ifEmpty { null }
            u.edited = clean.isNotEmpty()
            val sink: SubtitleSink = settings.saveFolder?.let { TreeSink(ctx, it) } ?: DownloadsSink(ctx)
            val tmpEngine = SubtitleEngine(state, NoAudio, { throw IllegalStateException() }, { null }, sink, store,
                settings.engineOptions(), object : EngineListener {})
            tmpEngine.edit(unitId, clean) // 파일 다시 쓰기
            unitMap[unitId] = u.copy()
            publishUnits()
            _messages.tryEmit("수정한 자막을 저장했습니다.")
        }
    }

    private object NoAudio : com.kosub.player.core.AudioSource {
        override val durationSec: Double? = null
        override fun read(start: Double, end: Double) = FloatArray(0)
    }

    private fun setStatus(text: String) = _ui.update { it.copy(status = text) }

    private fun displayMode(): String =
        if (settings.translator == "none" && (language ?: "") != "ko") "src" else "ko"

    private fun publishUnits() {
        val list = unitMap.values.sortedWith(compareBy({ it.start }, { it.id }))
        _units.value = list
        _cues.value = Subtitles.buildCues(list, displayMode())
    }

    private val listener = object : EngineListener {
        override fun onState(state: JobState) = applyState(state)

        override fun onUnits(units: List<SubUnit>) {
            units.forEach { unitMap[it.id] = it }
            publishUnits()
        }

        override fun onCoverage(intervals: List<Pair<Double, Double>>, active: Pair<Double, Double>?, duration: Double?, speed: Double?) {
            _ui.update {
                val d = duration ?: it.duration
                val covered = intervals.sumOf { iv -> iv.second - iv.first }
                it.copy(
                    coverage = intervals, active = active, duration = d, speed = speed ?: it.speed,
                    progress = if (d != null && d > 0) (covered / d).coerceIn(0.0, 1.0) else it.progress,
                    status = statusLine(it.copy(progress = if (d != null && d > 0) covered / d else it.progress, speed = speed ?: it.speed)),
                )
            }
        }

        override fun onStatus(phase: String, text: String) {
            if (phase == "transcribing" || phase == "translating") {
                _ui.update { it.copy(status = statusLine(it)) }
            } else setStatus(text)
        }

        override fun onLanguage(code: String, prob: Double) {
            language = code
            _ui.update { it.copy(language = code) }
            publishUnits()
        }

        override fun onSaved(files: List<String>) = _ui.update { it.copy(savedTo = files) }

        override fun onDone(complete: Boolean, message: String) {
            _ui.update {
                it.copy(running = false, active = null, complete = complete, status = message,
                    progress = if (complete) 1.0 else it.progress)
            }
        }

        override fun onError(text: String, fatal: Boolean) {
            if (fatal) _ui.update { it.copy(running = false, status = "오류: $text") }
            _messages.tryEmit(text)
        }
    }

    private fun statusLine(ui: Ui): String {
        val parts = ArrayList<String>()
        parts.add("자막 만드는 중 ${(ui.progress * 100).toInt()}%")
        ui.speed?.let { parts.add(String.format(java.util.Locale.ROOT, "%.1f배속", it)) }
        val waiting = if (displayMode() == "ko" && settings.translator != "none") unitMap.values.count { it.ko.isNullOrBlank() } else 0
        if (waiting > 0) parts.add("번역 대기 $waiting")
        ui.language?.let { parts.add(Languages.name(it)) }
        parts.add("자동 저장")
        return parts.joinToString(" · ")
    }

    override fun onCleared() {
        engine?.cancel()
        super.onCleared()
    }
}
