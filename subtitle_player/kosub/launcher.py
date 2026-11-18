"""실행 진입점: GUI(기본), --cli(일괄 생성), --worker(내부용 워커 프로세스)."""

from __future__ import annotations

import sys
from typing import List, Optional


def main(argv: Optional[List[str]] = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if args and args[0] == "--worker":
        from .worker import main as worker_main

        return worker_main(args[1:])
    if args and args[0] == "--cli":
        from .cli import main as cli_main

        return cli_main(args[1:])
    if args and args[0] in ("-h", "--help"):
        print(
            "사용법:\n"
            "  python main.py [동영상 파일]          플레이어 실행\n"
            "  python main.py --cli 파일... [옵션]   창 없이 자막만 만들기 (--cli --help 참고)"
        )
        return 0
    from .ui.app import run_gui

    return run_gui(args)
