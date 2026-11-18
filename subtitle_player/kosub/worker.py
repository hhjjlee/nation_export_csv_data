"""자막 생성 워커 프로세스.

GUI와 분리된 프로세스에서 음성 인식/번역을 돌린다.
- GPU 라이브러리 문제로 프로세스가 죽어도 플레이어는 살아 있고 CPU로 다시 시작할 수 있다.
- 무거운 계산이 영상 재생을 버벅이게 하지 않도록 우선순위를 낮춘다.

통신: 표준 입력/출력으로 한 줄에 하나씩 JSON
  GUI → 워커: {"cmd": "start" | "position" | "edit" | "cancel" | "shutdown", ...}
  워커 → GUI: {"ev": "...", "job": 작업번호, ...}
"""

from __future__ import annotations

import json
import logging
import os
import sys
import threading
from typing import Any, Dict, Optional

from . import __version__


def _protocol_stream():
    """원래 stdout은 프로토콜 전용으로 쓰고, 라이브러리가 찍는 출력은 stderr로 돌린다."""
    try:
        fd = os.dup(sys.stdout.fileno())
        os.dup2(sys.stderr.fileno(), sys.stdout.fileno())
        stream = os.fdopen(fd, "w", encoding="utf-8", buffering=1, newline="\n")
    except (AttributeError, OSError, ValueError):
        stream = sys.stdout
    sys.stdout = sys.stderr
    return stream


def _lower_priority() -> None:
    try:
        if hasattr(os, "nice"):
            os.nice(5)
    except OSError:
        pass


class Emitter:
    def __init__(self, stream) -> None:
        self._stream = stream
        self._lock = threading.Lock()
        self.broken = False

    def __call__(self, event: Dict[str, Any]) -> None:
        line = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
        with self._lock:
            if self.broken:
                return
            try:
                self._stream.write(line + "\n")
                self._stream.flush()
            except (BrokenPipeError, OSError, ValueError):
                self.broken = True


def main(argv: Optional[list] = None) -> int:
    proto = _protocol_stream()
    logging.basicConfig(
        level=logging.INFO, stream=sys.stderr, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
    )
    try:
        sys.stdin.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
    except (AttributeError, ValueError):
        pass
    _lower_priority()

    from .engine import SubtitleJob
    from .transcriber import ModelCache

    emit = Emitter(proto)
    cache = ModelCache()
    job: Optional[SubtitleJob] = None
    emit({"ev": "hello", "version": __version__, "pid": os.getpid()})

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        cmd = msg.get("cmd")
        if cmd == "start":
            previous = job
            if previous is not None:
                previous.cancel()
            job = SubtitleJob(
                msg["path"],
                msg.get("options") or {},
                emit,
                job_id=int(msg.get("job", 0)),
                position=float(msg.get("position") or 0.0),
                audio_track=int(msg.get("audio_track") or 0),
                retranslate=bool(msg.get("retranslate")),
                duration_hint=msg.get("duration"),
                model_cache=cache,
                previous=previous,
            )
            job.start()
        elif cmd == "position" and job is not None:
            job.set_position(float(msg.get("t") or 0.0))
        elif cmd == "edit" and job is not None:
            job.edit(int(msg["id"]), str(msg.get("text") or ""))
        elif cmd == "cancel" and job is not None:
            job.cancel()
        elif cmd == "ping":
            emit({"ev": "pong"})
        elif cmd == "shutdown":
            break
    if job is not None:
        job.cancel()
        job.join(15)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
