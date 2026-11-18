"""동영상/음성 파일에서 필요한 구간의 오디오만 16 kHz 모노로 디코딩한다 (PyAV).

전체 파일을 메모리에 올리지 않고 구간마다 탐색(seek)해서 읽기 때문에
두 시간짜리 영화의 중간으로 바로 이동해도 곧바로 그 위치부터 자막을 만들 수 있다.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Iterator, Optional

import numpy as np

log = logging.getLogger(__name__)

SAMPLE_RATE = 16000


class NoAudioError(RuntimeError):
    pass


class _NeedFullDecode(Exception):
    """탐색 후 첫 프레임에 타임스탬프가 없어 위치를 알 수 없을 때."""


@dataclass
class MediaInfo:
    duration: Optional[float]
    audio_streams: int
    origin: float  # 컨테이너 시작 시각 (플레이어의 0초에 해당)


def open_media(path: str):
    """PyAV 버전에 따라 metadata_errors 인자가 없을 수 있다."""
    import av

    try:
        return av.open(path, metadata_errors="ignore")
    except TypeError:
        return av.open(path)


def probe(path: str) -> MediaInfo:
    with open_media(path) as container:
        origin = (container.start_time or 0) / 1_000_000 if container.start_time else 0.0
        duration = container.duration / 1_000_000 if container.duration else None
        streams = list(container.streams.audio)
        if duration is None and streams:
            st = streams[0]
            if st.duration and st.time_base:
                duration = float(st.duration * st.time_base)
        return MediaInfo(duration=duration, audio_streams=len(streams), origin=origin)


def _iter_frames(container, stream) -> Iterator["object"]:
    """손상된 패킷은 건너뛰면서 오디오 프레임을 내보낸다."""
    import av

    demux = container.demux(stream)
    while True:
        try:
            packet = next(demux)
        except StopIteration:
            return
        except av.error.FFmpegError as exc:  # 컨테이너가 망가진 경우 여기서 끝낸다
            log.warning("오디오 디먹싱 중단: %s", exc)
            return
        try:
            frames = packet.decode()
        except av.error.FFmpegError:
            continue
        for frame in frames:
            yield frame


class AudioReader:
    def __init__(self, path: str, track: int = 0):
        self.path = path
        info = probe(path)
        if info.audio_streams == 0:
            raise NoAudioError("이 파일에는 오디오 트랙이 없습니다.")
        self.track = track if 0 <= track < info.audio_streams else 0
        self.duration = info.duration
        self.origin = info.origin

    def read(self, start: float, end: float) -> np.ndarray:
        """[start, end) 구간을 float32 배열로 돌려준다. 파일 끝을 넘으면 그만큼 짧아진다."""
        import av

        start = max(0.0, float(start))
        if end <= start:
            return np.zeros(0, dtype=np.float32)
        n_total = int(round((end - start) * SAMPLE_RATE))
        out = np.zeros(n_total, dtype=np.int16)
        written = 0
        try:
            written = self._decode_into(out, start, end, seek=start > 1.0)
        except (av.error.FFmpegError, _NeedFullDecode) as exc:
            log.warning("탐색 디코딩 실패(%r), 처음부터 다시 읽습니다", exc)
            out[:] = 0
            written = self._decode_into(out, start, end, seek=False)
        return out[:written].astype(np.float32) / 32768.0

    def _decode_into(self, out: np.ndarray, start: float, end: float, seek: bool) -> int:
        import av

        written = 0
        with open_media(self.path) as container:
            stream = container.streams.audio[self.track]
            stream.thread_type = "AUTO"
            if seek:
                target = start + self.origin - 1.0
                container.seek(max(0, int(target / stream.time_base)), stream=stream, backward=True, any_frame=False)
            resampler = av.AudioResampler(format="s16", layout="mono", rate=SAMPLE_RATE)
            cursor: Optional[float] = None
            for frame in _iter_frames(container, stream):
                ftime = frame.time
                if ftime is not None:
                    ftime -= self.origin
                    # 처음 프레임이거나 타임스탬프가 크게 튀면(손상/불연속) 위치를 다시 맞춘다
                    if cursor is None or abs(ftime - cursor) > 0.5:
                        cursor = ftime
                elif cursor is None:
                    if seek:
                        raise _NeedFullDecode()
                    cursor = 0.0
                if cursor > end + 1.0:
                    break
                for rframe in resampler.resample(frame):
                    data = rframe.to_ndarray().reshape(-1)
                    written = max(written, self._place(out, data, cursor, start))
                    cursor += len(data) / SAMPLE_RATE
                if cursor >= end:
                    break
            else:
                if cursor is not None:
                    for rframe in resampler.resample(None):
                        data = rframe.to_ndarray().reshape(-1)
                        written = max(written, self._place(out, data, cursor, start))
                        cursor += len(data) / SAMPLE_RATE
        return written

    @staticmethod
    def _place(out: np.ndarray, data: np.ndarray, t0: float, start: float) -> int:
        idx = int(round((t0 - start) * SAMPLE_RATE))
        lo = max(idx, 0)
        hi = min(idx + len(data), len(out))
        if hi <= lo:
            return 0 if idx + len(data) <= 0 else (len(out) if idx >= len(out) else 0)
        out[lo:hi] = data[lo - idx : hi - idx]
        return hi


def find_quiet_point(audio: np.ndarray, lo: float, hi: float) -> float:
    """audio 안의 [lo, hi] 초 범위에서 말소리가 없는 지점(가장 긴 무음의 가운데)을 찾는다."""
    n = len(audio)
    lo_i = max(0, int(lo * SAMPLE_RATE))
    hi_i = min(n, int(hi * SAMPLE_RATE))
    if hi_i - lo_i < SAMPLE_RATE // 2:
        return (lo + hi) / 2
    window = audio[lo_i:hi_i]
    try:
        from faster_whisper.vad import VadOptions, get_speech_timestamps

        speech = get_speech_timestamps(
            window,
            VadOptions(min_silence_duration_ms=150, speech_pad_ms=40, min_speech_duration_ms=120),
        )
        edges = [0] + [x for chunk in speech for x in (chunk["start"], chunk["end"])] + [len(window)]
        best_len, best_mid = 0, None
        for a, b in zip(edges[0::2], edges[1::2]):
            if b - a > best_len:
                best_len, best_mid = b - a, (a + b) // 2
        if best_mid is not None and best_len >= int(0.2 * SAMPLE_RATE):
            return (lo_i + best_mid) / SAMPLE_RATE
    except Exception:  # VAD를 못 쓰면 에너지 기준으로
        log.debug("VAD 무음 탐색 실패", exc_info=True)
    frame = 320  # 20ms
    usable = (len(window) // frame) * frame
    if usable < frame * 5:
        return (lo + hi) / 2
    rms = np.sqrt(np.mean(window[:usable].reshape(-1, frame) ** 2, axis=1) + 1e-12)
    k = 15  # 0.3초 이동 평균
    smooth = np.convolve(rms, np.ones(k) / k, mode="same")
    best = int(np.argmin(smooth))
    return (lo_i + best * frame + frame // 2) / SAMPLE_RATE
