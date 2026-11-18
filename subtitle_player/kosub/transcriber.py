"""faster-whisper 음성 인식 래퍼: 모델 다운로드(진행률), GPU 자동 감지, 구간 인식."""

from __future__ import annotations

import ctypes
import glob
import logging
import os
import sys
import threading
from pathlib import Path
from typing import Callable, Iterator, List, Optional, Tuple

import numpy as np

from .paths import models_dir
from .segmentation import Segment, Word

log = logging.getLogger(__name__)

# 이름: (Hugging Face 저장소, 대략적인 크기 MB, 설명)
MODEL_INFO = {
    "tiny": ("Systran/faster-whisper-tiny", 75, "가장 빠름 · 정확도 낮음"),
    "base": ("Systran/faster-whisper-base", 145, "빠름 · 정확도 보통"),
    "small": ("Systran/faster-whisper-small", 484, "CPU 권장 · 속도/정확도 균형"),
    "medium": ("Systran/faster-whisper-medium", 1530, "정확 · CPU에서는 느림"),
    "large-v3-turbo": ("mobiuslabsgmbh/faster-whisper-large-v3-turbo", 1620, "GPU 권장 · 매우 정확"),
    "large-v3": ("Systran/faster-whisper-large-v3", 3090, "최고 정확도 · 가장 느림"),
}

ProgressCallback = Callable[[int, int], None]
StatusCallback = Callable[[str, str], None]


class ModelDownloadError(RuntimeError):
    pass


def resolve_model_name(name: str, device: str) -> str:
    if not name or name == "auto":
        return "large-v3-turbo" if device == "cuda" else "small"
    return name


def default_cpu_threads() -> int:
    # 영상 재생용으로 코어 하나는 남겨 둔다
    return max(2, min(8, (os.cpu_count() or 4) - 1))


# ---------------------------------------------------------------------------
# GPU(CUDA) 감지


def _add_pip_nvidia_libs() -> None:
    """pip로 설치한 nvidia-cublas-cu12 / nvidia-cudnn-cu12 라이브러리를 찾을 수 있게 한다."""
    try:
        import nvidia  # type: ignore
    except ImportError:
        return
    roots = [Path(p) for p in getattr(nvidia, "__path__", [])]
    for root in roots:
        for sub in ("cublas", "cudnn", "cuda_runtime", "cuda_nvrtc"):
            if sys.platform == "win32":
                folder = root / sub / "bin"
                if folder.is_dir():
                    try:
                        os.add_dll_directory(str(folder))
                    except (AttributeError, OSError):
                        pass
                    os.environ["PATH"] = str(folder) + os.pathsep + os.environ.get("PATH", "")
            else:
                folder = root / sub / "lib"
                for lib in sorted(glob.glob(str(folder / "lib*.so*"))):
                    try:
                        ctypes.CDLL(lib, mode=getattr(ctypes, "RTLD_GLOBAL", 0))
                    except OSError:
                        pass


def _can_load(name: str) -> bool:
    try:
        if sys.platform == "win32":
            ctypes.WinDLL(name, winmode=0)  # PATH까지 검색
        else:
            ctypes.CDLL(name)
        return True
    except OSError:
        return False


def cuda_status() -> Tuple[bool, str]:
    """(사용 가능 여부, 설명)"""
    if os.environ.get("KOSUB_FORCE_CPU"):
        return False, "CPU 강제 사용"
    try:
        import ctranslate2

        count = ctranslate2.get_cuda_device_count()
    except Exception as exc:  # pragma: no cover - 환경 의존
        return False, f"CUDA 확인 실패: {exc}"
    if count <= 0:
        return False, "NVIDIA GPU 없음"
    _add_pip_nvidia_libs()
    if sys.platform == "win32":
        needed = ["cublas64_12.dll", "cudnn_ops64_9.dll"]
    else:
        needed = ["libcublas.so.12", "libcudnn_ops.so.9"]
    missing = [n for n in needed if not _can_load(n)]
    if missing:
        return False, "GPU 라이브러리 없음: " + ", ".join(missing)
    return True, f"NVIDIA GPU {count}개"


def _pick_compute_type(device: str, requested: str = "auto") -> str:
    if requested and requested != "auto":
        return requested
    try:
        import ctranslate2

        supported = set(ctranslate2.get_supported_compute_types(device))
    except Exception:
        supported = set()
    prefs = ["float16", "int8_float16", "int8_float32", "float32"] if device == "cuda" else ["int8", "int8_float32", "float32"]
    for ct in prefs:
        if not supported or ct in supported:
            return ct
    return "default"


# ---------------------------------------------------------------------------


def _folder_size(folder: Path) -> int:
    total = 0
    if not folder.exists():
        return 0
    for root, _dirs, files in os.walk(folder):
        for f in files:
            fp = os.path.join(root, f)
            try:
                if not os.path.islink(fp):
                    total += os.path.getsize(fp)
            except OSError:
                pass
    return total


