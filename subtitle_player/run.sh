#!/usr/bin/env bash
# 한글자막 플레이어 실행 (macOS / Linux)
set -e
cd "$(dirname "$0")"
PY="${PYTHON:-python3}"
if [ ! -x .venv/bin/python ]; then
  echo "[한글자막 플레이어] 처음 실행입니다. 필요한 패키지를 설치합니다 (몇 분 걸릴 수 있음)..."
  "$PY" -m venv .venv
fi
if [ ! -f .venv/.installed ]; then
  .venv/bin/python -m pip install --upgrade pip
  .venv/bin/python -m pip install -r requirements.txt
  touch .venv/.installed
fi
exec .venv/bin/python main.py "$@"
