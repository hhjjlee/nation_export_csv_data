"""자막 데이터 구조, 화면/파일용 큐(cue) 생성, SRT 읽기/쓰기."""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional, Sequence

from .languages import uses_spaces

# 한 줄 최대 글자 수 (방송 자막 관례 참고)
KO_LINE = 22
LATIN_LINE = 42
CJK_LINE = 18

# 읽기 속도(초당 글자 수)와 표시 시간 보정값
CPS_KO = 14.0
CPS_LATIN = 17.0
MIN_DURATION = 0.8
LINGER = 0.3
MIN_GAP = 0.05

_SPLIT_AFTER = ".?!…,;:。？！，、；："


@dataclass
class Unit:
    """번역 단위 (보통 한 문장). 너무 길면 parts로 여러 자막 줄에 나눠 표시한다."""

    id: int
    start: float
    end: float
    text: str  # 원문
    ko: Optional[str] = None  # 한국어 번역
    parts: Optional[List[Dict[str, Any]]] = None  # [{"s": 시작, "e": 끝, "t": 원문 조각}]
    edited: bool = False  # 사용자가 직접 고친 번역

    def to_dict(self) -> Dict[str, Any]:
        d: Dict[str, Any] = {
            "id": self.id,
            "start": round(self.start, 3),
            "end": round(self.end, 3),
            "text": self.text,
            "ko": self.ko,
        }
        if self.parts:
            d["parts"] = [{"s": round(p["s"], 3), "e": round(p["e"], 3), "t": p["t"]} for p in self.parts]
        if self.edited:
            d["edited"] = True
        return d

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "Unit":
        parts = d.get("parts") or None
        if parts:
            parts = [{"s": float(p["s"]), "e": float(p["e"]), "t": str(p["t"])} for p in parts]
        ko = d.get("ko")
        return cls(
            id=int(d["id"]),
            start=float(d["start"]),
            end=float(d["end"]),
            text=str(d.get("text") or ""),
            ko=None if ko is None else str(ko),
            parts=parts,
            edited=bool(d.get("edited", False)),
        )


@dataclass
class Cue:
    """화면이나 SRT 파일에 실제로 표시되는 자막 한 덩어리."""

    start: float
    end: float
    text: str  # 표시 텍스트 (한국어, 번역 전이면 원문)
    src: str = ""  # 원문
    unit_id: int = -1
    translated: bool = True


def split_by_weights(text: str, weights: Sequence[float]) -> Optional[List[str]]:
    """번역문을 원문 조각 길이 비율대로 나눈다. 빈 조각이 생기면 None."""
    n = len(weights)
    text = " ".join(text.split())
    if n <= 1:
        return [text]
    total = float(sum(weights)) or 1.0
    pieces: List[str] = []
    rest = text
    remaining = total
    for i in range(n - 1):
        frac = weights[i] / remaining if remaining > 0 else 1.0 / (n - i)
        remaining -= weights[i]
        ideal = frac * len(rest)
        cut = _best_cut(rest, ideal)
        left, rest = rest[:cut].strip(), rest[cut:].strip()
        if not left or not rest:
            return None
        pieces.append(left)
    pieces.append(rest)
    return pieces


def _best_cut(text: str, ideal: float) -> int:
    """ideal 위치 근처에서 공백/문장부호 뒤를 자를 위치로 고른다."""
    if len(text) < 2:
        return len(text)
    best, best_score = None, math.inf
    for i in range(1, len(text)):
        if text[i] == " ":
            bonus = 0.6 if text[i - 1] in _SPLIT_AFTER else 0.0
        elif text[i - 1] in _SPLIT_AFTER and text[i] != " ":
            bonus = 0.3
        else:
            continue
        score = abs(i - ideal) / max(len(text), 1) - bonus * 0.15
        if score < best_score:
            best, best_score = i, score
    if best is None:
        best = int(round(ideal))
    return min(max(best, 1), len(text) - 1)


def wrap_text(text: str, max_len: int, spaced: bool = True, max_lines: int = 3) -> str:
    """긴 자막을 길이가 비슷한 여러 줄로 나눈다."""
    text = " ".join(text.split()) if spaced else text.strip()
    if len(text) <= max_len:
        return text
    lines = min(max_lines, math.ceil(len(text) / max_len))
    return "\n".join(_balanced_split(text, lines, spaced))


def _balanced_split(text: str, lines: int, spaced: bool) -> List[str]:
    if lines <= 1 or len(text) < 2:
        return [text]
    ideal = len(text) / lines
    if spaced:
        cut = _best_cut(text, ideal)
    else:
        cut = None
        window = max(2, int(ideal * 0.3))
        for delta in range(0, window + 1):
            for i in (int(ideal) + delta, int(ideal) - delta):
                if 0 < i < len(text) and text[i - 1] in _SPLIT_AFTER:
                    cut = i
                    break
            if cut:
                break
        if cut is None:
            cut = max(1, min(len(text) - 1, int(round(ideal))))
    left, right = text[:cut].rstrip(), text[cut:].lstrip()
    if not left or not right:
        return [text]
    return [left] + _balanced_split(right, lines - 1, spaced)


