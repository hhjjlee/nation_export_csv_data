"""메인 창: 동영상 재생 + 자막 자동 생성/표시/저장."""

from __future__ import annotations

import bisect
import logging
import os
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from PySide6.QtCore import QByteArray, QLocale, QTimer, Qt, QUrl
from PySide6.QtGui import QAction, QActionGroup, QDesktopServices, QKeySequence
from PySide6.QtMultimedia import QAudioOutput, QMediaMetaData, QMediaPlayer
from PySide6.QtWidgets import (
    QApplication,
    QComboBox,
    QDockWidget,
    QFileDialog,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMenu,
    QMessageBox,
    QProgressBar,
    QSlider,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

from .. import APP_NAME, __version__
from ..config import Settings
from ..jobstore import delete_state_for, existing_korean_srt, load_state_for, output_dir_for
from ..languages import language_name
from ..paths import logs_dir
from ..subtitles import Cue, Unit, build_cues, cues_to_units, format_clock, parse_srt
from . import theme
from .seekbar import SeekBar
from .settings_dialog import SettingsDialog
from .subtitle_panel import SubtitlePanel
from .video_view import VideoView
from .worker_client import WorkerClient

log = logging.getLogger(__name__)

MEDIA_FILTER = (
    "동영상/음성 파일 (*.mp4 *.mkv *.avi *.mov *.wmv *.webm *.flv *.m4v *.ts *.mts *.m2ts *.mpg *.mpeg "
    "*.3gp *.ogv *.vob *.mp3 *.m4a *.aac *.wav *.flac *.ogg *.opus *.wma);;모든 파일 (*)"
)
SPEEDS = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0]


