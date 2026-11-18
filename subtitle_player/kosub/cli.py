"""명령줄 모드: 창을 띄우지 않고 여러 동영상의 한국어 자막을 한꺼번에 만든다.

예) python main.py --cli 영상1.mp4 영상2.mkv --model small --translator google
"""

from __future__ import annotations

import argparse
import logging
import sys
from typing import Any, Dict, List, Optional

from . import APP_NAME, __version__
from .config import Settings
from .transcriber import MODEL_INFO


def _printer(prefix: str):
    state: Dict[str, Any] = {"last_pct": -1}

    def emit(ev: Dict[str, Any]) -> None:
        kind = ev.get("ev")
        if kind == "status":
            print(f"{prefix}{ev.get('text')}", flush=True)
        elif kind == "download":
            total = ev.get("total") or 0
            if total:
                pct = int(100 * ev.get("done", 0) / total)
                if pct // 10 != state["last_pct"] // 10:
                    state["last_pct"] = pct
                    print(f"{prefix}모델 다운로드 {min(pct, 100)}%", flush=True)
        elif kind == "model":
            print(f"{prefix}모델: {ev.get('name')} ({ev.get('device')}, {ev.get('compute_type')})", flush=True)
        elif kind == "language":
            print(f"{prefix}언어 감지: {ev.get('name')} ({ev.get('prob', 0):.0%})", flush=True)
        elif kind == "coverage":
            dur = ev.get("duration") or 0
            if dur:
                covered = sum(b - a for a, b in ev.get("intervals") or [])
                pct = int(100 * covered / dur)
                if pct != state.get("cov_pct") and (pct % 10 == 0 or pct == 100):
                    state["cov_pct"] = pct
                    speed = ev.get("speed")
                    extra = f" · {speed:.1f}배속" if speed else ""
                    print(f"{prefix}음성 인식 {pct}%{extra}", flush=True)
        elif kind in ("error", "warning"):
            print(f"{prefix}{'오류' if kind == 'error' else '경고'}: {ev.get('text')}", file=sys.stderr, flush=True)
        elif kind == "done":
            print(f"{prefix}{ev.get('message')}", flush=True)
            for f in ev.get("files") or []:
                print(f"{prefix}  → {f}", flush=True)

    return emit


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(prog="kosub --cli", description=f"{APP_NAME} {__version__} — 자막 일괄 생성")
    parser.add_argument("files", nargs="+", help="동영상/음성 파일")
    parser.add_argument("--model", choices=["auto"] + list(MODEL_INFO), help="음성 인식 모델")
    parser.add_argument("--device", choices=["auto", "cpu", "cuda"])
    parser.add_argument("--language", help="원본 언어 코드 (예: en, ja). 기본: 자동 감지")
    parser.add_argument("--translator", choices=["google", "claude", "none"])
    parser.add_argument("--claude-key", help="Claude API 키 (없으면 설정값/ANTHROPIC_API_KEY)")
    parser.add_argument("--claude-model")
    parser.add_argument("--out-dir", help="자막 저장 폴더 (기본: 동영상과 같은 폴더)")
    parser.add_argument("--no-source", action="store_true", help="원어 자막(.en.srt 등)은 저장하지 않음")
    parser.add_argument("--bilingual", action="store_true", help="이중 자막(.ko+en.srt)도 저장")
    parser.add_argument("--retranslate", action="store_true", help="이미 만든 자막을 다시 번역")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING)

    from .engine import SubtitleJob
    from .transcriber import ModelCache

    opts = Settings.load().job_options()
    if args.model:
        opts["model"] = args.model
    if args.device:
        opts["device"] = args.device
    if args.language:
        opts["language"] = args.language
    if args.translator:
        opts["translator"] = args.translator
    if args.claude_key:
        opts["claude_api_key"] = args.claude_key
    if args.claude_model:
        opts["claude_model"] = args.claude_model
    if args.out_dir:
        opts["output_location"], opts["output_dir"] = "custom", args.out_dir
    if args.no_source:
        opts["save_source_srt"] = False
    if args.bilingual:
        opts["save_bilingual_srt"] = True

    cache = ModelCache()
    failed = 0
    for i, path in enumerate(args.files, 1):
        prefix = f"[{i}/{len(args.files)}] " if len(args.files) > 1 else ""
        print(f"{prefix}{path}", flush=True)
        result: Dict[str, Any] = {}
        printer = _printer(prefix)

        def emit(ev: Dict[str, Any], _p=printer, _r=result) -> None:
            if ev.get("ev") == "error" and ev.get("fatal"):
                _r["error"] = ev.get("text")
            if ev.get("ev") == "done":
                _r["done"] = True
            _p(ev)

        job = SubtitleJob(path, opts, emit, model_cache=cache, retranslate=args.retranslate)
        try:
            job.run()
        except KeyboardInterrupt:
            job.cancel()
            print("중단했습니다. 지금까지 만든 자막은 저장되었습니다.")
            return 130
        if result.get("error") or not result.get("done"):
            failed += 1
    return 1 if failed else 0
