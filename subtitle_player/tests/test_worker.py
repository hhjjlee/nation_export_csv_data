"""워커 프로세스를 실제로 띄워 JSON 프로토콜을 확인한다."""

import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

from conftest import ROOT, tone_pattern


def test_worker_protocol_end_to_end(make_video, isolated_data_dir):
    video = make_video(tone_pattern(15.0, on=2.5, off=0.5), "proto.mp4")
    env = dict(os.environ, KOSUB_FAKE="1", KOSUB_FAKE_SPEED="300", KOSUB_DATA_DIR=str(isolated_data_dir))
    proc = subprocess.Popen(
        [sys.executable, str(ROOT / "main.py"), "--worker"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        env=env,
    )
    events = []
    stderr_lines = []

    def read_out():
        for line in proc.stdout:
            events.append(json.loads(line))

    def read_err():
        for line in proc.stderr:
            stderr_lines.append(line)

    threading.Thread(target=read_out, daemon=True).start()
    threading.Thread(target=read_err, daemon=True).start()
    try:
        proc.stdin.write(json.dumps({"cmd": "start", "job": 7, "path": str(video), "options": {"translator": "google"}}) + "\n")
        proc.stdin.flush()
        deadline = time.time() + 60
        while time.time() < deadline and not any(e.get("ev") == "finished" for e in events):
            time.sleep(0.05)
        kinds = [e["ev"] for e in events]
        assert kinds[0] == "hello"
        assert "state" in kinds and "units" in kinds and "done" in kinds, (kinds, stderr_lines[-10:])
        assert all(e.get("job") == 7 for e in events if e["ev"] not in ("hello", "pong"))
        done = [e for e in events if e["ev"] == "done"][-1]
        assert done["complete"]
        assert any(f.endswith("proto.ko.srt") for f in done["files"])
        # 완성 후 편집 명령도 처리되어야 한다
        uid = [e for e in events if e["ev"] == "units"][0]["items"][0]["id"]
        proc.stdin.write(json.dumps({"cmd": "edit", "id": uid, "text": "프로토콜 편집"}, ensure_ascii=False) + "\n")
        proc.stdin.flush()
        deadline = time.time() + 10
        ko = Path(video).with_name("proto.ko.srt")
        while time.time() < deadline and "프로토콜 편집" not in ko.read_text("utf-8-sig"):
            time.sleep(0.05)
        assert "프로토콜 편집" in ko.read_text("utf-8-sig")
    finally:
        proc.stdin.write(json.dumps({"cmd": "shutdown"}) + "\n")
        proc.stdin.flush()
        proc.wait(20)
    assert proc.returncode == 0
