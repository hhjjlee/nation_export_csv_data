import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


@pytest.fixture(autouse=True)
def isolated_data_dir(tmp_path, monkeypatch):
    """테스트마다 별도의 앱 데이터 폴더를 쓴다."""
    data = tmp_path / "appdata"
    data.mkdir()
    monkeypatch.setenv("KOSUB_DATA_DIR", str(data))
    return data


def _ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if not exe:
        pytest.skip("ffmpeg가 필요합니다")
    return exe


def write_wav(path: Path, audio: np.ndarray, sr: int = 16000) -> Path:
    import wave

    pcm = (np.clip(audio, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())
    return path


def tone_pattern(seconds: float, on: float = 1.0, off: float = 1.0, sr: int = 16000, freq: float = 440.0) -> np.ndarray:
    """on초 소리, off초 무음이 반복되는 신호."""
    t = np.arange(int(seconds * sr)) / sr
    period = on + off
    gate = ((t % period) < on).astype(np.float32)
    return (0.3 * np.sin(2 * np.pi * freq * t) * gate).astype(np.float32)


@pytest.fixture
def make_video(tmp_path):
    """오디오 신호로 짧은 mp4(영상+AAC 음성)를 만든다."""

    def _make(audio: np.ndarray, name: str = "clip.mp4", sr: int = 16000) -> Path:
        ffmpeg = _ffmpeg()
        wav = write_wav(tmp_path / (name + ".wav"), audio, sr)
        out = tmp_path / name
        dur = len(audio) / sr
        subprocess.run(
            [
                ffmpeg, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", f"color=c=navy:size=160x90:rate=10:duration={dur:.3f}",
                "-i", str(wav), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-ar", "44100",
                "-shortest", str(out),
            ],
            check=True,
        )
        return out

    return _make