class Transcriber:
    def __init__(self, model: str = "auto", device: str = "auto", compute_type: str = "auto", cpu_threads: int = 0):
        self.requested_model = model or "auto"
        self.requested_device = device or "auto"
        self.requested_compute = compute_type or "auto"
        self.cpu_threads = cpu_threads or default_cpu_threads()
        self.device = "cpu"
        self.model_name = ""
        self.compute_type = ""
        self.device_note = ""
        self.model = None

    @property
    def key(self) -> Tuple[str, str, str]:
        return (self.requested_model, self.requested_device, self.requested_compute)

    # -- 모델 준비 ---------------------------------------------------------
    def load(self, status: Optional[StatusCallback] = None, progress: Optional[ProgressCallback] = None) -> None:
        status = status or (lambda phase, text: None)
        if self.requested_device == "cuda":
            ok, note = cuda_status()
            self.device = "cuda"
            self.device_note = note
            if not ok:
                log.warning("GPU를 쓸 수 없을 수 있음: %s", note)
        elif self.requested_device == "auto":
            ok, note = cuda_status()
            self.device = "cuda" if ok else "cpu"
            self.device_note = note
        else:
            self.device = "cpu"
            self.device_note = "CPU"
        self.model_name = resolve_model_name(self.requested_model, self.device)
        self.compute_type = _pick_compute_type(self.device, self.requested_compute)

        path = self._ensure_model(self.model_name, status, progress)
        status("model_load", f"음성 인식 모델 불러오는 중 ({self.model_name}, {self.device.upper()})")
        from faster_whisper import WhisperModel

        try:
            self.model = WhisperModel(
                path, device=self.device, compute_type=self.compute_type, cpu_threads=self.cpu_threads
            )
        except ValueError:
            # 지원하지 않는 연산 형식이면 기본값으로 재시도
            self.compute_type = "default"
            self.model = WhisperModel(path, device=self.device, compute_type="default", cpu_threads=self.cpu_threads)
        if self.device == "cuda":
            # cuDNN이 없으면 여기서 프로세스가 죽을 수 있다 → GUI가 감지해 CPU로 다시 시작한다
            status("warmup", "GPU 초기화 확인 중")
            noise = (np.random.default_rng(0).standard_normal(16000) * 0.01).astype(np.float32)
            self.model.detect_language(audio=noise)

    def _ensure_model(self, name: str, status: StatusCallback, progress: Optional[ProgressCallback]) -> str:
        if os.path.isdir(name):
            return name  # 직접 지정한 모델 폴더
        from faster_whisper.utils import download_model

        cache = str(models_dir())
        try:
            return download_model(name, local_files_only=True, cache_dir=cache)
        except Exception:
            pass
        repo, size_mb, _desc = MODEL_INFO.get(name, (name, 0, ""))
        status("download", f"음성 인식 모델 내려받는 중 ({name}, 약 {size_mb} MB) — 처음 한 번만")
        result: dict = {}
        done = threading.Event()

        def run() -> None:
            try:
                result["path"] = download_model(name, cache_dir=cache)
            except BaseException as exc:  # noqa: BLE001 - 스레드 밖으로 전달
                result["error"] = exc
            finally:
                done.set()

        threading.Thread(target=run, name="model-download", daemon=True).start()
        folder = Path(cache) / ("models--" + repo.replace("/", "--"))
        total = size_mb * 1024 * 1024
        while not done.wait(0.5):
            if progress:
                progress(_folder_size(folder), total)
        if "error" in result:
            raise ModelDownloadError(
                f"음성 인식 모델({name})을 내려받지 못했습니다. 인터넷 연결(huggingface.co)을 확인하세요.\n{result['error']}"
            )
        if progress:
            progress(total, total)
        return result["path"]

    # -- 인식 -------------------------------------------------------------
    def transcribe(
        self,
        audio: np.ndarray,
        language: Optional[str] = None,
        word_timestamps: bool = True,
        beam_size: int = 5,
    ) -> Tuple[Iterator[Segment], Tuple[Optional[str], float]]:
        """audio(16 kHz) 를 인식한다. 시간은 audio 시작 기준.

        반환: (세그먼트 반복자, (언어 코드, 언어 확률))
        """
        if self.model is None:
            raise RuntimeError("model not loaded")
        kwargs = dict(
            language=language,
            beam_size=beam_size,
            vad_filter=True,
            vad_parameters={"min_silence_duration_ms": 1000},
            word_timestamps=word_timestamps,
            condition_on_previous_text=False,
            language_detection_segments=3,
        )
        if word_timestamps:
            kwargs["hallucination_silence_threshold"] = 2.0
        segments, info = self.model.transcribe(audio, **kwargs)

        def generate() -> Iterator[Segment]:
            for s in segments:
                words: Optional[List[Word]] = None
                if s.words:
                    words = [Word(float(w.start), float(w.end), w.word) for w in s.words]
                yield Segment(
                    start=float(s.start),
                    end=float(s.end),
                    text=s.text,
                    words=words,
                    no_speech_prob=float(s.no_speech_prob),
                    avg_logprob=float(s.avg_logprob),
                )

        return generate(), (info.language, float(info.language_probability))


class ModelCache:
    """워커 프로세스 안에서 불러온 모델을 동영상이 바뀌어도 재사용한다."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._current: Optional[Transcriber] = None

    def get(self, options: dict, status: Optional[StatusCallback] = None, progress: Optional[ProgressCallback] = None):
        if os.environ.get("KOSUB_FAKE"):
            from .fakes import FakeTranscriber

            return FakeTranscriber()
        key = (options.get("model") or "auto", options.get("device") or "auto", options.get("compute_type") or "auto")
        with self._lock:
            if self._current is not None and self._current.key == key:
                return self._current
            self._current = None  # 이전 모델 메모리 해제
            import gc

            gc.collect()
            t = Transcriber(*key)
            t.load(status, progress)
            self._current = t
            return t
