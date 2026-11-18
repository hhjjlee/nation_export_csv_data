"""사용자 설정 (JSON으로 앱 데이터 폴더에 저장)."""

from __future__ import annotations

import json
import logging
import os
from dataclasses import asdict, dataclass, field, fields
from pathlib import Path
from typing import Any, Dict, List, Optional

from .paths import atomic_write_text, settings_path

log = logging.getLogger(__name__)

CLAUDE_MODELS = [
    ("claude-opus-5-5", "Claude Opus 5.5 (최고 품질)"),
    ("claude-sonnet-5-5", "Claude Sonnet 5.5 (빠르고 저렴)"),
    ("claude-haiku-5-5", "Claude Haiku 5.5 (가장 저렴)"),
]
CLAUDE_EFFORTS = [
    ("low", "빠르게 (권장)"),
    ("medium", "균형"),
    ("high", "꼼꼼하게 (느림)"),
]


@dataclass
class Settings:
    # 음성 인식
    whisper_model: str = "auto"  # auto | tiny | base | small | medium | large-v3-turbo | large-v3 | 경로
    device: str = "auto"  # auto | cpu | cuda
    source_language: str = "auto"
    word_timestamps: bool = True
    # 번역
    translator: str = "google"  # google | claude | none
    claude_api_key: str = ""
    claude_model: str = "claude-opus-5-5"
    claude_effort: str = "low"
    # 저장
    output_location: str = "video"  # video | custom
    output_dir: str = ""
    save_source_srt: bool = True
    save_bilingual_srt: bool = False
    # 재생 / 표시
    auto_generate: bool = True
    resume_playback: bool = True
    show_subtitles: bool = True
    show_source_text: bool = False
    subtitle_scale: float = 1.0
    subtitle_background: bool = False
    volume: int = 80
    muted: bool = False
    playback_rate: float = 1.0
    panel_visible: bool = True
    window_geometry: str = ""
    window_state: str = ""
    last_dir: str = ""
    recent_files: List[str] = field(default_factory=list)
    positions: Dict[str, float] = field(default_factory=dict)
    # GPU 초기화가 실패한 적이 있으면 '자동'일 때 CPU를 쓴다.
    cuda_failed: bool = False

    @classmethod
    def load(cls, path: Optional[Path] = None) -> "Settings":
        path = Path(path or settings_path())
        try:
            data = json.loads(path.read_text("utf-8"))
        except FileNotFoundError:
            return cls()
        except Exception:  # 손상된 설정 파일은 무시하고 기본값으로 시작
            log.exception("설정 파일을 읽지 못했습니다: %s", path)
            return cls()
        if not isinstance(data, dict):
            return cls()
        defaults = cls()
        kwargs: Dict[str, Any] = {}
        for f in fields(cls):
            if f.name not in data:
                continue
            value = data[f.name]
            default = getattr(defaults, f.name)
            if isinstance(default, bool):
                ok = isinstance(value, bool)
            elif isinstance(default, float):
                ok = isinstance(value, (int, float)) and not isinstance(value, bool)
                value = float(value) if ok else value
            elif isinstance(default, int):
                ok = isinstance(value, int) and not isinstance(value, bool)
            else:
                ok = isinstance(value, type(default))
            if ok:
                kwargs[f.name] = value
        return cls(**kwargs)

    def save(self, path: Optional[Path] = None) -> None:
        path = Path(path or settings_path())
        try:
            atomic_write_text(path, json.dumps(asdict(self), ensure_ascii=False, indent=2))
        except OSError:
            log.exception("설정을 저장하지 못했습니다")

    def add_recent(self, video: str, limit: int = 12) -> None:
        video = os.path.abspath(video)
        items = [p for p in self.recent_files if os.path.normcase(p) != os.path.normcase(video)]
        self.recent_files = [video] + items[: limit - 1]

    def remember_position(self, video: str, seconds: float, duration: float) -> None:
        key = os.path.abspath(video)
        if seconds > 10 and (duration <= 0 or seconds < duration - 15):
            self.positions[key] = round(seconds, 1)
        else:
            self.positions.pop(key, None)
        if len(self.positions) > 300:
            for old in list(self.positions)[: len(self.positions) - 300]:
                self.positions.pop(old, None)

    def job_options(self) -> Dict[str, Any]:
        """자막 생성 작업(워커 프로세스)에 넘기는 설정."""
        return {
            "model": self.whisper_model,
            "device": "cpu" if (self.device == "auto" and self.cuda_failed) else self.device,
            "language": self.source_language,
            "word_timestamps": self.word_timestamps,
            "translator": self.translator,
            "claude_api_key": self.claude_api_key,
            "claude_model": self.claude_model,
            "claude_effort": self.claude_effort,
            "output_location": self.output_location,
            "output_dir": self.output_dir,
            "save_source_srt": self.save_source_srt,
            "save_bilingual_srt": self.save_bilingual_srt,
        }


DEFAULT_JOB_OPTIONS: Dict[str, Any] = Settings().job_options()
