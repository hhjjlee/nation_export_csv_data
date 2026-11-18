"""자막 생성 작업: 재생 위치 우선으로 구간을 골라 인식 → 문장 분할 → 번역 → 자동 저장.

- 재생 중인 위치(또는 사용자가 이동한 위치)부터 먼저 처리한다.
- 구간 경계는 말소리가 없는 지점으로 맞춰 문장이 잘리지 않게 한다.
- 처리 결과는 몇 초마다 작업 상태(JSON)와 .srt 파일로 저장되므로
  중간에 닫아도 다음에 열면 이어서 만든다.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from .config import DEFAULT_JOB_OPTIONS
from .intervals import IntervalSet
from .jobstore import JobState, file_fingerprint, write_outputs
from .languages import language_name
from .paths import jobs_dir
from .segmentation import Segment, Word, build_units, ends_sentence, is_hallucination
from .subtitles import Unit

log = logging.getLogger(__name__)

Emit = Callable[[Dict[str, Any]], None]

CHUNK_SECONDS = 120.0
SAVE_INTERVAL = 3.0
MAX_TRANSLATE_ATTEMPTS = 6
RETRY_BASE_SECONDS = 5.0  # 번역 실패 시 5, 10, 20, 40, 60초 뒤 다시 시도


@dataclass
class Region:
    start: float
    end: float
    snap_start: bool
    snap_end: bool
    gap_lo: float
    gap_hi: float
    pos_version: int


class SubtitleJob:
    def __init__(
        self,
        video: str,
        options: Optional[Dict[str, Any]],
        emit: Emit,
        *,
        job_id: int = 0,
        position: float = 0.0,
        audio_track: int = 0,
        retranslate: bool = False,
        duration_hint: Optional[float] = None,
        model_cache=None,
        previous: Optional["SubtitleJob"] = None,
    ):
        self.video = os.path.abspath(video)
        self.options: Dict[str, Any] = dict(DEFAULT_JOB_OPTIONS)
        self.options.update(options or {})
        self._emit_cb = emit
        self.job_id = job_id
        self.audio_track = audio_track
        self.retranslate_requested = retranslate
        self.duration_hint = duration_hint
        if model_cache is None:
            from .transcriber import ModelCache

            model_cache = ModelCache()
        self.model_cache = model_cache
        self.previous = previous

        self._lock = threading.RLock()
        self._cv = threading.Condition(self._lock)
        self._pos = max(0.0, float(position or 0.0))
        self._pos_version = 0
        self._cancel = threading.Event()
        self.finished = threading.Event()
        self._thread: Optional[threading.Thread] = None

        self.state: Optional[JobState] = None
        self.state_file: Optional[Path] = None
        self._queue: List[int] = []
        self._retry: Dict[int, Tuple[int, float]] = {}
        self._attempts: Dict[int, int] = {}
        self._transcribing = False
        self._translator = None
        self._translator_error: Optional[str] = None
        self._translator_thread: Optional[threading.Thread] = None
        self._detections = 0
        self._dirty = False
        self._last_save = 0.0
        self._last_cov_emit = 0.0
        self._speed: Optional[float] = None

    # ------------------------------------------------------------------
    # 외부에서 부르는 메서드 (스레드 안전)
    def start(self) -> None:
        self._thread = threading.Thread(target=self.run, name=f"job-{self.job_id}", daemon=True)
        self._thread.start()

    def join(self, timeout: Optional[float] = None) -> bool:
        return self.finished.wait(timeout)

    def cancel(self) -> None:
        self._cancel.set()
        with self._cv:
            self._cv.notify_all()

    def set_position(self, seconds: float) -> None:
        with self._cv:
            self._pos = max(0.0, float(seconds))
            self._pos_version += 1
            self._cv.notify_all()

    def edit(self, unit_id: int, text: str) -> None:
        with self._lock:
            if self.state is None:
                return
            unit = self.state.units.get(unit_id)
            if unit is None:
                return
            text = " ".join((text or "").split())
            unit.ko = text or None
            unit.edited = bool(text)
            self._dirty = True
            item = unit.to_dict()
        self._emit("units", items=[item])
        self._save(force=True)

    # ------------------------------------------------------------------
    def _emit(self, ev: str, **data: Any) -> None:
        data["ev"] = ev
        data["job"] = self.job_id
        try:
            self._emit_cb(data)
        except Exception:  # 출력 채널 문제로 작업이 죽지 않게
            log.exception("이벤트 전송 실패: %s", ev)

    def _fail(self, text: str) -> None:
        """치명적 오류를 알리고 작업을 멈춘다. 지금까지 만든 자막은 저장된다."""
        self._emit("error", text=text, fatal=True)
        self._cancel.set()
        with self._cv:
            self._cv.notify_all()

    def _status(self, phase: str, text: str) -> None:
        self._emit("status", phase=phase, text=text)

    def _download_progress(self, done: int, total: int) -> None:
        self._emit("download", done=int(done), total=int(total))

    # ------------------------------------------------------------------
    def run(self) -> None:
        try:
            if self.previous is not None and self.previous.video == self.video:
                self.previous.finished.wait(120)  # 같은 동영상의 이전 작업이 저장을 마칠 때까지
            self._run()
        except Exception as exc:
            log.exception("자막 작업 실패")
            self._fail(f"자막 생성 중 오류가 발생했습니다: {exc}")
        finally:
            with self._cv:
                self._transcribing = False
                self._cv.notify_all()
            if self._translator_thread is not None and self._cancel.is_set():
                with self._cv:
                    self._cv.notify_all()
                self._translator_thread.join(5)
            self._save(force=True)
            self.finished.set()
            self._emit("finished")

    def _run(self) -> None:
        from .audio import AudioReader, NoAudioError, probe

        self._status("prepare", "자막 작업 준비 중")
        fingerprint = file_fingerprint(self.video)
        self.state_file = jobs_dir() / f"{fingerprint}.json"
        state = JobState.load(self.state_file) or JobState(video=self.video, fingerprint=fingerprint)
        state.video = self.video
        forced = self.options.get("language") or "auto"
        if forced != "auto":
            state.language, state.language_prob, state.language_locked = forced, 1.0, True
        with self._lock:
            self.state = state
        self._emit_state()

        try:
            info = probe(self.video)
        except Exception as exc:
            self._fail(f"파일을 열 수 없습니다: {exc}")
            return
        if info.audio_streams == 0:
            self._fail("이 파일에는 오디오 트랙이 없어 자막을 만들 수 없습니다.")
            return
        with self._lock:
            if info.duration:
                state.duration = info.duration
            elif self.duration_hint and not state.duration:
                state.duration = float(self.duration_hint)

        if self.retranslate_requested and state.language != "ko":
            with self._lock:
                for unit in state.units.values():
                    if not unit.edited:
                        unit.ko = None
                state.complete = False
                self._dirty = True
            self._emit_state()

        if state.complete:
            if not state.outputs or not all(os.path.exists(p) for p in state.outputs):
                with self._lock:
                    self._dirty = True
                self._save(force=True)
            self._emit("done", complete=True, files=state.outputs, message="이미 만들어 둔 자막을 불러왔습니다.")
            return

        need_asr = not state.transcribed
        with self._cv:
            # 번역 스레드는 인식이 끝날 때까지 새 문장을 기다린다
            self._transcribing = need_asr
        try:
            self._ensure_translator()
            if need_asr:
                try:
                    reader = AudioReader(self.video, self.audio_track)
                except NoAudioError as exc:
                    self._fail(str(exc))
                    return
                self._transcribe_all(reader)
        finally:
            with self._cv:
                self._transcribing = False
                self._cv.notify_all()
        self._wait_translation()
        self._finalize()

    # ------------------------------------------------------------------
    # 인식
    def _transcribe_all(self, reader) -> None:
        from .transcriber import ModelDownloadError

        state = self.state
        assert state is not None
        try:
            transcriber = self.model_cache.get(self.options, status=self._status, progress=self._download_progress)
        except ModelDownloadError as exc:
            self._fail(str(exc))
            return
        self._emit(
            "model",
            name=transcriber.model_name,
            device=transcriber.device,
            compute_type=transcriber.compute_type,
            note=getattr(transcriber, "device_note", ""),
        )
        with self._lock:
            state.model = transcriber.model_name
            if reader.duration and not state.duration:
                state.duration = reader.duration
        self._status("transcribing", "음성 인식 중")
        while not self._cancel.is_set():
            region = self._pick_region()
            if region is None:
                break
            self._process_region(reader, transcriber, region)
            self._save()

    def _gaps(self) -> List[Tuple[float, float]]:
        state = self.state
        assert state is not None
        return state.covered.gaps(0.0, state.duration or 1e9, min_len=0.5)

    def _pick_region(self) -> Optional[Region]:
        with self._lock:
            gaps = self._gaps()
            if not gaps:
                return None
            dur = self.state.duration or 1e9
            pos = min(self._pos, dur)
            pv = self._pos_version
        lo, hi = next((g for g in gaps if g[1] > pos + 1.0), gaps[0])
        start, snap_start = lo, False
        if lo < pos < hi and pos - lo > 15.0:
            start, snap_start = pos - 3.0, True
        end = min(hi, start + CHUNK_SECONDS)
        snap_end = end < hi
        if snap_end and hi - end < 20.0:
            end, snap_end = hi, False
        return Region(start, end, snap_start, snap_end, lo, hi, pv)

    def _process_region(self, reader, transcriber, r: Region) -> None:
        from .audio import SAMPLE_RATE, find_quiet_point

        state = self.state
        read_lo = max(r.gap_lo, r.start - 6.0) if r.snap_start else r.start
        read_hi = min(r.gap_hi, r.end + 6.0) if r.snap_end else r.end
        self._emit_coverage(active=(r.start, r.start), force=True)
        audio = reader.read(read_lo, read_hi)
        got_end = read_lo + len(audio) / SAMPLE_RATE
        end_time, snap_end = r.end, r.snap_end
        if got_end < read_hi - 0.5:
            with self._lock:
                known = state.duration
                if known is None or known > 1e8 or got_end >= known - 5.0:
                    state.duration = got_end  # 실제 오디오 끝
                else:
                    # 중간에 읽을 수 없는 부분은 건너뛴다 (같은 구간을 계속 다시 시도하지 않도록)
                    state.covered.add(max(r.start, got_end), r.end)
                self._dirty = True
            end_time, snap_end = min(r.end, got_end), False
        if end_time - r.start < 0.3:
            return

        start_time = r.start
        if r.snap_start:
            q = find_quiet_point(audio, max(0.0, r.start - 6.0 - read_lo), r.start + 2.0 - read_lo)
            start_time = read_lo + q
        if snap_end:
            q = find_quiet_point(audio, r.end - 6.0 - read_lo, min(got_end, r.end + 6.0) - read_lo)
            end_time = read_lo + q
        if end_time - start_time < 1.0:
            start_time, end_time = r.start, min(r.end, got_end)
        chunk = audio[int(round((start_time - read_lo) * SAMPLE_RATE)) : int(round((end_time - read_lo) * SAMPLE_RATE))]

        with self._lock:
            known_lang = state.language if (state.language_locked or (state.language and state.language_prob >= 0.8)) else None
        segments, (det_lang, det_prob) = transcriber.transcribe(
            chunk, language=known_lang, word_timestamps=bool(self.options.get("word_timestamps", True))
        )
        region_lang = known_lang or det_lang
        if known_lang is None and det_lang:
            self._update_language(det_lang, det_prob)

        pending: List[Segment] = []
        processed = start_time
        interrupted = False
        t0 = time.monotonic()
        self._status("transcribing", "음성 인식 중")
        for seg in segments:
            if self._cancel.is_set():
                interrupted = True
                break
            seg = _shift(seg, start_time, end_time)
            if seg is None:
                continue
            processed = max(processed, seg.end)
            if not is_hallucination(seg):
                if pending and seg.start - pending[-1].end > 1.5:
                    self._flush(pending, region_lang)
                    pending = []
                pending.append(seg)
                if ends_sentence(seg.text) or len(pending) >= 8 or pending[-1].end - pending[0].start > 30.0:
                    self._flush(pending, region_lang)
                    pending = []
            self._emit_coverage(active=(start_time, processed))
            if self._should_interrupt(start_time, end_time, r.pos_version):
                interrupted = True
                break
        if pending:
            self._flush(pending, region_lang)
        covered_end = processed if interrupted else end_time
        elapsed = max(1e-3, time.monotonic() - t0)
        with self._lock:
            if covered_end > start_time:
                state.covered.add(start_time, covered_end)
            self._dirty = True
            if covered_end - start_time > 5:
                self._speed = (covered_end - start_time) / elapsed
        self._emit_coverage(active=None, force=True)

    def _should_interrupt(self, start: float, end: float, pos_version: int) -> bool:
        """재생 위치가 처리 중인 구간 밖의, 자막이 없는 곳으로 옮겨 갔으면 중단한다."""
        if self._cancel.is_set():
            return True
        with self._lock:
            if self._pos_version == pos_version:
                return False
            pos = self._pos
            if start - 1.0 <= pos <= end:
                return False
            covered = self.state.covered
            if covered.contains(pos):
                gaps = covered.gaps(pos, self.state.duration or 1e9, 0.5)
                if not gaps:
                    return False
                need = gaps[0][0]
            else:
                need = pos
            if start - 1.0 <= need <= end:
                return False
            return need - pos < 25.0

    def _update_language(self, code: str, prob: float) -> None:
        changed = False
        with self._lock:
            state = self.state
            self._detections += 1
            if not state.language_locked and (state.language is None or prob > state.language_prob):
                changed = state.language != code
                state.language, state.language_prob = code, prob
            if state.language_prob >= 0.8 or self._detections >= 3:
                state.language_locked = True
            self._dirty = True
        if changed:
            self._emit("language", code=code, name=language_name(code), prob=round(prob, 3))
        self._ensure_translator()

    def _flush(self, segments: List[Segment], lang: Optional[str]) -> None:
        drafts = build_units(segments, lang)
        if not drafts:
            return
        items = []
        with self._cv:
            state = self.state
            for d in drafts:
                unit = Unit(id=state.next_id, start=d.start, end=d.end, text=d.text, parts=d.parts)
                state.next_id += 1
                if lang == "ko":
                    unit.ko = unit.text
                state.units[unit.id] = unit
                items.append(unit.to_dict())
                if lang != "ko" and self._translator is not None:
                    self._queue.append(unit.id)
            self._dirty = True
            self._cv.notify_all()
        self._emit("units", items=items)

    # ------------------------------------------------------------------
    # 번역
    def _ensure_translator(self) -> None:
        from .translators import TranslatorFatalError, make_translator

        with self._lock:
            state = self.state
            if self._translator is not None or self._translator_error or state is None:
                return
            if not state.language or state.language == "ko":
                return
            if (self.options.get("translator") or "google") == "none":
                return
        try:
            translator = make_translator(self.options)
        except TranslatorFatalError as exc:
            with self._lock:
                self._translator_error = str(exc)
            self._emit("error", text=str(exc), fatal=False)
            return
        if translator is None:
            return
        with self._cv:
            if self._translator is not None:
                return
            self._translator = translator
            state.translator = translator.name
            pending = sorted(state.untranslated(), key=lambda u: u.start)
            self._queue.extend(u.id for u in pending if u.id not in self._queue)
            self._translator_thread = threading.Thread(target=self._translation_loop, name="translate", daemon=True)
            self._translator_thread.start()
            self._cv.notify_all()

    def _ready_ids(self) -> List[int]:
        now = time.monotonic()
        state = self.state
        due = [uid for uid, (_n, t) in self._retry.items() if t <= now]
        ids = [uid for uid in self._queue if uid in state.units] + [uid for uid in due if uid in state.units]
        pos = self._pos

        def priority(uid: int) -> Tuple[int, float]:
            u = state.units[uid]
            return (0 if u.end >= pos - 5.0 else 1, u.start)

        return sorted(set(ids), key=priority)

    def _translation_loop(self) -> None:
        from .translators import TranslatorFatalError

        translator = self._translator
        while not self._cancel.is_set():
            with self._cv:
                while True:
                    if self._cancel.is_set():
                        return
                    ready = self._ready_ids()
                    if ready:
                        break
                    if not self._transcribing and not self._queue and not self._retry:
                        return
                    self._cv.wait(timeout=0.5)
                batch = ready[: translator.batch_size]
                for uid in batch:
                    if uid in self._queue:
                        self._queue.remove(uid)
                    self._retry.pop(uid, None)
                state = self.state
                units = [state.units[uid] for uid in batch]
                texts = [u.text for u in units]
                lang = state.language or ""
                context = self._context_before(units[0])
            self._status("translating", f"번역 중 ({translator.label})")
            try:
                results = translator.translate(texts, lang, context)
            except TranslatorFatalError as exc:
                with self._cv:
                    self._translator_error = str(exc)
                    self._queue.clear()
                    self._retry.clear()
                self._emit("error", text=str(exc), fatal=False)
                return
            except Exception as exc:  # 네트워크 오류 등은 잠시 후 다시
                log.warning("번역 실패: %s", exc)
                self._emit("warning", text=f"번역 실패, 잠시 후 다시 시도합니다: {exc}")
                with self._cv:
                    for uid in batch:
                        self._schedule_retry(uid)
                continue
            items = []
            with self._cv:
                for unit, ko in zip(units, results):
                    if unit.edited:
                        continue
                    if ko:
                        unit.ko = ko
                        items.append(unit.to_dict())
                    else:
                        self._schedule_retry(unit.id, limit=3)
                self._dirty = True
            if items:
                self._emit("units", items=items)
            self._save()

    def _schedule_retry(self, uid: int, limit: int = MAX_TRANSLATE_ATTEMPTS) -> None:
        n = self._attempts.get(uid, 0) + 1
        self._attempts[uid] = n
        if n >= limit:
            return  # 포기 (다음에 동영상을 열면 다시 시도)
        self._retry[uid] = (n, time.monotonic() + min(60.0, RETRY_BASE_SECONDS * 2 ** (n - 1)))

    def _context_before(self, unit: Unit) -> List[Tuple[str, str]]:
        earlier = [u for u in self.state.units.values() if u.end <= unit.start + 0.01 and u.ko]
        earlier.sort(key=lambda u: u.start)
        return [(u.text, u.ko) for u in earlier[-4:]]

    def _wait_translation(self) -> None:
        thread = self._translator_thread
        if thread is None:
            return
        while thread.is_alive() and not self._cancel.is_set():
            thread.join(0.5)

    # ------------------------------------------------------------------
    def _finalize(self) -> None:
        if self._cancel.is_set():
            return
        with self._lock:
            state = self.state
            state.transcribed = state.duration is not None and not self._gaps()
            need_translation = (
                state.language not in (None, "ko") and (self.options.get("translator") or "google") != "none"
            )
            missing = len(state.untranslated()) if need_translation else 0
            state.complete = state.transcribed and missing == 0
            self._dirty = True
        self._save(force=True)
        with self._lock:
            files = list(state.outputs)
            count = len(state.units)
        if count == 0:
            message = "음성이 감지되지 않아 자막을 만들지 않았습니다."
        elif state.complete:
            message = f"자막 완성 ({count}개) — 저장했습니다."
        elif missing:
            message = f"번역하지 못한 자막 {missing}개는 원문으로 저장했습니다 (다시 열면 이어서 번역)."
        else:
            message = "자막을 저장했습니다."
        self._emit("done", complete=state.complete, files=files, message=message, count=count)

    # ------------------------------------------------------------------
    def _save(self, force: bool = False) -> None:
        with self._lock:
            state = self.state
            if state is None or self.state_file is None:
                return
            if not self._dirty:
                return
            now = time.monotonic()
            if not force and now - self._last_save < SAVE_INTERVAL:
                return
            self._dirty = False
            self._last_save = now
            try:
                files = write_outputs(state, self.options)
                state.save(self.state_file)
            except OSError as exc:
                log.exception("저장 실패")
                self._emit("warning", text=f"자막을 저장하지 못했습니다: {exc}")
                return
        if files:
            self._emit("saved", files=files)

    def _emit_state(self) -> None:
        with self._lock:
            state = self.state
            data = dict(
                units=[u.to_dict() for u in state.units.values()],
                coverage=state.covered.to_list(),
                duration=state.duration,
                language=state.language,
                language_name=language_name(state.language) if state.language else None,
                language_prob=state.language_prob,
                complete=state.complete,
                files=list(state.outputs),
            )
        self._emit("state", **data)

    def _emit_coverage(self, active: Optional[Tuple[float, float]], force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self._last_cov_emit < 0.3:
            return
        self._last_cov_emit = now
        with self._lock:
            state = self.state
            data = dict(
                intervals=state.covered.to_list(),
                active=[round(active[0], 2), round(active[1], 2)] if active else None,
                duration=state.duration,
                speed=round(self._speed, 2) if self._speed else None,
            )
        self._emit("coverage", **data)


def _shift(seg: Segment, offset: float, limit: float) -> Optional[Segment]:
    start = seg.start + offset
    if start >= limit:
        return None
    end = min(seg.end + offset, limit + 0.5)
    words = None
    if seg.words:
        words = [Word(w.start + offset, min(w.end + offset, limit + 0.5), w.text) for w in seg.words]
    return Segment(start, max(end, start), seg.text, words, seg.no_speech_prob, seg.avg_logprob)


def run_job_sync(video: str, options: Dict[str, Any], emit: Emit, **kwargs: Any) -> SubtitleJob:
    job = SubtitleJob(video, options, emit, **kwargs)
    job.run()
    return job


__all__ = ["SubtitleJob", "run_job_sync", "IntervalSet"]
