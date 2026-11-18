"""동영상별 자막 작업 상태 저장(이어서 만들기)과 자막 파일 출력."""

from __future__ import annotations

import hashlib
import json
import logging
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

from .intervals import IntervalSet
from .paths import atomic_write_text, fallback_subtitle_dir, is_dir_writable, jobs_dir
from .subtitles import Unit, build_cues, cues_to_srt

log = logging.getLogger(__name__)

STATE_VERSION = 1
_SAMPLE = 1 << 20  # 1 MiB


def file_fingerprint(path: str) -> str:
    """파일 크기 + 앞/뒤 1MiB로 만든 지문. 파일 이름을 바꾸거나 옮겨도 같은 작업으로 인식한다."""
    size = os.path.getsize(path)
    h = hashlib.sha1(str(size).encode())
    with open(path, "rb") as fh:
        h.update(fh.read(_SAMPLE))
        if size > 2 * _SAMPLE:
            fh.seek(size - _SAMPLE)
            h.update(fh.read(_SAMPLE))
    return h.hexdigest()[:24]


def state_path_for(path: str) -> Path:
    return jobs_dir() / f"{file_fingerprint(path)}.json"


@dataclass
class JobState:
    video: str
    fingerprint: str
    duration: Optional[float] = None
    language: Optional[str] = None
    language_prob: float = 0.0
    language_locked: bool = False
    model: str = ""
    translator: str = ""
    units: Dict[int, Unit] = field(default_factory=dict)
    covered: IntervalSet = field(default_factory=IntervalSet)
    next_id: int = 1
    transcribed: bool = False
    complete: bool = False
    outputs: List[str] = field(default_factory=list)
    updated: float = 0.0

    # -- 직렬화 -----------------------------------------------------------
    def to_json(self) -> str:
        data = {
            "version": STATE_VERSION,
            "video": self.video,
            "fingerprint": self.fingerprint,
            "duration": self.duration,
            "language": self.language,
            "language_prob": round(self.language_prob, 4),
            "language_locked": self.language_locked,
            "model": self.model,
            "translator": self.translator,
            "next_id": self.next_id,
            "transcribed": self.transcribed,
            "complete": self.complete,
            "outputs": self.outputs,
            "updated": time.time(),
            "covered": self.covered.to_list(),
            "units": [u.to_dict() for u in sorted(self.units.values(), key=lambda u: (u.start, u.id))],
        }
        return json.dumps(data, ensure_ascii=False, indent=1)

    @classmethod
    def from_json(cls, text: str) -> "JobState":
        d = json.loads(text)
        units = {}
        for item in d.get("units", []):
            u = Unit.from_dict(item)
            units[u.id] = u
        st = cls(
            video=d.get("video", ""),
            fingerprint=d.get("fingerprint", ""),
            duration=d.get("duration"),
            language=d.get("language"),
            language_prob=float(d.get("language_prob") or 0.0),
            language_locked=bool(d.get("language_locked", False)),
            model=d.get("model", ""),
            translator=d.get("translator", ""),
            units=units,
            covered=IntervalSet(d.get("covered", [])),
            next_id=int(d.get("next_id") or (max(units) + 1 if units else 1)),
            transcribed=bool(d.get("transcribed", False)),
            complete=bool(d.get("complete", False)),
            outputs=list(d.get("outputs", [])),
            updated=float(d.get("updated") or 0.0),
        )
        st.next_id = max(st.next_id, max(units) + 1 if units else 1)
        return st

    @classmethod
    def load(cls, path: Path) -> Optional["JobState"]:
        try:
            return cls.from_json(Path(path).read_text("utf-8"))
        except FileNotFoundError:
            return None
        except Exception:
            log.exception("작업 상태 파일이 손상되어 무시합니다: %s", path)
            return None

    def save(self, path: Path) -> None:
        atomic_write_text(path, self.to_json())

    # -- 상태 -------------------------------------------------------------
    def untranslated(self) -> List[Unit]:
        return [u for u in self.units.values() if not (u.ko and u.ko.strip())]

    def progress(self) -> float:
        if not self.duration:
            return 1.0 if self.transcribed else 0.0
        return min(1.0, self.covered.total() / self.duration)


def load_state_for(video: str) -> Optional[JobState]:
    try:
        return JobState.load(state_path_for(video))
    except OSError:
        return None


def delete_state_for(video: str) -> None:
    try:
        state_path_for(video).unlink()
    except OSError:
        pass


# ---------------------------------------------------------------------------
# 자막 파일


def output_dir_for(video: str, options: Dict[str, Any]) -> Path:
    if options.get("output_location") == "custom" and options.get("output_dir"):
        folder = Path(options["output_dir"]).expanduser()
        try:
            folder.mkdir(parents=True, exist_ok=True)
        except OSError:
            pass
        if is_dir_writable(folder):
            return folder
    folder = Path(video).resolve().parent
    if is_dir_writable(folder):
        return folder
    return fallback_subtitle_dir()


def output_paths(video: str, options: Dict[str, Any], src_lang: Optional[str]) -> Dict[str, Optional[Path]]:
    folder = output_dir_for(video, options)
    stem = Path(video).stem
    lang = src_lang or "und"
    paths: Dict[str, Optional[Path]] = {"ko": folder / f"{stem}.ko.srt", "src": None, "both": None}
    if lang != "ko":
        paths["src"] = folder / f"{stem}.{lang}.srt"
        paths["both"] = folder / f"{stem}.ko+{lang}.srt"
    return paths


def existing_korean_srt(video: str, options: Dict[str, Any]) -> Optional[Path]:
    """이미 있는 한국어 자막 파일 (다른 곳에서 받은 자막 포함)."""
    candidates = [output_dir_for(video, options) / f"{Path(video).stem}.ko.srt"]
    candidates.append(Path(video).with_name(Path(video).stem + ".ko.srt"))
    for c in candidates:
        if c.is_file():
            return c
    return None


def write_outputs(state: JobState, options: Dict[str, Any]) -> List[str]:
    """작업 상태로부터 자막 파일을 쓴다. 쓴 파일 경로 목록을 돌려준다."""
    units = list(state.units.values())
    if not units:
        return []
    lang = state.language or "und"
    paths = output_paths(state.video, options, lang)
    translate = (options.get("translator") or "google") != "none"
    jobs: List[tuple] = []
    if lang == "ko":
        jobs.append((paths["ko"], "src"))
    else:
        if translate:
            jobs.append((paths["ko"], "ko"))
        if paths["src"] and (options.get("save_source_srt", True) or not translate):
            jobs.append((paths["src"], "src"))
        if paths["both"] and translate and options.get("save_bilingual_srt"):
            jobs.append((paths["both"], "both"))
    written: List[str] = []
    for path, mode in jobs:
        if path is None:
            continue
        cue_mode = "src" if mode == "src" else "ko"
        text = cues_to_srt(build_cues(units, mode=cue_mode), mode=mode, src_lang=lang)
        try:
            # BOM을 붙이면 오래된 Windows 플레이어도 UTF-8 한글을 제대로 읽는다
            atomic_write_text(path, text, encoding="utf-8-sig", newline="\r\n")
            written.append(str(path))
        except OSError:
            log.exception("자막 파일을 쓰지 못했습니다: %s", path)
    state.outputs = written
    return written
