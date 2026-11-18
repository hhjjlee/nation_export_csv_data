"""GUI 쪽에서 워커 프로세스를 띄우고 JSON 메시지를 주고받는다."""

from __future__ import annotations

import collections
import json
import logging
import os
import subprocess
import sys
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

from PySide6.QtCore import QObject, Signal

from ..paths import logs_dir

log = logging.getLogger(__name__)

_CREATE_NO_WINDOW = 0x08000000
_BELOW_NORMAL_PRIORITY_CLASS = 0x00004000


def worker_command() -> List[str]:
    if getattr(sys, "frozen", False):  # PyInstaller 등으로 묶은 실행 파일
        return [sys.executable, "--worker"]
    main_py = Path(__file__).resolve().parents[2] / "main.py"
    return [sys.executable, "-u", str(main_py), "--worker"]


class WorkerClient(QObject):
    event = Signal(dict)
    crashed = Signal(int, str)  # 종료 코드, stderr 마지막 부분

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._proc: Optional[subprocess.Popen] = None
        self._lock = threading.Lock()
        self._tail: "collections.deque[str]" = collections.deque(maxlen=80)
        self._expected_exit = False
        self._log_path = logs_dir() / "worker.log"
        self._rotate_log()

    def _rotate_log(self) -> None:
        try:
            if self._log_path.exists() and self._log_path.stat().st_size > 5 * 1024 * 1024:
                self._log_path.replace(self._log_path.with_suffix(".old.log"))
        except OSError:
            pass

    def is_running(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def start(self) -> None:
        if self.is_running():
            return
        env = dict(os.environ)
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUTF8"] = "1"
        kwargs: Dict[str, Any] = {}
        if sys.platform == "win32":
            kwargs["creationflags"] = _CREATE_NO_WINDOW | _BELOW_NORMAL_PRIORITY_CLASS
        cmd = worker_command()
        log.info("워커 시작: %s", cmd)
        self._expected_exit = False
        proc = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
            cwd=str(Path(cmd[-2]).parent) if not getattr(sys, "frozen", False) else None,
            **kwargs,
        )
        self._proc = proc
        threading.Thread(target=self._read_stdout, args=(proc,), name="worker-out", daemon=True).start()
        threading.Thread(target=self._read_stderr, args=(proc,), name="worker-err", daemon=True).start()
        threading.Thread(target=self._wait, args=(proc,), name="worker-wait", daemon=True).start()

    def send(self, message: Dict[str, Any]) -> bool:
        with self._lock:
            if not self.is_running():
                self.start()
            proc = self._proc
            try:
                data = (json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8")
                proc.stdin.write(data)
                proc.stdin.flush()
                return True
            except (BrokenPipeError, OSError, ValueError, AttributeError):
                log.warning("워커에 메시지를 보내지 못했습니다: %s", message.get("cmd"))
                return False

    def stop(self, timeout: float = 10.0) -> None:
        proc = self._proc
        if proc is None or proc.poll() is not None:
            return
        self._expected_exit = True
        try:
            proc.stdin.write(b'{"cmd":"shutdown"}\n')
            proc.stdin.flush()
            proc.stdin.close()
        except (BrokenPipeError, OSError, ValueError):
            pass
        try:
            proc.wait(timeout)
        except subprocess.TimeoutExpired:
            proc.kill()

    def kill(self) -> None:
        proc = self._proc
        if proc is not None and proc.poll() is None:
            self._expected_exit = True
            proc.kill()

    def stderr_tail(self) -> str:
        return "".join(self._tail)

    # -- 스레드 -----------------------------------------------------------
    def _read_stdout(self, proc: subprocess.Popen) -> None:
        for raw in iter(proc.stdout.readline, b""):
            try:
                ev = json.loads(raw.decode("utf-8", "replace"))
            except ValueError:
                continue
            if isinstance(ev, dict):
                self.event.emit(ev)

    def _read_stderr(self, proc: subprocess.Popen) -> None:
        try:
            fh = open(self._log_path, "a", encoding="utf-8")
        except OSError:
            fh = None
        for raw in iter(proc.stderr.readline, b""):
            line = raw.decode("utf-8", "replace")
            self._tail.append(line)
            if fh:
                try:
                    fh.write(line)
                    fh.flush()
                except OSError:
                    pass
        if fh:
            fh.close()

    def _wait(self, proc: subprocess.Popen) -> None:
        code = proc.wait()
        if proc is self._proc and not self._expected_exit:
            log.error("워커가 예기치 않게 종료됨 (코드 %s)", code)
            self.crashed.emit(int(code if code is not None else -1), self.stderr_tail())
