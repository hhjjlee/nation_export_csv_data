"""음성 인식 결과(세그먼트/단어)를 문장 단위 자막으로 다시 나눈다.

- 문장 끝(. ? ! 。 등)과 긴 쉼에서 문장을 끊는다.
- 너무 짧은 문장은 이웃과 합쳐 읽기 편하게 만든다.
- 7초나 글자 수 제한을 넘는 긴 문장은 쉼/쉼표 위치에서 여러 자막 줄(parts)로 나눈다.
  번역은 문장 전체로 하고, 번역문은 원문 조각 비율대로 나눠 보여 준다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import List, Optional, Sequence, Tuple

from .languages import NO_SPACE_LANGUAGES

MAX_CUE_DURATION = 7.0
MAX_CHARS_SPACED = 84
MAX_CHARS_NOSPACE = 34
PAUSE_BREAK = 1.2  # 이보다 긴 쉼이면 문장을 끊는다
MIN_PART_DURATION = 0.8

SENTENCE_END = ".?!…。？！‼⁇⁈⁉"
CLOSERS = "\"'”’)]}」』】》〉»"
OPENERS = "\"'“‘([{¿¡«「『【《〈"
SOFT_BREAK = ",;:，、；：—–"
ABBREVIATIONS = {
    "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "u.s", "u.k",
    "mt", "lt", "col", "gen", "sgt", "capt", "no", "vol", "fig", "approx", "dept", "est",
    "sra", "srta", "hr", "fr", "nr", "bzw", "usw", "z.b",
}
CONJUNCTIONS_EN = {
    "and", "but", "so", "because", "which", "that", "or", "if", "when", "while", "although",
    "though", "where", "who", "then", "until", "unless", "since", "after", "before",
}

# 무음이나 음악 구간에서 Whisper가 자주 지어내는 문장 (신뢰도가 낮을 때만 버림)
_HALLUCINATIONS = {
    "thank you for watching", "thanks for watching", "thank you so much for watching",
    "please subscribe", "subscribe to my channel", "like and subscribe",
    "subtitles by the amara org community", "transcription by castingwords",
    "ご視聴ありがとうございました", "チャンネル登録お願いします",
    "字幕由amaraorg社区提供", "请不吝点赞订阅转发打赏支持明镜与点点栏目",
    "mbc 뉴스 이덕영입니다", "시청해주셔서 감사합니다", "시청해 주셔서 감사합니다",
    "구독과 좋아요 부탁드립니다", "구독 좋아요 알림설정 부탁드립니다",
    "untertitel der amaraorg community", "sous-titres réalisés par la communauté damaraorg",
}


@dataclass
class Word:
    start: float
    end: float
    text: str  # Whisper 출력 그대로 (영어 등은 앞에 공백 포함)


@dataclass
class Segment:
    start: float
    end: float
    text: str
    words: Optional[List[Word]] = None
    no_speech_prob: float = 0.0
    avg_logprob: float = 0.0


@dataclass
class DraftUnit:
    start: float
    end: float
    text: str
    parts: Optional[List[dict]] = field(default=None)


def limits(lang: Optional[str]) -> Tuple[float, int]:
    if (lang or "") in NO_SPACE_LANGUAGES:
        return MAX_CUE_DURATION, MAX_CHARS_NOSPACE
    return MAX_CUE_DURATION, MAX_CHARS_SPACED


def _normalize(text: str) -> str:
    text = text.lower()
    text = re.sub(r"[\s\.,!?。！？、，…·'\"“”‘’\-–—:;()\[\]{}♪]+", " ", text)
    return " ".join(text.split())


_NORMALIZED_HALLUCINATIONS = {_normalize(h) for h in _HALLUCINATIONS}
_NORMALIZED_HALLUCINATIONS |= {h.replace(" ", "") for h in _NORMALIZED_HALLUCINATIONS}


def is_hallucination(seg: Segment) -> bool:
    norm = _normalize(seg.text)
    if not norm:
        return True
    if norm in _NORMALIZED_HALLUCINATIONS or norm.replace(" ", "") in _NORMALIZED_HALLUCINATIONS:
        return seg.no_speech_prob > 0.2 or seg.avg_logprob < -0.5
    return False


def ends_sentence(text: str) -> bool:
    t = text.strip().rstrip(CLOSERS)
    return bool(t) and t[-1] in SENTENCE_END


def join_words(words: Sequence[Word], lang: Optional[str]) -> str:
    text = "".join(w.text for w in words)
    if (lang or "") in NO_SPACE_LANGUAGES:
        return text.strip()
    return " ".join(text.split())


def _duration(words: Sequence[Word]) -> float:
    return max(w.end for w in words) - words[0].start if words else 0.0


def _pseudo_words(seg: Segment, lang: Optional[str]) -> List[Word]:
    """단어 타임스탬프가 없을 때 글자 수 비율로 시간을 나눠 가짜 단어를 만든다."""
    text = seg.text.strip()
    if not text:
        return []
    if (lang or "") in NO_SPACE_LANGUAGES:
        tokens: List[str] = []
        for ch in text:
            if tokens and (ch in SENTENCE_END + SOFT_BREAK + CLOSERS or ch.isspace()):
                tokens[-1] += ch
            else:
                tokens.append(ch)
    else:
        tokens = [" " + t for t in text.split()]
    total = sum(len(t.strip()) or 1 for t in tokens)
    span = max(seg.end - seg.start, 0.01)
    words, cur = [], seg.start
    for tok in tokens:
        d = span * (len(tok.strip()) or 1) / total
        words.append(Word(cur, cur + d, tok))
        cur += d
    return words


def segments_to_words(segments: Sequence[Segment], lang: Optional[str]) -> List[Word]:
    words: List[Word] = []
    for seg in segments:
        ws = [w for w in (seg.words or []) if w.text.strip()]
        if not ws:
            ws = _pseudo_words(seg, lang)
        words.extend(Word(float(w.start), float(w.end), w.text) for w in ws)
    # 시간이 거꾸로 가는 단어를 바로잡는다
    for i, w in enumerate(words):
        if i and w.start < words[i - 1].start:
            w.start = words[i - 1].start
        if w.end < w.start:
            w.end = w.start
    return words


def _is_sentence_end(word: str, next_word: Optional[str], lang: Optional[str]) -> bool:
    t = word.strip().rstrip(CLOSERS)
    if not t or t[-1] not in SENTENCE_END:
        return False
    if (lang or "") in NO_SPACE_LANGUAGES:
        return True
    if t.endswith(".") and not t.endswith(".."):
        core = t.rstrip(".").lstrip(OPENERS).lower()
        if core in ABBREVIATIONS or (len(core) == 1 and core.isalpha()):
            return False
    if next_word:
        nt = next_word.strip().lstrip(OPENERS)
        if nt and nt[0].isalpha() and nt[0].islower():
            return False  # "I was... thinking" 처럼 문장이 이어지는 경우
    return True


def split_sentences(words: Sequence[Word], lang: Optional[str]) -> List[List[Word]]:
    sentences: List[List[Word]] = []
    cur: List[Word] = []
    for i, w in enumerate(words):
        if cur and w.start - cur[-1].end >= PAUSE_BREAK:
            sentences.append(cur)
            cur = []
        cur.append(w)
        nxt = words[i + 1].text if i + 1 < len(words) else None
        if _is_sentence_end(w.text, nxt, lang):
            sentences.append(cur)
            cur = []
    if cur:
        sentences.append(cur)
    return sentences


def merge_short(sentences: List[List[Word]], lang: Optional[str]) -> List[List[Word]]:
    max_dur, max_chars = limits(lang)
    short_chars = 4 if (lang or "") in NO_SPACE_LANGUAGES else 10

    def is_short(ws: List[Word]) -> bool:
        return _duration(ws) < 1.0 or len(join_words(ws, lang)) < short_chars

    def fits(ws: List[Word]) -> bool:
        return _duration(ws) <= max_dur * 0.85 and len(join_words(ws, lang)) <= max_chars * 0.8

    result: List[List[Word]] = []
    for s in sentences:
        if result:
            prev = result[-1]
            gap = s[0].start - prev[-1].end
            if gap <= 0.6 and (is_short(prev) or is_short(s)) and fits(prev + s):
                result[-1] = prev + s
                continue
        result.append(s)
    return result


def split_long(words: List[Word], lang: Optional[str], depth: int = 0) -> List[List[Word]]:
    max_dur, max_chars = limits(lang)
    text_len = len(join_words(words, lang))
    if (_duration(words) <= max_dur and text_len <= max_chars) or len(words) < 2 or depth > 8:
        return [words]
    best_i, best_score = None, float("-inf")
    for i in range(1, len(words)):
        left, right = words[:i], words[i:]
        if _duration(left) < MIN_PART_DURATION or _duration(right) < MIN_PART_DURATION:
            continue
        pause = max(0.0, words[i].start - words[i - 1].end)
        prev = words[i - 1].text.strip().rstrip(CLOSERS)
        punct = 0.0
        if prev and prev[-1] in SENTENCE_END:
            punct = 3.0
        elif prev and prev[-1] in SOFT_BREAK:
            punct = 2.0
        elif lang == "en" and words[i].text.strip().lower() in CONJUNCTIONS_EN:
            punct = 1.0
        left_len = len(join_words(left, lang))
        balance = 1.0 - abs(left_len - (text_len - left_len)) / max(text_len, 1)
        score = punct + 2.0 * min(pause, 1.0) + 1.5 * balance
        if score > best_score:
            best_i, best_score = i, score
    if best_i is None:
        return [words]
    return split_long(words[:best_i], lang, depth + 1) + split_long(words[best_i:], lang, depth + 1)


def build_units(segments: Sequence[Segment], lang: Optional[str]) -> List[DraftUnit]:
    """세그먼트 묶음을 번역 단위(문장) 목록으로 바꾼다."""
    words = segments_to_words(segments, lang)
    if not words:
        return []
    units: List[DraftUnit] = []
    for sent in merge_short(split_sentences(words, lang), lang):
        text = join_words(sent, lang)
        if not text:
            continue
        pieces = split_long(sent, lang)
        parts = None
        if len(pieces) > 1:
            parts = [
                {"s": p[0].start, "e": max(w.end for w in p), "t": join_words(p, lang)}
                for p in pieces
                if join_words(p, lang)
            ]
            if len(parts) < 2:
                parts = None
        units.append(DraftUnit(sent[0].start, max(w.end for w in sent), text, parts))
    return units