class MainWindow(QMainWindow):
    def __init__(self, settings: Settings) -> None:
        super().__init__()
        self.settings = settings
        self.setWindowTitle(APP_NAME)
        self.setWindowIcon(theme.app_icon())
        self.resize(1280, 760)
        self.setAcceptDrops(True)

        # -- 재생기 -----------------------------------------------------------
        self.player = QMediaPlayer(self)
        self.audio = QAudioOutput(self)
        self.player.setAudioOutput(self.audio)
        self.view = VideoView()
        self.player.setVideoOutput(self.view.video_item)

        # -- 자막 상태 ---------------------------------------------------------
        self.video_path: Optional[str] = None
        self.job_id = 0
        self.job_active = False
        self.units: Dict[int, Unit] = {}
        self.cues: List[Cue] = []
        self._cue_starts: List[float] = []
        self.coverage: List[List[float]] = []
        self.active_region: Optional[List[float]] = None
        self.duration_sec: Optional[float] = None
        self.language: Optional[str] = None
        self.files: List[str] = []
        self.external_srt: Optional[str] = None
        self.speed: Optional[float] = None
        self.phase = ""
        self.phase_text = ""
        self.model_text = ""
        self.device = ""
        self._last_start: Optional[Dict[str, Any]] = None
        self._crash_times: List[float] = []
        self._pending_resume_ms = 0
        self._current_cue_id: Optional[int] = None
        self._was_maximized = False
        self._dock_was_visible = True
        self._fs_transition = False

        self.worker = WorkerClient(self)
        self.worker.event.connect(self._on_worker_event)
        self.worker.crashed.connect(self._on_worker_crashed)

        self._build_ui()
        self._build_actions()
        self._connect_player()
        self._apply_display_settings()
        self._restore_window()

        self._tick = QTimer(self)
        self._tick.setInterval(40)
        self._tick.timeout.connect(self._on_tick)
        self._tick.start()
        self._pos_sync = QTimer(self)
        self._pos_sync.setInterval(2000)
        self._pos_sync.timeout.connect(self._send_position)
        self._pos_sync.start()
        self._cue_timer = QTimer(self)
        self._cue_timer.setSingleShot(True)
        self._cue_timer.setInterval(120)
        self._cue_timer.timeout.connect(self._rebuild_cues)
        self._hide_timer = QTimer(self)
        self._hide_timer.setSingleShot(True)
        self._hide_timer.setInterval(2500)
        self._hide_timer.timeout.connect(self._hide_fullscreen_controls)
        self._set_status("동영상을 열면 자막을 자동으로 만들어 저장합니다.")

    # ------------------------------------------------------------------
    # 화면 구성
    def _tool(self, icon, tip: str, slot=None, checkable: bool = False) -> QToolButton:
        b = QToolButton()
        b.setIcon(icon)
        b.setToolTip(tip)
        b.setAutoRaise(True)
        b.setFocusPolicy(Qt.NoFocus)
        b.setCheckable(checkable)
        b.setIconSize(b.iconSize() * 1.3)
        if slot:
            (b.toggled if checkable else b.clicked).connect(slot)
        return b

    def _build_ui(self) -> None:
        central = QWidget()
        layout = QVBoxLayout(central)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        layout.addWidget(self.view, 1)

        self.controls = QWidget()
        self.controls.setObjectName("controls")
        self.controls.setStyleSheet("#controls { background: #1c1c1f; border-top: 1px solid #333; }")
        c = QVBoxLayout(self.controls)
        c.setContentsMargins(10, 4, 10, 6)
        c.setSpacing(2)
        self.seekbar = SeekBar()
        self.seekbar.seekRequested.connect(self._seek_ms)
        c.addWidget(self.seekbar)
        row = QHBoxLayout()
        row.setSpacing(4)
        self.btn_play = self._tool(theme.icon_play(), "재생/일시정지 (Space)", self.toggle_play)
        self.btn_stop = self._tool(theme.icon_stop(), "정지", self.stop)
        self.btn_back = self._tool(theme.icon_skip(False), "5초 뒤로 (←)", lambda: self.skip(-5))
        self.btn_fwd = self._tool(theme.icon_skip(True), "5초 앞으로 (→)", lambda: self.skip(5))
        for b in (self.btn_play, self.btn_stop, self.btn_back, self.btn_fwd):
            row.addWidget(b)
        self.time_label = QLabel("00:00 / 00:00")
        self.time_label.setStyleSheet("color: #ddd; padding: 0 8px; font-family: monospace;")
        row.addWidget(self.time_label)
        row.addStretch(1)
        self.btn_cc = self._tool(theme.icon_cc(), "자막 표시 (C)", self._toggle_subtitles, checkable=True)
        self.btn_list = self._tool(theme.icon_list(), "자막 목록 (L)", None, checkable=True)
        row.addWidget(self.btn_cc)
        row.addWidget(self.btn_list)
        self.speed_combo = QComboBox()
        self.speed_combo.setFocusPolicy(Qt.NoFocus)
        self.speed_combo.setToolTip("재생 속도 ([ / ])")
        for s in SPEEDS:
            self.speed_combo.addItem(f"{s:g}x", s)
        self.speed_combo.currentIndexChanged.connect(lambda i: self.set_speed(SPEEDS[i]))
        row.addWidget(self.speed_combo)
        self.btn_mute = self._tool(theme.icon_volume(False), "음소거 (M)", self.toggle_mute)
        row.addWidget(self.btn_mute)
        self.volume = QSlider(Qt.Horizontal)
        self.volume.setFocusPolicy(Qt.NoFocus)
        self.volume.setRange(0, 100)
        self.volume.setFixedWidth(110)
        self.volume.setToolTip("볼륨 (↑/↓, 마우스 휠)")
        self.volume.valueChanged.connect(self._set_volume)
        row.addWidget(self.volume)
        self.btn_full = self._tool(theme.icon_fullscreen(), "전체 화면 (F, 두 번 클릭)", self.toggle_fullscreen)
        row.addWidget(self.btn_full)
        c.addLayout(row)
        layout.addWidget(self.controls)
        self.setCentralWidget(central)

        self.panel = SubtitlePanel()
        self.panel.seekRequested.connect(lambda sec: self._seek_ms(int(sec * 1000)))
        self.panel.editRequested.connect(self._edit_unit)
        self.dock = QDockWidget("자막 목록", self)
        self.dock.setObjectName("subtitleDock")
        self.dock.setWidget(self.panel)
        self.dock.setFeatures(QDockWidget.DockWidgetMovable | QDockWidget.DockWidgetClosable | QDockWidget.DockWidgetFloatable)
        self.addDockWidget(Qt.RightDockWidgetArea, self.dock)
        self.resizeDocks([self.dock], [400], Qt.Horizontal)
        self.dock.visibilityChanged.connect(self._on_dock_visibility)
        self.btn_list.toggled.connect(lambda on: self.dock.setVisible(on))

        sb = self.statusBar()
        self.status_label = QLabel()
        self.status_label.setTextFormat(Qt.PlainText)
        sb.addWidget(self.status_label, 1)
        self.progress = QProgressBar()
        self.progress.setFixedWidth(150)
        self.progress.setMaximumHeight(14)
        self.progress.setTextVisible(True)
        self.progress.setFormat("%p%")
        self.progress.setRange(0, 1000)
        self.progress.setValue(0)
        self.progress.setVisible(False)
        sb.addPermanentWidget(self.progress)
        self.lang_label = QLabel("")
        sb.addPermanentWidget(self.lang_label)
        self.engine_label = QLabel("")
        self.engine_label.setStyleSheet("color: #9a9a9a;")
        sb.addPermanentWidget(self.engine_label)

        v = self.view
        v.clicked.connect(self.toggle_play)
        v.doubleClicked.connect(self.toggle_fullscreen)
        v.wheelSteps.connect(lambda step: self.change_volume(5 * step))
        v.mouseMoved.connect(self._on_mouse_moved)
        v.filesDropped.connect(self._open_dropped)
        v.contextMenuAt.connect(self._show_context_menu)

    def _action(self, text: str, slot, shortcut=None, checkable: bool = False, menu: Optional[QMenu] = None) -> QAction:
        act = QAction(text, self)
        if shortcut:
            if isinstance(shortcut, (list, tuple)):
                act.setShortcuts([QKeySequence(s) for s in shortcut])
            else:
                act.setShortcut(QKeySequence(shortcut))
        act.setCheckable(checkable)
        if checkable:
            act.toggled.connect(slot)
        else:
            act.triggered.connect(lambda _=False: slot())
        self.addAction(act)  # 메뉴가 숨겨진 전체 화면에서도 단축키가 동작하도록
        if menu is not None:
            menu.addAction(act)
        return act

    def _build_actions(self) -> None:
        mb = self.menuBar()
        m_file = mb.addMenu("파일(&F)")
        self._action("열기…", self.open_dialog, QKeySequence.Open, menu=m_file)
        self.recent_menu = m_file.addMenu("최근 파일")
        self.recent_menu.aboutToShow.connect(self._fill_recent)
        m_file.addSeparator()
        self._action("자막 폴더 열기", self.open_subtitle_folder, menu=m_file)
        m_file.addSeparator()
        self._action("끝내기", self.close, QKeySequence.Quit, menu=m_file)

        m_play = mb.addMenu("재생(&P)")
        self._action("재생/일시정지", self.toggle_play, "Space", menu=m_play)
        self._action("정지", self.stop, menu=m_play)
        m_play.addSeparator()
        self._action("5초 뒤로", lambda: self.skip(-5), "Left", menu=m_play)
        self._action("5초 앞으로", lambda: self.skip(5), "Right", menu=m_play)
        self._action("30초 뒤로", lambda: self.skip(-30), "Ctrl+Left", menu=m_play)
        self._action("30초 앞으로", lambda: self.skip(30), "Ctrl+Right", menu=m_play)
        self._action("이전 자막으로", lambda: self.jump_cue(-1), "A", menu=m_play)
        self._action("다음 자막으로", lambda: self.jump_cue(1), "D", menu=m_play)
        m_play.addSeparator()
        self._action("느리게", lambda: self.step_speed(-1), "[", menu=m_play)
        self._action("빠르게", lambda: self.step_speed(1), "]", menu=m_play)
        self._action("보통 속도", lambda: self.set_speed(1.0), "Backspace", menu=m_play)
        m_play.addSeparator()
        self._action("볼륨 크게", lambda: self.change_volume(5), "Up", menu=m_play)
        self._action("볼륨 작게", lambda: self.change_volume(-5), "Down", menu=m_play)
        self._action("음소거", self.toggle_mute, "M", menu=m_play)
        self.audio_menu = m_play.addMenu("오디오 트랙")
        self.audio_menu.aboutToShow.connect(self._fill_audio_tracks)
        m_play.addSeparator()
        self._action("전체 화면", self.toggle_fullscreen, ["F", "Alt+Return"], menu=m_play)
        self._action("전체 화면 끝내기", self._exit_fullscreen, "Esc")

        m_sub = mb.addMenu("자막(&S)")
        self.act_generate = self._action("자막 만들기 / 이어서 만들기", self.generate, "Ctrl+G", menu=m_sub)
        self.act_retranslate = self._action("번역 다시 하기 (현재 번역 설정으로)", self.retranslate, menu=m_sub)
        self.act_regenerate = self._action("처음부터 새로 만들기…", self.regenerate, menu=m_sub)
        self.act_stop_job = self._action("자막 만들기 멈추기", self.stop_job, menu=m_sub)
        m_sub.addSeparator()
        self.act_show = self._action("자막 표시", self._toggle_subtitles, "C", checkable=True, menu=m_sub)
        self.act_source = self._action("원문 함께 표시", self._toggle_source, "V", checkable=True, menu=m_sub)
        self.act_panel = self.dock.toggleViewAction()
        self.act_panel.setText("자막 목록 보기")
        self.act_panel.setShortcut(QKeySequence("L"))
        self.addAction(self.act_panel)
        m_sub.addAction(self.act_panel)
        self._action("자막 크게", lambda: self.change_sub_scale(0.1), ["Ctrl++", "Ctrl+="], menu=m_sub)
        self._action("자막 작게", lambda: self.change_sub_scale(-0.1), "Ctrl+-", menu=m_sub)
        m_sub.addSeparator()
        self._action("자막 폴더 열기", self.open_subtitle_folder, menu=m_sub)

        m_tr = mb.addMenu("번역(&T)")
        group = QActionGroup(self)
        self.tr_actions = {}
        for key, label in (("google", "구글 번역 (무료)"), ("claude", "Claude AI (API 키 필요)"), ("none", "번역 안 함 (원어 자막)")):
            act = QAction(label, self, checkable=True)
            act.setData(key)
            group.addAction(act)
            m_tr.addAction(act)
            act.triggered.connect(lambda _=False, k=key: self._set_translator(k))
            self.tr_actions[key] = act
        m_tr.addSeparator()
        self._action("환경설정…", self.open_settings, "Ctrl+,", menu=m_tr)

        m_set = mb.addMenu("설정(&O)")
        self._action("환경설정…", self.open_settings, menu=m_set)
        self.act_auto = self._action("동영상을 열면 자동으로 자막 만들기", self._toggle_auto, checkable=True, menu=m_set)

        m_help = mb.addMenu("도움말(&H)")
        self._action("단축키 안내", self.show_shortcuts, "F1", menu=m_help)
        self._action("로그 폴더 열기", lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(logs_dir()))), menu=m_help)
        self._action(f"{APP_NAME} 정보", self.show_about, menu=m_help)

    def _connect_player(self) -> None:
        p = self.player
        p.durationChanged.connect(self._on_duration)
        p.playbackStateChanged.connect(self._on_playback_state)
        p.mediaStatusChanged.connect(self._on_media_status)
        p.errorOccurred.connect(self._on_player_error)
        p.tracksChanged.connect(self._on_tracks_changed)

    def _apply_display_settings(self) -> None:
        s = self.settings
        self.volume.blockSignals(True)
        self.volume.setValue(s.volume)
        self.volume.blockSignals(False)
        self._set_volume(s.volume)
        self.audio.setMuted(s.muted)
        self.btn_mute.setIcon(theme.icon_volume(s.muted))
        self.act_show.blockSignals(True)
        self.act_show.setChecked(s.show_subtitles)
        self.act_show.blockSignals(False)
        self.btn_cc.blockSignals(True)
        self.btn_cc.setChecked(s.show_subtitles)
        self.btn_cc.blockSignals(False)
        self.act_source.blockSignals(True)
        self.act_source.setChecked(s.show_source_text)
        self.act_source.blockSignals(False)
        self.act_auto.blockSignals(True)
        self.act_auto.setChecked(s.auto_generate)
        self.act_auto.blockSignals(False)
        self.tr_actions.get(s.translator, self.tr_actions["google"]).setChecked(True)
        self.view.subtitle.set_style(s.subtitle_scale, s.subtitle_background)
        idx = SPEEDS.index(s.playback_rate) if s.playback_rate in SPEEDS else SPEEDS.index(1.0)
        self.speed_combo.blockSignals(True)
        self.speed_combo.setCurrentIndex(idx)
        self.speed_combo.blockSignals(False)
        self.player.setPlaybackRate(SPEEDS[idx])
        self._update_engine_label()
        self._rebuild_cues()

    def _restore_window(self) -> None:
        s = self.settings
        if s.window_geometry:
            self.restoreGeometry(QByteArray.fromBase64(s.window_geometry.encode()))
        if s.window_state:
            self.restoreState(QByteArray.fromBase64(s.window_state.encode()))
        self.dock.setVisible(s.panel_visible)
        self.btn_list.setChecked(s.panel_visible)

    # ------------------------------------------------------------------
    # 파일 열기
    def open_dialog(self) -> None:
        start = self.settings.last_dir or str(Path.home())
        path, _ = QFileDialog.getOpenFileName(self, "동영상 열기", start, MEDIA_FILTER)
        if path:
            self.open_file(path)

    def _open_dropped(self, paths: List[str]) -> None:
        files = [p for p in paths if os.path.isfile(p)]
        if not files:
            return
        subs = [p for p in files if p.lower().endswith(".srt")]
        media = [p for p in files if not p.lower().endswith(".srt")]
        if media:
            self.open_file(media[0])
        elif subs and self.video_path:
            self._load_external_srt(subs[0])

    def dragEnterEvent(self, event) -> None:
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event) -> None:
        self._open_dropped([u.toLocalFile() for u in event.mimeData().urls() if u.isLocalFile()])

    def _fill_recent(self) -> None:
        self.recent_menu.clear()
        items = [p for p in self.settings.recent_files if os.path.exists(p)]
        if not items:
            act = self.recent_menu.addAction("(없음)")
            act.setEnabled(False)
            return
        for p in items:
            act = self.recent_menu.addAction(os.path.basename(p))
            act.setToolTip(p)
            act.triggered.connect(lambda _=False, path=p: self.open_file(path))

    def open_file(self, path: str) -> None:
        path = os.path.abspath(path)
        if not os.path.isfile(path):
            QMessageBox.warning(self, APP_NAME, f"파일을 찾을 수 없습니다:\n{path}")
            return
        self._leave_current()
        self.video_path = path
        self.settings.add_recent(path)
        self.settings.last_dir = os.path.dirname(path)
        self.settings.save()
        self._reset_subtitles()
        self.setWindowTitle(f"{os.path.basename(path)} — {APP_NAME}")
        self.view.set_placeholder_visible(False)
        resume = self.settings.positions.get(path) if self.settings.resume_playback else None
        self._pending_resume_ms = int(resume * 1000) if resume else 0
        self.player.setSource(QUrl.fromLocalFile(path))
        self.player.setPlaybackRate(self.settings.playback_rate)
        self.player.play()
        self._prepare_subtitles(path)

    def _leave_current(self) -> None:
        if not self.video_path:
            return
        self.settings.remember_position(self.video_path, self.player.position() / 1000.0, self.player.duration() / 1000.0)
        if self.job_active:
            self.worker.send({"cmd": "cancel"})
        self.job_active = False
        self.player.stop()

    def _reset_subtitles(self) -> None:
        self.units.clear()
        self.cues = []
        self._cue_starts = []
        self.coverage = []
        self.active_region = None
        self.language = None
        self.files = []
        self.external_srt = None
        self.speed = None
        self.duration_sec = None
        self._current_cue_id = None
        self.panel.set_units([])
        self.panel.model.set_editable(True)
        self.seekbar.set_coverage([])
        self.view.subtitle.set_texts("")
        self.lang_label.setText("")
        self.progress.setVisible(False)

    def _prepare_subtitles(self, path: str) -> None:
        opts = self.settings.job_options()
        state = load_state_for(path)
        if state is not None:
            self._apply_state(
                [u.to_dict() for u in state.units.values()],
                state.covered.to_list(),
                state.duration,
                state.language,
                state.outputs,
            )
            if state.complete:
                self._set_progress(1.0)
                self._set_status(f"저장해 둔 한국어 자막을 불러왔습니다 ({len(state.units)}개).")
                return
            if self.settings.auto_generate:
                self.start_job()
            else:
                self._set_status("이전에 만들다 만 자막이 있습니다. [자막 → 자막 만들기]로 이어서 만드세요.")
            return
        external = existing_korean_srt(path, opts)
        if external is not None:
            self._load_external_srt(str(external))
            return
        if self.settings.auto_generate:
            self.start_job()
        else:
            self._set_status("[자막 → 자막 만들기] (Ctrl+G) 를 누르면 한국어 자막을 만듭니다.")

    def _load_external_srt(self, srt_path: str) -> None:
        try:
            text = Path(srt_path).read_text("utf-8-sig")
        except UnicodeDecodeError:
            text = Path(srt_path).read_text("cp949", errors="replace")
        except OSError as exc:
            QMessageBox.warning(self, APP_NAME, f"자막 파일을 읽지 못했습니다:\n{exc}")
            return
        units = cues_to_units(parse_srt(text))
        self.external_srt = srt_path
        self.units = {u.id: u for u in units}
        self.panel.set_units(units)
        self.panel.model.set_editable(False)
        self._rebuild_cues()
        self._set_status(
            f"기존 자막 파일을 불러왔습니다: {os.path.basename(srt_path)} — 새로 만들려면 [자막 → 처음부터 새로 만들기]"
        )

    # ------------------------------------------------------------------
    # 자막 작업
    def start_job(self, retranslate: bool = False, force_cpu: bool = False) -> None:
        if not self.video_path:
            return
        self.job_id += 1
        self.job_active = True
        self.external_srt = None
        self.panel.model.set_editable(True)
        opts = self.settings.job_options()
        if force_cpu:
            opts["device"] = "cpu"
        pos = self.player.position() / 1000.0 or self._pending_resume_ms / 1000.0
        dur = self.player.duration() / 1000.0 or None
        msg = {
            "cmd": "start",
            "job": self.job_id,
            "path": self.video_path,
            "options": opts,
            "position": pos,
            "duration": dur,
            "audio_track": max(0, self.player.activeAudioTrack()),
            "retranslate": retranslate,
        }
        self._last_start = msg
        self.device = opts.get("device", "auto")
        self.phase = "prepare"
        self._set_status("자막 만들 준비 중…")
        self.progress.setVisible(True)
        if not self.worker.send(msg):
            self.job_active = False
            self._set_status("자막 작업 프로세스를 시작하지 못했습니다. 로그 폴더를 확인하세요.")

    def generate(self) -> None:
        if not self.video_path:
            self.open_dialog()
            return
        if self.external_srt:
            self.regenerate()
            return
        self.start_job()

    def retranslate(self) -> None:
        if not self.video_path or self.external_srt:
            return
        if (self.language or "") == "ko":
            QMessageBox.information(self, APP_NAME, "원래 한국어인 동영상이라 번역할 필요가 없습니다.")
            return
        self.start_job(retranslate=True)

    def regenerate(self) -> None:
        if not self.video_path:
            return
        msg = "지금 자막을 지우고 처음부터 다시 만들까요?\n(같은 이름의 .ko.srt 파일은 새 자막으로 덮어씁니다)"
        if QMessageBox.question(self, APP_NAME, msg) != QMessageBox.Yes:
            return
        if self.job_active:
            self.worker.send({"cmd": "cancel"})
            self.job_active = False
        self.job_id += 1  # 이전 작업에서 오는 이벤트 무시
        QTimer.singleShot(300, self._do_regenerate)

    def _do_regenerate(self) -> None:
        path = self.video_path
        if not path:
            return
        delete_state_for(path)
        self._reset_subtitles()
        self.start_job()

    def stop_job(self) -> None:
        if self.job_active:
            self.worker.send({"cmd": "cancel"})
            self.job_active = False
            self._set_status("자막 만들기를 멈췄습니다. 지금까지 만든 자막은 저장되었습니다.")

    def _edit_unit(self, unit_id: int, text: str) -> None:
        unit = self.units.get(unit_id)
        if unit is None or self.external_srt:
            return
        unit.ko = text or None
        unit.edited = bool(text)
        self.panel.upsert([unit])
        self._rebuild_cues()
        if self.job_active or self.worker.is_running():
            # 작업이 끝났어도 워커가 같은 동영상 작업을 들고 있으면 거기서 저장한다
            if self._last_start and self._last_start.get("path") == self.video_path:
                self.worker.send({"cmd": "edit", "id": unit_id, "text": text})
                return
        self._save_edit_locally(unit_id, text)

    def _save_edit_locally(self, unit_id: int, text: str) -> None:
        from ..jobstore import state_path_for, write_outputs

        path = self.video_path
        state = load_state_for(path) if path else None
        if state is None or unit_id not in state.units:
            return
        u = state.units[unit_id]
        u.ko, u.edited = (text or None), bool(text)
        try:
            write_outputs(state, self.settings.job_options())
            state.save(state_path_for(path))
            self.statusBar().showMessage("수정한 자막을 저장했습니다.", 3000)
        except OSError as exc:
            QMessageBox.warning(self, APP_NAME, f"저장하지 못했습니다:\n{exc}")

    def _send_position(self, force: bool = False) -> None:
        if not self.job_active:
            return
        if force or self.player.playbackState() == QMediaPlayer.PlayingState:
            self.worker.send({"cmd": "position", "t": self.player.position() / 1000.0})

    # ------------------------------------------------------------------
    # 워커 이벤트
    def _on_worker_event(self, ev: Dict[str, Any]) -> None:
        kind = ev.get("ev")
        if kind in ("hello", "pong"):
            return
        if ev.get("job") != self.job_id:
            return
        if kind == "state":
            self._apply_state(ev.get("units") or [], ev.get("coverage") or [], ev.get("duration"), ev.get("language"), ev.get("files") or [])
        elif kind == "units":
            units = [Unit.from_dict(d) for d in ev.get("items") or []]
            for u in units:
                self.units[u.id] = u
            self.panel.upsert(units)
            self._cue_timer.start()
        elif kind == "coverage":
            self.coverage = ev.get("intervals") or []
            self.active_region = ev.get("active")
            if ev.get("duration"):
                self.duration_sec = float(ev["duration"])
            if ev.get("speed"):
                self.speed = float(ev["speed"])
            self.seekbar.set_coverage(self.coverage, self.active_region)
            self._update_progress()
        elif kind == "status":
            self.phase = ev.get("phase", "")
            self.phase_text = ev.get("text", "")
            if self.phase != "download":
                self._update_progress()
        elif kind == "download":
            total = ev.get("total") or 0
            done = ev.get("done") or 0
            self.progress.setVisible(True)
            if total:
                frac = min(1.0, done / total)
                self.progress.setValue(int(frac * 1000))
                self.progress.setFormat(f"모델 {frac:.0%}")
            self._set_status(self.phase_text or "음성 인식 모델 내려받는 중…")
        elif kind == "model":
            self.device = ev.get("device", "")
            self.model_text = f"{ev.get('name')} · {str(ev.get('device', '')).upper()}"
            self._update_engine_label()
            note = ev.get("note") or ""
            if self.device == "cpu" and note and "GPU" in note and "없음" not in note:
                self.statusBar().showMessage(f"CPU로 처리합니다 ({note})", 6000)
        elif kind == "language":
            self.language = ev.get("code")
            self._update_language_label(ev.get("prob"))
        elif kind == "saved":
            self.files = ev.get("files") or []
        elif kind == "warning":
            self.statusBar().showMessage(ev.get("text", ""), 6000)
        elif kind == "error":
            text = ev.get("text", "알 수 없는 오류")
            if ev.get("fatal"):
                self.job_active = False
                self._set_status(f"오류: {text}")
                QMessageBox.warning(self, APP_NAME, text)
            else:
                self.statusBar().showMessage(text, 10000)
                QMessageBox.warning(self, APP_NAME, text)
        elif kind == "done":
            self.job_active = False
            self.files = ev.get("files") or self.files
            self._set_progress(1.0 if ev.get("complete") else None)
            saved = ", ".join(os.path.basename(f) for f in self.files[:2])
            self._set_status(ev.get("message", "완료") + (f"  [{saved}]" if saved else ""))
            self.active_region = None
            self.seekbar.set_coverage(self.coverage, None)
        elif kind == "finished":
            self.job_active = False

    def _apply_state(self, units, coverage, duration, language, files) -> None:
        parsed = [Unit.from_dict(d) for d in units]
        self.units = {u.id: u for u in parsed}
        self.panel.set_units(parsed)
        self.coverage = coverage
        self.seekbar.set_coverage(coverage, None)
        if duration:
            self.duration_sec = float(duration)
        if language:
            self.language = language
            self._update_language_label(None)
        self.files = files or []
        self._rebuild_cues()
        self._update_progress()

    def _on_worker_crashed(self, code: int, tail: str) -> None:
        log.error("워커 비정상 종료 %s\n%s", code, tail[-2000:])
        if not self.job_active or not self._last_start:
            return
        now = time.time()
        self._crash_times = [t for t in self._crash_times if now - t < 120] + [now]
        gpu_phase = self.phase in ("model_load", "warmup", "prepare", "download") and self.device != "cpu"
        if gpu_phase and not self.settings.cuda_failed:
            self.settings.cuda_failed = True
            self.settings.save()
            self.statusBar().showMessage("GPU 초기화에 실패해서 CPU로 다시 시작합니다…", 8000)
            self.start_job(retranslate=bool(self._last_start.get("retranslate")), force_cpu=True)
            return
        if len(self._crash_times) <= 2:
            self.statusBar().showMessage("자막 작업 프로세스를 다시 시작합니다…", 5000)
            self.start_job(
                retranslate=bool(self._last_start.get("retranslate")),
                force_cpu=self.device == "cpu" or len(self._crash_times) >= 2,
            )
            return
        self.job_active = False
        self._set_status("자막 작업이 계속 실패합니다. 로그 폴더의 worker.log를 확인하세요.")
        QMessageBox.critical(self, APP_NAME, "자막 작업 프로세스가 반복해서 종료되었습니다.\n\n" + tail[-1500:])

    # ------------------------------------------------------------------
    # 자막 표시
    def _display_mode(self) -> str:
        if self.settings.translator == "none" and (self.language or "") != "ko" and not self.external_srt:
            return "src"
        return "ko"

    def _rebuild_cues(self) -> None:
        mode = self._display_mode()
        self.cues = build_cues(self.units.values(), mode=mode)
        self._cue_starts = [c.start for c in self.cues]
        self._current_cue_id = -2  # 다음 tick에서 다시 그리기
        korean_source = (self.language or "") == "ko" or bool(self.external_srt)
        self.panel.set_columns(show_source=not korean_source, show_korean=mode == "ko")

    def _cue_at(self, t: float) -> Optional[Cue]:
        i = bisect.bisect_right(self._cue_starts, t) - 1
        if 0 <= i < len(self.cues) and t < self.cues[i].end:
            return self.cues[i]
        return None

    def _on_tick(self) -> None:
        ms = self.player.position()
        t = ms / 1000.0
        cue = self._cue_at(t) if self.settings.show_subtitles else None
        key = (id(cue) if cue else None)
        if key != self._current_cue_id:
            self._current_cue_id = key
            if cue is None:
                self.view.subtitle.set_texts("")
            else:
                sub = ""
                if self.settings.show_source_text and cue.translated and cue.src and cue.src != cue.text:
                    sub = cue.src
                self.view.subtitle.set_texts(cue.text, sub, dim_main=not cue.translated)
            self.panel.set_current(cue.unit_id if cue else None)
        if not self.seekbar.is_dragging():
            self.seekbar.set_position(ms)
        dur = self.player.duration()
        self.time_label.setText(f"{format_clock(t)} / {format_clock(dur / 1000.0)}")

    def jump_cue(self, direction: int) -> None:
        if not self.cues:
            return
        t = self.player.position() / 1000.0
        if direction > 0:
            i = bisect.bisect_right(self._cue_starts, t + 0.05)
            if i < len(self.cues):
                self._seek_ms(int(self.cues[i].start * 1000))
        else:
            i = bisect.bisect_left(self._cue_starts, t - 0.6) - 1
            if i >= 0:
                self._seek_ms(int(self.cues[i].start * 1000))

    def _toggle_subtitles(self, on: bool) -> None:
        self.settings.show_subtitles = bool(on)
        for w in (self.act_show, self.btn_cc):
            w.blockSignals(True)
            w.setChecked(bool(on))
            w.blockSignals(False)
        self._current_cue_id = -2
        self.view.show_osd("자막 켬" if on else "자막 끔")

    def _toggle_source(self, on: bool) -> None:
        self.settings.show_source_text = bool(on)
        self._current_cue_id = -2
        self.view.show_osd("원문 함께 표시" if on else "한국어만 표시")

    def change_sub_scale(self, delta: float) -> None:
        s = self.settings
        s.subtitle_scale = round(min(2.0, max(0.6, s.subtitle_scale + delta)), 2)
        self.view.subtitle.set_style(s.subtitle_scale, s.subtitle_background)
        self.view.show_osd(f"자막 크기 {int(s.subtitle_scale * 100)}%")

    # ------------------------------------------------------------------
    # 재생 제어
    def toggle_play(self) -> None:
        if not self.video_path:
            self.open_dialog()
            return
        if self.player.playbackState() == QMediaPlayer.PlayingState:
            self.player.pause()
        else:
            self.player.play()

    def stop(self) -> None:
        self.player.stop()

    def skip(self, seconds: float) -> None:
        if not self.video_path:
            return
        target = max(0, self.player.position() + int(seconds * 1000))
        if self.player.duration() > 0:
            target = min(target, self.player.duration() - 500)
        self._seek_ms(target)
        self.view.show_osd(("▶▶ " if seconds > 0 else "◀◀ ") + f"{abs(seconds):g}초")

    def _seek_ms(self, ms: int) -> None:
        self.player.setPosition(max(0, int(ms)))
        self.seekbar.set_position(ms)
        self._send_position(force=True)
        self._current_cue_id = -2

    def set_speed(self, rate: float) -> None:
        self.settings.playback_rate = rate
        self.player.setPlaybackRate(rate)
        idx = SPEEDS.index(rate) if rate in SPEEDS else SPEEDS.index(1.0)
        if self.speed_combo.currentIndex() != idx:
            self.speed_combo.blockSignals(True)
            self.speed_combo.setCurrentIndex(idx)
            self.speed_combo.blockSignals(False)
        self.view.show_osd(f"재생 속도 {rate:g}x")

    def step_speed(self, step: int) -> None:
        cur = self.settings.playback_rate
        idx = SPEEDS.index(cur) if cur in SPEEDS else SPEEDS.index(1.0)
        self.set_speed(SPEEDS[max(0, min(len(SPEEDS) - 1, idx + step))])

    def _set_volume(self, value: int) -> None:
        self.settings.volume = int(value)
        self.audio.setVolume((value / 100.0) ** 2)  # 귀로 듣기에 고르게 변하도록

    def change_volume(self, delta: int) -> None:
        v = max(0, min(100, self.volume.value() + delta))
        self.volume.setValue(v)
        if self.audio.isMuted() and delta > 0:
            self.toggle_mute()
        self.view.show_osd(f"볼륨 {v}%")

    def toggle_mute(self) -> None:
        muted = not self.audio.isMuted()
        self.audio.setMuted(muted)
        self.settings.muted = muted
        self.btn_mute.setIcon(theme.icon_volume(muted))
        self.view.show_osd("음소거" if muted else f"볼륨 {self.volume.value()}%")

    def _fill_audio_tracks(self) -> None:
        self.audio_menu.clear()
        tracks = self.player.audioTracks()
        if not tracks:
            act = self.audio_menu.addAction("(없음)")
            act.setEnabled(False)
            return
        active = self.player.activeAudioTrack()
        for i, md in enumerate(tracks):
            lang = md.value(QMediaMetaData.Language)
            title = md.value(QMediaMetaData.Title)
            if isinstance(lang, QLocale.Language):
                lang = QLocale.languageToString(lang) if lang != QLocale.AnyLanguage else None
            label = f"트랙 {i + 1}"
            extra = " · ".join(str(x) for x in (title, lang) if x not in (None, ""))
            if extra:
                label += f" ({extra})"
            act = self.audio_menu.addAction(label)
            act.setCheckable(True)
            act.setChecked(i == active)
            act.triggered.connect(lambda _=False, idx=i: self.player.setActiveAudioTrack(idx))

    # ------------------------------------------------------------------
    # 플레이어 신호
    def _on_duration(self, ms: int) -> None:
        self.seekbar.set_duration(ms)

    def _on_playback_state(self, state) -> None:
        playing = state == QMediaPlayer.PlayingState
        self.btn_play.setIcon(theme.icon_pause() if playing else theme.icon_play())
        if playing:
            self._send_position(force=True)

    def _on_media_status(self, status) -> None:
        if status in (QMediaPlayer.LoadedMedia, QMediaPlayer.BufferedMedia) and self._pending_resume_ms:
            ms, self._pending_resume_ms = self._pending_resume_ms, 0
            self.player.setPosition(ms)
            self._send_position(force=True)
            self.view.show_osd(f"이어서 재생 {format_clock(ms / 1000.0)}", 2500)
        elif status == QMediaPlayer.EndOfMedia:
            self.view.show_osd("재생 끝")

    def _on_tracks_changed(self) -> None:
        # 파일에 들어 있는 자막 트랙은 끄고 만든 자막만 보여 준다
        try:
            if self.player.activeSubtitleTrack() != -1:
                self.player.setActiveSubtitleTrack(-1)
        except AttributeError:
            pass

    def _on_player_error(self, error, text: str) -> None:
        if error == QMediaPlayer.NoError:
            return
        self.statusBar().showMessage(f"재생 오류: {text}", 10000)
        log.warning("재생 오류 %s: %s", error, text)

    # ------------------------------------------------------------------
    # 전체 화면
    def toggle_fullscreen(self) -> None:
        if self.isFullScreen():
            self._exit_fullscreen()
        else:
            self._was_maximized = self.isMaximized()
            self._dock_was_visible = self.dock.isVisible()
            self._fs_transition = True
            self.showFullScreen()
            self.menuBar().hide()
            self.statusBar().hide()
            self.dock.hide()
            self._fs_transition = False
            self._hide_timer.start()

    def _exit_fullscreen(self) -> None:
        if not self.isFullScreen():
            return
        self._hide_timer.stop()
        self.menuBar().show()
        self.statusBar().show()
        self.controls.show()
        self.view.viewport().unsetCursor()
        if self._dock_was_visible:
            self.dock.show()
        if self._was_maximized:
            self.showMaximized()
        else:
            self.showNormal()

    def _on_mouse_moved(self) -> None:
        if self.isFullScreen():
            self.controls.show()
            self.view.viewport().unsetCursor()
            self._hide_timer.start()

    def _hide_fullscreen_controls(self) -> None:
        if self.isFullScreen():
            self.controls.hide()
            self.view.viewport().setCursor(Qt.BlankCursor)

    def _on_dock_visibility(self, visible: bool) -> None:
        if not self.isFullScreen() and not self._fs_transition:
            self.settings.panel_visible = visible
        self.btn_list.blockSignals(True)
        self.btn_list.setChecked(visible)
        self.btn_list.blockSignals(False)

    def _show_context_menu(self, global_pos) -> None:
        menu = QMenu(self)
        menu.addAction("열기…", self.open_dialog)
        menu.addAction("재생/일시정지", self.toggle_play)
        menu.addAction("전체 화면", self.toggle_fullscreen)
        menu.addSeparator()
        menu.addAction(self.act_show)
        menu.addAction(self.act_source)
        menu.addAction(self.act_panel)
        menu.addSeparator()
        menu.addAction(self.act_generate)
        menu.addAction(self.act_retranslate)
        menu.addAction("자막 폴더 열기", self.open_subtitle_folder)
        menu.exec(global_pos)

    # ------------------------------------------------------------------
    # 설정
    def _set_translator(self, key: str) -> None:
        if self.settings.translator == key:
            return
        if key == "claude" and not (self.settings.claude_api_key or os.environ.get("ANTHROPIC_API_KEY")):
            QMessageBox.information(self, APP_NAME, "Claude 번역을 쓰려면 환경설정 → 번역 탭에서 API 키를 입력하세요.")
            self.open_settings()
            if not (self.settings.claude_api_key or os.environ.get("ANTHROPIC_API_KEY")):
                self.tr_actions.get(self.settings.translator, self.tr_actions["google"]).setChecked(True)
                return
        self.settings.translator = key
        self.settings.save()
        self.tr_actions[key].setChecked(True)
        self._update_engine_label()
        self._rebuild_cues()
        self._settings_changed({"translator"})

    def _toggle_auto(self, on: bool) -> None:
        self.settings.auto_generate = bool(on)
        self.settings.save()

    def open_settings(self) -> None:
        before = self.settings.job_options()
        dlg = SettingsDialog(self.settings, self)
        if dlg.exec() != SettingsDialog.Accepted:
            return
        dlg.apply()
        self.settings.save()
        self._apply_display_settings()
        after = self.settings.job_options()
        self._settings_changed({k for k in after if before.get(k) != after.get(k)})

    _TR_KEYS = {"translator", "claude_api_key", "claude_model", "claude_effort"}
    _ASR_KEYS = {"model", "device", "language", "word_timestamps"}
    _OUT_KEYS = {"output_location", "output_dir", "save_source_srt", "save_bilingual_srt"}

    def _settings_changed(self, changed: set) -> None:
        if not changed or not self.video_path or self.external_srt:
            return
        if self.job_active:
            if changed & (self._TR_KEYS | self._ASR_KEYS | self._OUT_KEYS):
                q = "설정을 바꿨습니다.\n지금 만들고 있는 자막 작업에 바로 적용할까요? (지금까지 만든 자막은 유지됩니다)"
                if QMessageBox.question(self, APP_NAME, q) == QMessageBox.Yes:
                    self.start_job()
            return
        if changed & self._OUT_KEYS:
            self._rewrite_outputs()
        if changed & self._TR_KEYS and self.units and (self.language or "") != "ko" and self.settings.translator != "none":
            q = "번역 설정을 바꿨습니다.\n이 동영상의 자막을 새 설정으로 다시 번역할까요?"
            if QMessageBox.question(self, APP_NAME, q) == QMessageBox.Yes:
                self.start_job(retranslate=True)

    def _rewrite_outputs(self) -> None:
        """저장 위치/형식을 바꾸면 이미 만든 자막을 새 설정대로 다시 쓴다."""
        from ..jobstore import state_path_for, write_outputs

        state = load_state_for(self.video_path) if self.video_path else None
        if state is None or not state.units:
            return
        try:
            self.files = write_outputs(state, self.settings.job_options())
            state.save(state_path_for(self.video_path))
            self.statusBar().showMessage("새 저장 설정으로 자막 파일을 다시 저장했습니다.", 5000)
        except OSError as exc:
            QMessageBox.warning(self, APP_NAME, f"저장하지 못했습니다:\n{exc}")

    # ------------------------------------------------------------------
    # 상태 표시
    def _set_status(self, text: str) -> None:
        self.status_label.setText(text)

    def _set_progress(self, frac: Optional[float]) -> None:
        if frac is None:
            return
        self.progress.setVisible(True)
        self.progress.setValue(int(frac * 1000))
        self.progress.setFormat("%p%")

    def _update_progress(self) -> None:
        dur = self.duration_sec or (self.player.duration() / 1000.0)
        covered = sum(b - a for a, b in self.coverage)
        frac = min(1.0, covered / dur) if dur else 0.0
        if self.coverage or self.job_active:
            self._set_progress(frac)
        if not self.job_active:
            return
        untranslated = sum(1 for u in self.units.values() if not u.ko) if self._display_mode() == "ko" else 0
        parts = []
        if self.phase in ("transcribing", "translating") or self.coverage:
            parts.append(f"자막 만드는 중 {frac:.0%}")
            if self.speed:
                parts.append(f"{self.speed:.1f}배속")
            if untranslated and self.settings.translator != "none":
                parts.append(f"번역 대기 {untranslated}개")
            parts.append("자동 저장")
            self._set_status(" · ".join(parts))
        elif self.phase_text:
            self._set_status(self.phase_text)

    def _update_language_label(self, prob) -> None:
        if not self.language:
            self.lang_label.setText("")
            return
        text = f"원어: {language_name(self.language)}"
        if prob:
            text += f" ({float(prob):.0%})"
        self.lang_label.setText(text)

    def _update_engine_label(self) -> None:
        tr = {"google": "구글 번역", "claude": "Claude", "none": "번역 안 함"}.get(self.settings.translator, "")
        model = self.model_text or ("모델 자동" if self.settings.whisper_model == "auto" else self.settings.whisper_model)
        self.engine_label.setText(f"{model} · {tr}")

    # ------------------------------------------------------------------
    def open_subtitle_folder(self) -> None:
        if self.files:
            folder = os.path.dirname(self.files[0])
        elif self.external_srt:
            folder = os.path.dirname(self.external_srt)
        elif self.video_path:
            folder = str(output_dir_for(self.video_path, self.settings.job_options()))
        else:
            return
        QDesktopServices.openUrl(QUrl.fromLocalFile(folder))

    def show_shortcuts(self) -> None:
        QMessageBox.information(
            self,
            "단축키",
            "Space  재생/일시정지\n"
            "← / →  5초 이동     Ctrl+← / →  30초 이동\n"
            "A / D  이전/다음 자막으로\n"
            "↑ / ↓  볼륨     M  음소거\n"
            "[ / ]  재생 속도     Backspace  보통 속도\n"
            "F  전체 화면     Esc  전체 화면 끝내기\n"
            "C  자막 켜기/끄기     V  원문 함께 표시\n"
            "L  자막 목록     Ctrl + / -  자막 크기\n"
            "Ctrl+O  열기     Ctrl+G  자막 만들기\n\n"
            "탐색 막대의 초록색 = 자막이 만들어진 구간, 노란색 = 지금 만드는 구간",
        )

    def show_about(self) -> None:
        QMessageBox.about(
            self,
            f"{APP_NAME} 정보",
            f"<b>{APP_NAME}</b> {__version__}<br><br>"
            "재생하는 동영상의 음성을 인식해 한국어 자막을 만들고 자동으로 저장합니다.<br><br>"
            "음성 인식: faster-whisper (OpenAI Whisper)<br>"
            "번역: 구글 번역 또는 Claude (Anthropic)<br>"
            "재생: Qt Multimedia (FFmpeg)",
        )

    # ------------------------------------------------------------------
    def closeEvent(self, event) -> None:
        if self.isFullScreen():
            self._exit_fullscreen()
        if self.video_path:
            self.settings.remember_position(
                self.video_path, self.player.position() / 1000.0, self.player.duration() / 1000.0
            )
        self.settings.window_geometry = bytes(self.saveGeometry().toBase64()).decode()
        self.settings.window_state = bytes(self.saveState().toBase64()).decode()
        self.settings.save()
        self.player.stop()
        self._set_status("저장 중…")
        QApplication.processEvents()
        self.worker.stop(timeout=10)
        super().closeEvent(event)
