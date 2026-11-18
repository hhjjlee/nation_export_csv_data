"""한글자막 플레이어 실행 파일.

    python main.py                 # 플레이어 실행
    python main.py 영상.mp4        # 파일을 열면서 실행
    python main.py --cli 영상.mp4  # 창 없이 자막만 생성
"""

import multiprocessing
import os
import sys

if __name__ == "__main__":
    multiprocessing.freeze_support()
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from kosub.launcher import main

    raise SystemExit(main())
