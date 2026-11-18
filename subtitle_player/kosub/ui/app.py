"""GUI 실행."""

from __future__ import annotations

import logging
import logging.handlers
import os
import sys
from typing import List


def _setup_logging() -> None:
    from ..paths import logs_dir

    handler = logging.handlers.RotatingFileHandler(
        logs_dir() / "app.log", maxBytes=2 * 1024 * 1024, backupCount=2, encoding="utf-8"
    )
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.addHandler(handler)
    if sys.stderr is not None:
        root.addHandler(logging.StreamHandler())


def run_gui(args: List[str]) -> int:
    _setup_logging()
    if sys.platform == "win32":
        try:  # 작업 표시줄에 앱 아이콘이 따로 보이도록
            import ctypes

            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("KoSubPlayer.App")
        except Exception:
            pass

    from PySide6.QtWidgets import QApplication

    from .. import APP_ID, APP_NAME
    from ..config import Settings
    from . import theme
    from .main_window import MainWindow

    app = QApplication.instance() or QApplication(sys.argv[:1])
    app.setApplicationName(APP_NAME)
    app.setOrganizationName(APP_ID)
    app.setWindowIcon(theme.app_icon())
    theme.apply_dark_theme(app)

    settings = Settings.load()
    window = MainWindow(settings)
    window.show()
    files = [a for a in args if not a.startswith("-")]
    if files and os.path.isfile(files[0]):
        window.open_file(files[0])
    return app.exec()