def _reading_time(text: str, korean: bool) -> float:
    cps = CPS_KO if korean else CPS_LATIN
    return min(6.0, len(text) / cps)


def build_cues(units: Iterable[Unit], mode: str = "ko") -> List[Cue]:
    """번역 단위들을 표시용 큐로 바꾼다.

    mode: "ko" = 한국어(번역 전이면 원문), "src" = 원문
    긴 단위는 원문 조각 비율대로 번역문도 나누고, 읽기 시간을 고려해 끝 시간을 살짝 늘린다.
    """
    cues: List[Cue] = []
    for u in sorted(units, key=lambda x: (x.start, x.id)):
        ko = u.ko.strip() if (u.ko and u.ko.strip()) else None
        translated = ko is not None
        parts = u.parts if (u.parts and len(u.parts) > 1) else None
        if parts:
            srcs = [p["t"] for p in parts]
            if mode == "src":
                texts: Optional[List[str]] = srcs
            elif ko is not None:
                texts = split_by_weights(ko, [max(1, len(s)) for s in srcs])
            else:
                texts = srcs
            if texts is not None:
                for p, t, s in zip(parts, texts, srcs):
                    cues.append(Cue(p["s"], p["e"], t, s, u.id, translated))
                continue
        text = u.text if mode == "src" else (ko if ko is not None else u.text)
        cues.append(Cue(u.start, u.end, text, u.text, u.id, translated))
    _polish(cues, korean=(mode != "src"))
    return cues


def _polish(cues: List[Cue], korean: bool) -> None:
    """겹침을 없애고, 너무 짧게 지나가는 자막은 다음 자막 전까지 조금 더 보여 준다."""
    for i, c in enumerate(cues):
        nxt = cues[i + 1].start if i + 1 < len(cues) else c.end + 30.0
        if c.end > nxt - MIN_GAP:
            c.end = max(c.start + 0.2, nxt - MIN_GAP)
        want = max(c.end + LINGER, c.start + max(MIN_DURATION, _reading_time(c.text, korean)))
        c.end = max(c.end, min(want, nxt - MIN_GAP))


# ---------------------------------------------------------------------------
# SRT


def srt_time(t: float) -> str:
    ms = max(0, int(round(t * 1000)))
    h, rem = divmod(ms, 3_600_000)
    m, rem = divmod(rem, 60_000)
    s, ms = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def format_clock(t: float) -> str:
    t = max(0, int(t))
    h, rem = divmod(t, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def cues_to_srt(cues: Sequence[Cue], mode: str = "ko", src_lang: Optional[str] = None) -> str:
    """mode: "ko"(한국어), "src"(원문), "both"(한국어 + 원문 이중 자막)."""
    spaced = uses_spaces(src_lang)
    src_line = LATIN_LINE if spaced else CJK_LINE
    blocks = []
    for i, c in enumerate(cues, 1):
        if mode == "src":
            body = wrap_text(c.src or c.text, src_line, spaced)
        elif mode == "both":
            body = wrap_text(c.text, KO_LINE)
            if c.src and c.src != c.text:
                body += "\n" + wrap_text(c.src, src_line, spaced)
        else:
            body = wrap_text(c.text, KO_LINE)
        if not body.strip():
            continue
        blocks.append(f"{len(blocks) + 1}\n{srt_time(c.start)} --> {srt_time(c.end)}\n{body}\n")
    return "\n".join(blocks)


_TIME_RE = re.compile(
    r"(\d{1,2}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})"
)


def parse_srt(text: str) -> List[Cue]:
    """SRT(또는 비슷한 형식) 텍스트를 큐 목록으로 읽는다."""
    text = text.lstrip("﻿").replace("\r\n", "\n").replace("\r", "\n")
    cues: List[Cue] = []
    for block in re.split(r"\n\s*\n", text):
        lines = [ln for ln in block.split("\n") if ln.strip()]
        for idx, line in enumerate(lines):
            m = _TIME_RE.search(line)
            if not m:
                continue
            g = [int(x) for x in m.groups()]
            ms1 = int(m.group(4).ljust(3, "0"))
            ms2 = int(m.group(8).ljust(3, "0"))
            start = g[0] * 3600 + g[1] * 60 + g[2] + ms1 / 1000
            end = g[4] * 3600 + g[5] * 60 + g[6] + ms2 / 1000
            body = "\n".join(lines[idx + 1 :])
            body = re.sub(r"<[^>]+>", "", body)  # <i> 같은 태그 제거
            if body.strip():
                cues.append(Cue(start, end, body.strip(), "", len(cues) + 1, True))
            break
    cues.sort(key=lambda c: c.start)
    return cues


def cues_to_units(cues: Sequence[Cue]) -> List[Unit]:
    """외부 SRT를 화면 표시용 단위로 바꾼다 (원문 = 번역문)."""
    units = []
    for i, c in enumerate(cues, 1):
        flat = " ".join(c.text.split())
        units.append(Unit(id=i, start=c.start, end=c.end, text=flat, ko=flat))
    return units
