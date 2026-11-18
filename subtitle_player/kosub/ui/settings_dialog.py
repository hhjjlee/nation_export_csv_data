"""환경설정 대화상자."""

from __future__ import annotations

from PySide6.QtCore import Qt, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QRadioButton,
    QSlider,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from ..config import CLAUDE_EFFORTS, CLAUDE_MODELS, Settings
from ..languages import COMMON_LANGUAGES, WHISPER_LANGUAGES
from ..paths import data_dir
from ..transcriber import MODEL_INFO


def _note(text: str) -> QLabel:
    label = QLabel(text)
    label.setWordWrap(True)
    label.setStyleSheet("color: #9a9a9a; font-size: 11px;")
    label.setTextInteractionFlags(Qt.TextBrowserInteraction)
    label.setOpenExternalLinks(True)
    return label


class SettingsDialog(QDialog):
    def __init__(self, settings: Settings, parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle("환경설정")
        self.setMinimumWidth(560)
        self.settings = settings
        tabs = QTabWidget()
        tabs.addTab(self._asr_tab(), "음성 인식")
        tabs.addTab(self._translate_tab(), "번역")
        tabs.addTab(self._save_tab(), "저장")
        tabs.addTab(self._display_tab(), "재생/표시")
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("확인")
        buttons.button(QDialogButtonBox.Cancel).setText("취소")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout = QVBoxLayout(self)
        layout.addWidget(tabs)
        layout.addWidget(buttons)
        self._load()

    # -- 탭 구성 -----------------------------------------------------------
    def _asr_tab(self) -> QWidget:
        w = QWidget()
        form = QFormLayout(w)
        self.model_combo = QComboBox()
        self.model_combo.addItem("자동 (GPU: large-v3-turbo, CPU: small)", "auto")
        for name, (_repo, size, desc) in MODEL_INFO.items():
            self.model_combo.addItem(f"{name} — {desc} (약 {size:,} MB)", name)
        form.addRow("인식 모델", self.model_combo)
        form.addRow("", _note("큰 모델일수록 정확하지만 느립니다. 모델은 처음 사용할 때 한 번만 내려받습니다."))
        self.device_combo = QComboBox()
        self.device_combo.addItem("자동 (NVIDIA GPU가 있으면 사용)", "auto")
        self.device_combo.addItem("CPU", "cpu")
        self.device_combo.addItem("GPU (CUDA)", "cuda")
        form.addRow("처리 장치", self.device_combo)
        form.addRow(
            "",
            _note("GPU를 쓰려면 NVIDIA 드라이버와 CUDA 12용 cuBLAS·cuDNN 9가 필요합니다 "
                  "(pip install nvidia-cublas-cu12 nvidia-cudnn-cu12). GPU 초기화에 실패하면 자동으로 CPU로 전환합니다."),
        )
        self.lang_combo = QComboBox()
        self.lang_combo.addItem("자동 감지", "auto")
        seen = set()
        for code in COMMON_LANGUAGES:
            self.lang_combo.addItem(f"{WHISPER_LANGUAGES[code]} ({code})", code)
            seen.add(code)
        self.lang_combo.insertSeparator(self.lang_combo.count())
        for code, name in sorted(WHISPER_LANGUAGES.items(), key=lambda kv: kv[1]):
            if code not in seen:
                self.lang_combo.addItem(f"{name} ({code})", code)
        form.addRow("원본 언어", self.lang_combo)
        self.word_ts = QCheckBox("단어 단위로 자막 타이밍 맞추기 (권장, 약간 느려짐)")
        form.addRow("", self.word_ts)
        return w

    def _translate_tab(self) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        self.tr_google = QRadioButton("구글 번역 — 무료, API 키 불필요 (인터넷 필요)")
        self.tr_claude = QRadioButton("Claude AI — 문맥을 고려한 자연스러운 번역 (API 키 필요, 사용량 과금)")
        self.tr_none = QRadioButton("번역 안 함 — 원래 언어 자막만 만들기")
        for rb in (self.tr_google, self.tr_claude, self.tr_none):
            v.addWidget(rb)
        box = QGroupBox("Claude 설정")
        form = QFormLayout(box)
        key_row = QHBoxLayout()
        self.api_key = QLineEdit()
        self.api_key.setEchoMode(QLineEdit.Password)
        self.api_key.setPlaceholderText("sk-ant-… (비워 두면 ANTHROPIC_API_KEY 환경 변수 사용)")
        show = QPushButton("보기")
        show.setCheckable(True)
        show.toggled.connect(lambda on: self.api_key.setEchoMode(QLineEdit.Normal if on else QLineEdit.Password))
        key_row.addWidget(self.api_key, 1)
        key_row.addWidget(show)
        form.addRow("API 키", key_row)
        self.claude_model = QComboBox()
        for mid, label in CLAUDE_MODELS:
            self.claude_model.addItem(label, mid)
        form.addRow("모델", self.claude_model)
        self.claude_effort = QComboBox()
        for eid, label in CLAUDE_EFFORTS:
            self.claude_effort.addItem(label, eid)
        form.addRow("번역 방식", self.claude_effort)
        form.addRow(
            "",
            _note('API 키는 <a href="https://console.anthropic.com/">console.anthropic.com</a>에서 발급합니다. '
                  "키는 이 컴퓨터의 설정 파일에만 저장됩니다."),
        )
        v.addWidget(box)
        v.addStretch(1)
        self.tr_claude.toggled.connect(box.setEnabled)
        return w

    def _save_tab(self) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        self.save_video = QRadioButton("동영상과 같은 폴더 (다른 플레이어에서도 자동으로 인식)")
        self.save_custom = QRadioButton("지정한 폴더")
        v.addWidget(self.save_video)
        row = QHBoxLayout()
        row.addWidget(self.save_custom)
        self.out_dir = QLineEdit()
        browse = QPushButton("찾아보기…")
        browse.clicked.connect(self._browse)
        row.addWidget(self.out_dir, 1)
        row.addWidget(browse)
        v.addLayout(row)
        v.addWidget(_note("동영상 폴더에 쓸 수 없으면 앱 데이터 폴더의 subtitles 폴더에 저장합니다. "
                          "파일 이름: 영상이름.ko.srt (한국어), 영상이름.en.srt (원어)"))
        self.save_src = QCheckBox("원어 자막도 저장 (예: 영상이름.en.srt)")
        self.save_bi = QCheckBox("이중 자막도 저장 (한국어 + 원문, 예: 영상이름.ko+en.srt)")
        v.addWidget(self.save_src)
        v.addWidget(self.save_bi)
        open_data = QPushButton("앱 데이터 폴더 열기 (모델·작업 기록)")
        open_data.clicked.connect(lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(data_dir()))))
        v.addWidget(open_data, 0, Qt.AlignLeft)
        v.addStretch(1)
        self.save_custom.toggled.connect(self.out_dir.setEnabled)
        self.save_custom.toggled.connect(browse.setEnabled)
        return w

    def _display_tab(self) -> QWidget:
        w = QWidget()
        form = QFormLayout(w)
        self.auto_gen = QCheckBox("동영상을 열면 자동으로 자막 만들기")
        self.resume = QCheckBox("마지막으로 보던 위치부터 이어서 재생")
        self.show_src = QCheckBox("화면에 원문도 함께 표시")
        self.sub_bg = QCheckBox("자막 뒤에 반투명 배경")
        form.addRow(self.auto_gen)
        form.addRow(self.resume)
        form.addRow(self.show_src)
        form.addRow(self.sub_bg)
        self.scale = QSlider(Qt.Horizontal)
        self.scale.setRange(60, 200)
        self.scale_label = QLabel()
        self.scale.valueChanged.connect(lambda v: self.scale_label.setText(f"{v}%"))
        row = QHBoxLayout()
        row.addWidget(self.scale, 1)
        row.addWidget(self.scale_label)
        form.addRow("자막 크기", row)
        return w

    def _browse(self) -> None:
        folder = QFileDialog.getExistingDirectory(self, "자막 저장 폴더", self.out_dir.text() or "")
        if folder:
            self.out_dir.setText(folder)
            self.save_custom.setChecked(True)

    # -- 값 읽기/쓰기 --------------------------------------------------------
    @staticmethod
    def _select(combo: QComboBox, value) -> None:
        idx = combo.findData(value)
        if idx < 0 and isinstance(value, str) and value:
            combo.addItem(value, value)  # 직접 지정한 모델 경로 등
            idx = combo.count() - 1
        combo.setCurrentIndex(max(0, idx))

    def _load(self) -> None:
        s = self.settings
        self._select(self.model_combo, s.whisper_model)
        self._select(self.device_combo, s.device)
        self._select(self.lang_combo, s.source_language)
        self.word_ts.setChecked(s.word_timestamps)
        {"claude": self.tr_claude, "none": self.tr_none}.get(s.translator, self.tr_google).setChecked(True)
        self.tr_claude.toggled.emit(self.tr_claude.isChecked())
        self.api_key.setText(s.claude_api_key)
        self._select(self.claude_model, s.claude_model)
        self._select(self.claude_effort, s.claude_effort)
        (self.save_custom if s.output_location == "custom" else self.save_video).setChecked(True)
        self.save_custom.toggled.emit(self.save_custom.isChecked())
        self.out_dir.setText(s.output_dir)
        self.save_src.setChecked(s.save_source_srt)
        self.save_bi.setChecked(s.save_bilingual_srt)
        self.auto_gen.setChecked(s.auto_generate)
        self.resume.setChecked(s.resume_playback)
        self.show_src.setChecked(s.show_source_text)
        self.sub_bg.setChecked(s.subtitle_background)
        self.scale.setValue(int(round(s.subtitle_scale * 100)))
        self.scale_label.setText(f"{self.scale.value()}%")

    def apply(self) -> None:
        s = self.settings
        old_device = s.device
        s.whisper_model = self.model_combo.currentData()
        s.device = self.device_combo.currentData()
        if s.device != old_device:
            s.cuda_failed = False  # 장치를 바꾸면 GPU를 다시 시도
        s.source_language = self.lang_combo.currentData() or "auto"
        s.word_timestamps = self.word_ts.isChecked()
        s.translator = "claude" if self.tr_claude.isChecked() else ("none" if self.tr_none.isChecked() else "google")
        s.claude_api_key = self.api_key.text().strip()
        s.claude_model = self.claude_model.currentData()
        s.claude_effort = self.claude_effort.currentData()
        s.output_location = "custom" if (self.save_custom.isChecked() and self.out_dir.text().strip()) else "video"
        s.output_dir = self.out_dir.text().strip()
        s.save_source_srt = self.save_src.isChecked()
        s.save_bilingual_srt = self.save_bi.isChecked()
        s.auto_generate = self.auto_gen.isChecked()
        s.resume_playback = self.resume.isChecked()
        s.show_source_text = self.show_src.isChecked()
        s.subtitle_background = self.sub_bg.isChecked()
        s.subtitle_scale = self.scale.value() / 100.0
