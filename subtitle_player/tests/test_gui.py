"""화면 없이(offscreen) 플레이어를 띄워 자막 생성/표시/저장 흐름을 확인한다."""

import time

import pytest

from conftest import tone_pattern

pytest.importorskip("PySide6.QtMultimedia")


@pytest.fixture
def app(monkeypatch):
    monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    monkeypatch.setenv("KOSUB_FAKE", "1")
    monkeypatch.setenv("KOSUB_FAKE_SPEED", "200")
    from PySide6.QtWidgets import QApplication

    return QApplication.instance() or QApplication([])


def _wait(app, cond, timeout=20.0):
    from PySide6.QtCore import QEventLoop, QTimer

    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        loop = QEventLoop()
        QTimer.singleShot(50, loop.quit)
        loop.exec()
    return cond()


def test_player_generates_shows_and_saves(app, make_video, tmp_path):
    from kosub.config import Settings
    from kosub.ui.main_window import MainWindow

    video = make_video(tone_pattern(20.0, on=2.5, off=0.5), "gui.mp4")
    win = MainWindow(Settings())
    win.resize(1100, 650)
    win.show()
    try:
        win.open_file(str(video))
        assert _wait(app, lambda: win.cues and not win.job_active), win.status_label.text()
        assert win.panel.model.rowCount() == len(win.units) >= 5
        assert "자막 완성" in win.status_label.text()
        ko = video.with_name("gui.ko.srt")
        assert ko.is_file()

        # 자막이 있는 시각으로 이동하면 화면에 한국어 자막이 그려져야 한다
        cue = win.cues[2]
        win.player.pause()
        win._seek_ms(int((cue.start + 0.3) * 1000))
        assert _wait(app, lambda: win.view.subtitle._main == cue.text, 5)
        assert win.view.subtitle.boundingRect().height() > 10

        # 목록에서 번역을 고치면 파일에 바로 저장된다
        win._edit_unit(cue.unit_id, "직접 고친 자막")
        assert _wait(app, lambda: "직접 고친 자막" in ko.read_text("utf-8-sig"), 10)
    finally:
        win.close()


def test_existing_korean_srt_is_loaded_not_regenerated(app, make_video):
    from kosub.config import Settings
    from kosub.ui.main_window import MainWindow

    video = make_video(tone_pattern(6.0), "has_sub.mp4")
    srt = video.with_name("has_sub.ko.srt")
    srt.write_text("1\n00:00:01,000 --> 00:00:02,500\n이미 있는 자막\n", encoding="utf-8")
    win = MainWindow(Settings())
    win.show()
    try:
        win.open_file(str(video))
        assert not win.job_active
        assert win.external_srt == str(srt)
        assert [c.text for c in win.cues] == ["이미 있는 자막"]
    finally:
        win.close()
