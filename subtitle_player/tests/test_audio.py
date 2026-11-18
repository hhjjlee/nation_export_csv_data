import numpy as np

from conftest import tone_pattern
from kosub.audio import SAMPLE_RATE, AudioReader, find_quiet_point, probe


def _onsets(audio: np.ndarray, threshold: float = 0.05):
    """소리가 시작되는 시각(초) 목록."""
    frame = 160
    usable = len(audio) // frame * frame
    env = np.abs(audio[:usable]).reshape(-1, frame).max(axis=1) > threshold
    idx = np.flatnonzero(env[1:] & ~env[:-1]) + 1
    return idx * frame / SAMPLE_RATE


def test_probe_and_region_reads_are_time_accurate(make_video):
    # 1초 소리 / 1초 무음 반복, 20초
    video = make_video(tone_pattern(20.0, on=1.0, off=1.0), "tones.mp4")
    info = probe(str(video))
    assert info.audio_streams == 1
    assert abs(info.duration - 20.0) < 0.3

    reader = AudioReader(str(video))
    whole = reader.read(0, 20)
    assert abs(len(whole) / SAMPLE_RATE - 20.0) < 0.2

    # 중간 구간을 탐색해서 읽어도 소리 시작 위치가 맞아야 한다 (10.0, 12.0 ... 기준)
    part = reader.read(9.5, 15.5)
    assert abs(len(part) / SAMPLE_RATE - 6.0) < 0.01
    onsets = _onsets(part) + 9.5
    expected = [10.0, 12.0, 14.0]
    assert len(onsets) == 3
    for got, exp in zip(onsets, expected):
        assert abs(got - exp) < 0.06, (got, exp)


def test_read_past_end_returns_short_audio(make_video):
    video = make_video(tone_pattern(6.0), "short.mp4")
    reader = AudioReader(str(video))
    tail = reader.read(4.0, 20.0)
    assert 1.6 < len(tail) / SAMPLE_RATE < 2.4
    assert len(reader.read(30.0, 40.0)) == 0


def test_find_quiet_point_lands_in_silence():
    audio = tone_pattern(10.0, on=1.5, off=0.5)  # 무음: 1.5-2.0, 3.5-4.0, 5.5-6.0 ...
    t = find_quiet_point(audio, 4.5, 6.8)
    assert 5.5 <= t <= 6.0, t
