import threading
import time
from pathlib import Path

import pytest

from conftest import tone_pattern
from kosub.engine import SubtitleJob
from kosub.jobstore import JobState, state_path_for
from kosub.subtitles import parse_srt


@pytest.fixture(autouse=True)
def fake_backend(monkeypatch):
    monkeypatch.setenv("KOSUB_FAKE", "1")
    monkeypatch.setenv("KOSUB_FAKE_SPEED", "400")
    monkeypatch.setenv("KOSUB_FAKE_TRANSLATE_DELAY", "0")


class Recorder:
    def __init__(self):
        self.events = []
        self.lock = threading.Lock()

    def __call__(self, ev):
        with self.lock:
            self.events.append(ev)

    def of(self, kind):
        with self.lock:
            return [e for e in self.events if e["ev"] == kind]


def _opts(**kw):
    o = {"translator": "google", "save_source_srt": True, "save_bilingual_srt": False, "word_timestamps": True}
    o.update(kw)
    return o


def test_full_job_writes_srt_and_state(make_video):
    video = make_video(tone_pattern(30.0, on=2.5, off=0.5), "full.mp4")
    rec = Recorder()
    job = SubtitleJob(str(video), _opts(), rec)
    job.run()
    done = rec.of("done")
    assert done and done[-1]["complete"], rec.events[-5:]
    ko = Path(video).with_name("full.ko.srt")
    en = Path(video).with_name("full.en.srt")
    assert ko.is_file() and en.is_file()
    raw = ko.read_bytes()
    assert raw.startswith(b"\xef\xbb\xbf") and b"\r\n" in raw  # BOM + CRLF
    cues = parse_srt(ko.read_text("utf-8-sig"))
    assert len(cues) >= 8
    assert all(c.text.startswith("가짜 번역") for c in cues)
    assert all(a.end <= b.start for a, b in zip(cues, cues[1:]))
    state = JobState.load(state_path_for(str(video)))
    assert state.complete and state.transcribed and state.language == "en"
    assert state.covered.gaps(0, state.duration, 0.5) == []


def test_starts_from_playback_position(make_video):
    video = make_video(tone_pattern(400.0, on=2.5, off=0.5), "long.mp4")
    rec = Recorder()
    job = SubtitleJob(str(video), _opts(translator="none"), rec, position=250.0)
    job.run()
    actives = [e["active"] for e in rec.of("coverage") if e.get("active")]
    first = actives[0]
    assert 240.0 <= first[0] <= 252.0, first  # 재생 위치 근처에서 시작
    assert rec.of("done")[-1]["complete"]
    units = JobState.load(state_path_for(str(video))).units.values()
    starts = sorted(u.start for u in units)
    assert starts[0] < 5 and starts[-1] > 390  # 결국 전체를 처리
    assert not Path(video).with_name("long.ko.srt").exists()  # 번역 안 함 → 원어 자막만
    assert Path(video).with_name("long.en.srt").exists()


def test_seek_interrupts_and_jumps(make_video, monkeypatch):
    monkeypatch.setenv("KOSUB_FAKE_SPEED", "60")
    video = make_video(tone_pattern(600.0, on=2.5, off=0.5), "seek.mp4")
    rec = Recorder()
    job = SubtitleJob(str(video), _opts(translator="none"), rec, position=0.0)
    job.start()
    deadline = time.time() + 20
    while time.time() < deadline and not rec.of("units"):
        time.sleep(0.05)
    job.set_position(420.0)
    t_seek = time.time()
    jumped = None
    while time.time() < t_seek + 20:
        for e in rec.of("coverage"):
            a = e.get("active")
            if a and 400 <= a[0] <= 425:
                jumped = a
                break
        if jumped:
            break
        time.sleep(0.05)
    job.cancel()
    assert job.join(20)
    assert jumped is not None, "재생 위치로 이동한 뒤 그 구간을 처리하지 않았습니다"
    assert time.time() - t_seek < 5.0
    state = JobState.load(state_path_for(str(video)))
    assert not state.complete  # 취소했으므로 미완성 상태로 저장
    assert state.covered.total() > 0


