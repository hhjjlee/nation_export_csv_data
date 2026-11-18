"""앱 데이터 폴더와 파일 쓰기 도우미 (표준 라이브러리만 사용)."""

from __future__ import annotations

import os
import sys
import tempfile
import time
from pathlib import Path
from typing import Union

from . import APP_ID

PathLike = Union[str, "os.PathLike[str]"]


def data_dir() -> Path:
    """설정·모델·작업 상태를 보관하는 앱 데이터 폴더."""
    override = os.environ.get("KOSUB_DATA_DIR")
    if override:
        base = Path(override)
    elif sys.platform == "win32":
        base = Path(os.environ.get("APPDATA") or (Path.home() / "AppData" / "Roaming")) / APP_ID
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support" / APP_ID
    else:
        base = Path(os.environ.get("XDG_DATA_HOME") or (Path.home() / ".local" / "share")) / APP_ID
    base.mkdir(parents=True, exist_ok=True)
    return base


def _subdir(name: str) -> Path:
    path = data_dir() / name
    path.mkdir(parents=True, exist_ok=True)
    return path


def models_dir() -> Path:
    return _subdir("models")


def jobs_dir() -> Path:
    return _subdir("jobs")


def logs_dir() -> Path:
    return _subdir("logs")


def fallback_subtitle_dir() -> Path:
    """동영상 폴더에 쓸 수 없을 때 자막을 저장하는 폴더."""
    return _subdir("subtitles")


def settings_path() -> Path:
    return data_dir() / "settings.json"


def is_dir_writable(path: PathLike) -> bool:
    """실제로 임시 파일을 만들어 보고 쓰기 가능 여부를 판단한다 (Windows의 os.access는 부정확)."""
    try:
        folder = Path(path)
        if not folder.is_dir():
            return False
        fd, tmp = tempfile.mkstemp(prefix=".kosub_", dir=str(folder))
        os.close(fd)
        os.remove(tmp)
        return True
    except OSError:
        return False


def atomic_write_text(path: PathLike, text: str, encoding: str = "utf-8", newline: str = "\n") -> None:
    """임시 파일에 쓴 뒤 교체해서, 쓰는 도중에 꺼져도 파일이 깨지지 않게 한다."""
    target = Path(path)
    tmp = target.with_name(f".{target.name}.{os.getpid()}.tmp")
    with open(tmp, "w", encoding=encoding, newline=newline) as fh:
        fh.write(text)
    # 다른 프로그램(플레이어 등)이 파일을 잠깐 열고 있으면 Windows에서 교체가 실패할 수 있다.
    for attempt in range(5):
        try:
            os.replace(tmp, target)
            return
        except PermissionError:
            time.sleep(0.2 * (attempt + 1))
    try:
        with open(target, "w", encoding=encoding, newline=newline) as fh:
            fh.write(text)
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass
