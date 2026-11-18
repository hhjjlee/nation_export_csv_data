package com.kosub.player.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.kosub.player.Settings
import com.kosub.player.core.Cue
import com.kosub.player.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch

/** 동영상 재생 + 자막 표시 화면. */
class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private val vm: PlayerViewModel by viewModels()
    private lateinit var settings: Settings
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var cues: List<Cue> = emptyList()
    private var cueStarts = DoubleArray(0)
    private var shownCue: Cue? = null
    private var lastSync = 0L

    private val ticker = object : Runnable {
        override fun run() {
            updateSubtitle()
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        binding.backButton.setOnClickListener { finish() }
        binding.listButton.setOnClickListener { SubtitleListSheet().show(supportFragmentManager, "subs") }
        binding.moreButton.setOnClickListener { showMenu(it) }
        binding.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            binding.topBar.visibility = visibility
        })

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        binding.playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) vm.setPosition(new.positionMs / 1000.0)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) vm.setPosition(exo.currentPosition / 1000.0)
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.ui.collect { render(it) } }
                launch {
                    vm.cues.collect { list ->
                        cues = list
                        cueStarts = DoubleArray(list.size) { list[it].start }
                        shownCue = null
                    }
                }
                launch { vm.messages.collect { Toast.makeText(this@PlayerActivity, it, Toast.LENGTH_LONG).show() } }
                launch { vm.seekRequests.collect { sec -> player?.seekTo((sec * 1000).toLong()) } }
            }
        }
        applySubtitleStyle()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> intent?.data
        } ?: run {
            finish(); return
        }
        if (vm.uri == uri && player?.currentMediaItem != null) return
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        val resume = settings.position(uri.toString())
        if (resume > 0) {
            exo.seekTo(resume)
            Toast.makeText(this, "이어서 재생합니다", Toast.LENGTH_SHORT).show()
        }
        exo.playWhenReady = true
        binding.titleText.text = com.kosub.player.storage.MediaInfo.displayName(this, uri)

        val model = vm.needsModelDownload()
        if (settings.autoGenerate && model != null) {
            AlertDialog.Builder(this)
                .setTitle("음성 인식 모델 내려받기")
                .setMessage("자막을 만들려면 음성 인식 모델(${model.id}, 약 ${model.sizeMb}MB)을 한 번 내려받아야 합니다.\nWi-Fi 연결을 권장합니다.")
                .setPositiveButton("내려받기") { _, _ -> vm.open(uri, resume / 1000.0, autoStart = true) }
                .setNegativeButton("나중에") { _, _ -> vm.open(uri, resume / 1000.0, autoStart = false) }
                .setCancelable(false)
                .show()
        } else {
            vm.open(uri, resume / 1000.0, autoStart = settings.autoGenerate)
        }
    }

    private fun render(ui: PlayerViewModel.Ui) {
        val text = ui.downloadFraction?.let { "음성 인식 모델 내려받는 중 ${(it * 100).toInt()}% — 처음 한 번만" } ?: ui.status
        binding.statusText.text = text
        binding.coverageBar.update(ui.duration ?: player?.duration?.takeIf { it > 0 }?.div(1000.0), ui.coverage, ui.active)
    }

    private fun applySubtitleStyle() {
        binding.subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.subtitleSize.toFloat())
        if (settings.subtitleBackground) {
            binding.subtitleView.setBackgroundColor(Color.argb(140, 0, 0, 0))
            binding.subtitleView.setPadding(16, 6, 16, 6)
        } else {
            binding.subtitleView.background = null
        }
    }

    private fun cueAt(t: Double): Cue? {
        var lo = 0
        var hi = cueStarts.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cueStarts[mid] <= t) {
                idx = mid; lo = mid + 1
            } else hi = mid - 1
        }
        if (idx < 0) return null
        val c = cues[idx]
        return if (t < c.end) c else null
    }

    private fun updateSubtitle() {
        val exo = player ?: return
        val t = exo.currentPosition / 1000.0
        binding.coverageBar.setPosition(t)
        val cue = cueAt(t)
        if (cue !== shownCue) {
            shownCue = cue
            if (cue == null) {
                binding.subtitleView.text = ""
                binding.subtitleView.visibility = View.INVISIBLE
            } else {
                val showSrc = settings.showSource && cue.translated && cue.src.isNotEmpty() && cue.src != cue.text
                binding.subtitleView.text = if (showSrc) "${cue.text}\n${cue.src}" else cue.text
                binding.subtitleView.fillColor = if (cue.translated) Color.WHITE else Color.rgb(215, 215, 215)
                binding.subtitleView.visibility = View.VISIBLE
            }
        }
        val now = System.currentTimeMillis()
        if (exo.isPlaying && now - lastSync > 2000) {
            lastSync = now
            vm.setPosition(t)
        }
    }

    private fun showMenu(anchor: View) {
        val ui = vm.ui.value
        val menu = PopupMenu(this, anchor)
        if (ui.running) menu.menu.add(0, 1, 0, "자막 만들기 멈추기") else menu.menu.add(0, 2, 0, "자막 만들기 / 이어서 만들기")
        menu.menu.add(0, 3, 0, "번역 다시 하기")
        menu.menu.add(0, 4, 0, "처음부터 새로 만들기")
        menu.menu.add(0, 5, 0, if (settings.showSource) "한국어만 표시" else "원문 함께 표시")
        menu.menu.add(0, 6, 0, "자막 저장 위치")
        menu.menu.add(0, 7, 0, "설정")
        menu.setOnMenuItemClickListener { item ->
            val pos = (player?.currentPosition ?: 0L) / 1000.0
            when (item.itemId) {
                1 -> vm.stopJob()
                2 -> confirmModelThen { vm.start(pos) }
                3 -> confirmModelThen { vm.start(pos, retranslate = true) }
                4 -> AlertDialog.Builder(this)
                    .setMessage("지금 자막을 지우고 처음부터 다시 만들까요?")
                    .setPositiveButton("다시 만들기") { _, _ -> confirmModelThen { vm.start(pos, fresh = true) } }
                    .setNegativeButton("취소", null).show()
                5 -> {
                    settings.setShowSource(!settings.showSource); shownCue = null
                }
                6 -> AlertDialog.Builder(this)
                    .setTitle("자막 저장 위치")
                    .setMessage(
                        if (ui.savedTo.isEmpty()) "아직 저장된 자막이 없습니다.\n기본 저장 위치: 내장 메모리/Download/KoSubPlayer\n(설정에서 동영상이 있는 폴더로 바꿀 수 있습니다)"
                        else ui.savedTo.joinToString("\n")
                    )
                    .setPositiveButton("확인", null).show()
                7 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        menu.show()
    }

    private fun confirmModelThen(action: () -> Unit) {
        val model = vm.needsModelDownload() ?: return action()
        AlertDialog.Builder(this)
            .setTitle("음성 인식 모델 내려받기")
            .setMessage("음성 인식 모델(${model.id}, 약 ${model.sizeMb}MB)을 한 번 내려받습니다. Wi-Fi 연결을 권장합니다.")
            .setPositiveButton("내려받기") { _, _ -> action() }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
        applySubtitleStyle()
        shownCue = null
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        player?.let { exo ->
            vm.uri?.let { settings.savePosition(it.toString(), exo.currentPosition, exo.duration) }
            exo.pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFullscreen(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun applyFullscreen(landscape: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !landscape)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (landscape) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.attributes = window.attributes
    }
}