def test_resume_does_not_redo_covered_parts(make_video, monkeypatch):
    monkeypatch.setenv("KOSUB_FAKE_SPEED", "80")
    video = make_video(tone_pattern(300.0, on=2.5, off=0.5), "resume.mp4")
    rec1 = Recorder()
    job1 = SubtitleJob(str(video), _opts(), rec1)
    job1.start()
    deadline = time.time() + 30
    while time.time() < deadline and len(rec1.of("units")) < 15:
        time.sleep(0.05)
    job1.cancel()
    assert job1.join(20)
    partial = JobState.load(state_path_for(str(video)))
    assert partial is not None and 0 < partial.covered.total() < 300
    first_ids = set(partial.units)

    monkeypatch.setenv("KOSUB_FAKE_SPEED", "400")
    rec2 = Recorder()
    job2 = SubtitleJob(str(video), _opts(), rec2)
    job2.run()
    final = JobState.load(state_path_for(str(video)))
    assert final.complete
    assert first_ids <= set(final.units)
    starts = sorted(u.start for u in final.units.values())
    assert all(b - a > 0.5 for a, b in zip(starts, starts[1:])), "같은 구간이 중복 처리됨"
    assert all(u.ko for u in final.units.values())


def test_edit_and_retranslate_keep_user_text(make_video):
    video = make_video(tone_pattern(20.0, on=2.5, off=0.5), "edit.mp4")
    rec = Recorder()
    job = SubtitleJob(str(video), _opts(), rec)
    job.run()
    uid = sorted(job.state.units.values(), key=lambda u: u.start)[0].id
    job.edit(uid, "제가 고친 번역")
    ko = Path(video).with_name("edit.ko.srt")
    assert "제가 고친 번역" in ko.read_text("utf-8-sig")

    rec2 = Recorder()
    job2 = SubtitleJob(str(video), _opts(), rec2, retranslate=True)
    job2.run()
    st = JobState.load(state_path_for(str(video)))
    assert st.units[uid].ko == "제가 고친 번역" and st.units[uid].edited
    assert st.complete


def test_completed_job_loads_without_model(make_video, monkeypatch):
    video = make_video(tone_pattern(12.0, on=2.5, off=0.5), "again.mp4")
    SubtitleJob(str(video), _opts(), Recorder()).run()

    def boom(*a, **k):
        raise AssertionError("완성된 작업은 모델을 불러오지 않아야 합니다")

    rec = Recorder()
    job = SubtitleJob(str(video), _opts(), rec)
    monkeypatch.setattr(job.model_cache, "get", boom)
    job.run()
    assert rec.of("state")[0]["units"]
    assert rec.of("done")[-1]["complete"]


def test_korean_source_needs_no_translation(make_video, monkeypatch):
    video = make_video(tone_pattern(12.0, on=2.5, off=0.5), "korean.mp4")
    rec = Recorder()
    SubtitleJob(str(video), _opts(language="ko"), rec).run()
    assert not rec.of("error")
    ko = Path(video).with_name("korean.ko.srt")
    cues = parse_srt(ko.read_text("utf-8-sig"))
    assert cues and all(c.text.startswith("Fake sentence") for c in cues)
    assert not list(Path(video).parent.glob("korean.en.srt"))


def test_video_without_audio_reports_error(tmp_path):
    import shutil
    import subprocess

    if not shutil.which("ffmpeg"):
        pytest.skip("ffmpeg 필요")
    out = tmp_path / "silent.mp4"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=red:size=64x64:duration=2",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", str(out)], check=True)
    rec = Recorder()
    SubtitleJob(str(out), _opts(), rec).run()
    errors = rec.of("error")
    assert errors and "오디오" in errors[0]["text"]
