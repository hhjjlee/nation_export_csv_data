"""자막 번역기: 구글 번역(무료, 키 불필요)과 Claude(고품질, API 키 필요)."""

from __future__ import annotations

import json
import logging
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional, Sequence, Tuple

from .languages import google_code, language_name

log = logging.getLogger(__name__)

Context = Sequence[Tuple[str, str]]  # (원문, 번역문) — 앞쪽 자막


class TranslatorError(RuntimeError):
    """일시적인 오류 (잠시 후 다시 시도)."""


class TranslatorFatalError(TranslatorError):
    """설정 문제 (API 키 오류 등) — 다시 시도해도 소용없음."""


_HAS_LETTER = re.compile(r"[^\W\d_]", re.UNICODE)


def _needs_translation(text: str) -> bool:
    return bool(_HAS_LETTER.search(text or ""))


class BaseTranslator:
    name = "base"
    label = ""
    batch_size = 40

    def translate(self, texts: Sequence[str], src_lang: str, context: Context = ()) -> List[Optional[str]]:
        """texts를 한국어로 번역한다. 실패한 항목은 None."""
        todo = [i for i, t in enumerate(texts) if _needs_translation(t)]
        out: List[Optional[str]] = [t if not _needs_translation(t) else None for t in texts]
        if todo:
            results = self._translate([" ".join(texts[i].split()) for i in todo], src_lang, context)
            for i, r in zip(todo, results):
                out[i] = r
        return out

    def _translate(self, texts: List[str], src_lang: str, context: Context) -> List[Optional[str]]:
        raise NotImplementedError


# ---------------------------------------------------------------------------
# 구글 번역 (공개 웹 엔드포인트)


class GoogleTranslator(BaseTranslator):
    name = "google"
    label = "구글 번역"
    batch_size = 40
    URL = "https://translate.googleapis.com/translate_a/single"
    MAX_QUERY_BYTES = 5000  # URL 길이 제한을 넘지 않도록

    def __init__(self, timeout: float = 20.0):
        self.timeout = timeout
        self._last_call = 0.0

    def _request(self, text: str, sl: str) -> str:
        params = urllib.parse.urlencode(
            {"client": "gtx", "sl": sl, "tl": "ko", "dt": "t", "ie": "UTF-8", "oe": "UTF-8", "q": text}
        )
        req = urllib.request.Request(
            f"{self.URL}?{params}",
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) KoSubPlayer"},
        )
        delay = 1.0
        for attempt in range(5):
            wait = 0.25 - (time.monotonic() - self._last_call)
            if wait > 0:
                time.sleep(wait)
            self._last_call = time.monotonic()
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    payload = json.loads(resp.read().decode("utf-8"))
                return "".join(part[0] for part in (payload[0] or []) if part and part[0])
            except urllib.error.HTTPError as exc:
                if exc.code in (429, 500, 502, 503, 504) and attempt < 4:
                    time.sleep(delay)
                    delay *= 2
                    continue
                raise TranslatorError(f"구글 번역 응답 오류 (HTTP {exc.code})") from exc
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                if attempt < 2:
                    time.sleep(delay)
                    delay *= 2
                    continue
                raise TranslatorError(f"구글 번역 서버에 연결하지 못했습니다: {exc}") from exc
            except (ValueError, IndexError, TypeError) as exc:
                raise TranslatorError("구글 번역 응답을 해석하지 못했습니다") from exc
        raise TranslatorError("구글 번역 실패")

    def _translate(self, texts: List[str], src_lang: str, context: Context) -> List[Optional[str]]:
        sl = google_code(src_lang)
        out: List[Optional[str]] = []
        batch: List[str] = []
        size = 0
        for t in texts:
            cost = len(urllib.parse.quote(t)) + 3
            if batch and (size + cost > self.MAX_QUERY_BYTES or len(batch) >= self.batch_size):
                out.extend(self._translate_lines(batch, sl))
                batch, size = [], 0
            batch.append(t)
            size += cost
        if batch:
            out.extend(self._translate_lines(batch, sl))
        return out

    def _translate_lines(self, lines: List[str], sl: str) -> List[Optional[str]]:
        if len(lines) == 1:
            return [self._request(lines[0], sl).strip() or None]
        result = self._request("\n".join(lines), sl)
        parts = [p.strip() for p in result.split("\n")]
        while parts and not parts[-1]:
            parts.pop()
        if len(parts) == len(lines):
            return [p or None for p in parts]
        # 줄 수가 맞지 않으면 한 줄씩 번역한다
        log.info("구글 번역 줄 수 불일치 (%d → %d), 줄 단위로 다시 번역", len(lines), len(parts))
        return [self._request(line, sl).strip() or None for line in lines]


# ---------------------------------------------------------------------------
# Claude (Anthropic API)

_CLAUDE_SYSTEM = """You are a professional subtitle translator. Translate each subtitle line of a video into natural, fluent Korean for on-screen subtitles.

Rules:
- Return exactly one Korean translation for every input line, keyed by the same id. Never merge, split, skip or reorder lines.
- The lines are consecutive subtitles from one video. Use the surrounding lines and the earlier context to resolve meaning, names, gender, tone and politeness, but each output must translate only its own line.
- Write natural spoken Korean that matches the speaker's tone and relationship (반말/존댓말). Keep it concise and easy to read quickly.
- Keep names and terminology consistent. Use the commonly used Korean spelling for proper nouns.
- Sound or music cues such as [Music] or ♪ should become their Korean equivalents (e.g. [음악], ♪).
- If a line is already Korean, return it unchanged."""

_CLAUDE_SCHEMA: Dict[str, Any] = {
    "type": "object",
    "properties": {
        "translations": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"id": {"type": "integer"}, "ko": {"type": "string"}},
                "required": ["id", "ko"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["translations"],
    "additionalProperties": False,
}


class ClaudeTranslator(BaseTranslator):
    name = "claude"
    label = "Claude"
    batch_size = 30

    def __init__(self, api_key: str = "", model: str = "claude-opus-5-5", effort: str = "low"):
        try:
            import anthropic
        except ImportError as exc:
            raise TranslatorFatalError("anthropic 패키지가 설치되어 있지 않습니다 (pip install anthropic)") from exc
        self._anthropic = anthropic
        # 키를 비워 두면 환경 변수 ANTHROPIC_API_KEY 또는 `ant auth login` 프로필을 쓴다.
        kwargs: Dict[str, Any] = {"max_retries": 3, "timeout": 180.0}
        if api_key.strip():
            kwargs["api_key"] = api_key.strip()
        try:
            self.client = anthropic.Anthropic(**kwargs)
        except Exception as exc:
            raise TranslatorFatalError(f"Claude 클라이언트를 만들 수 없습니다 (API 키를 확인하세요): {exc}") from exc
        self.model = model or "claude-opus-5-5"
        self.effort = effort if effort in ("low", "medium", "high", "xhigh", "max") else "low"

    def _translate(self, texts: List[str], src_lang: str, context: Context) -> List[Optional[str]]:
        out: List[Optional[str]] = []
        ctx = list(context)
        for i in range(0, len(texts), self.batch_size):
            chunk = texts[i : i + self.batch_size]
            res = self._call(chunk, src_lang, ctx)
            out.extend(res)
            ctx = (ctx + [(s, k) for s, k in zip(chunk, res) if k])[-6:]
        return out

    def _call(self, lines: List[str], src_lang: str, context: Context) -> List[Optional[str]]:
        anthropic = self._anthropic
        payload = {
            "source_language": f"{language_name(src_lang)} ({src_lang})" if src_lang else "auto-detect",
            "earlier_context": [{"source": s, "korean": k} for s, k in list(context)[-6:]],
            "lines": [{"id": i, "text": t} for i, t in enumerate(lines)],
        }
        try:
            response = self.client.beta.messages.create(
                model=self.model,
                max_tokens=16000,
                betas=["server-side-fallback-2026-07-01"],
                # 정책 분류기가 거절하면 서버가 기본 대체 모델로 다시 시도한다
                fallbacks="default",
                system=_CLAUDE_SYSTEM,
                output_config={
                    "effort": self.effort,
                    "format": {"type": "json_schema", "schema": _CLAUDE_SCHEMA},
                },
                messages=[{"role": "user", "content": json.dumps(payload, ensure_ascii=False)}],
            )
        except anthropic.AuthenticationError as exc:
            raise TranslatorFatalError("Claude API 키가 올바르지 않습니다. 설정에서 키를 확인하세요.") from exc
        except anthropic.PermissionDeniedError as exc:
            raise TranslatorFatalError("이 API 키로는 선택한 Claude 모델을 쓸 수 없습니다.") from exc
        except anthropic.NotFoundError as exc:
            raise TranslatorFatalError(f"Claude 모델을 찾을 수 없습니다: {self.model}") from exc
        except anthropic.BadRequestError as exc:
            if len(lines) > 1:  # 요청이 너무 크거나 특정 줄이 문제면 반으로 나눠 다시
                half = len(lines) // 2
                return self._call(lines[:half], src_lang, context) + self._call(lines[half:], src_lang, context)
            raise TranslatorError(f"Claude 요청 오류: {exc.message}") from exc
        except anthropic.RateLimitError as exc:
            raise TranslatorError("Claude 사용량 한도에 걸렸습니다. 잠시 후 다시 시도합니다.") from exc
        except anthropic.APIStatusError as exc:
            raise TranslatorError(f"Claude 서버 오류 ({exc.status_code})") from exc
        except anthropic.APIConnectionError as exc:
            raise TranslatorError("Claude 서버에 연결하지 못했습니다. 인터넷 연결을 확인하세요.") from exc

        if response.stop_reason == "max_tokens" and len(lines) > 1:
            half = len(lines) // 2
            return self._call(lines[:half], src_lang, context) + self._call(lines[half:], src_lang, context)
        if response.stop_reason == "refusal":
            log.warning("Claude가 번역을 거절했습니다 (%d줄)", len(lines))
            return [None] * len(lines)
        text = "".join(b.text for b in response.content if getattr(b, "type", "") == "text")
        try:
            data = json.loads(text)
            items = data["translations"]
        except (ValueError, KeyError, TypeError) as exc:
            raise TranslatorError("Claude 응답을 해석하지 못했습니다") from exc
        result: List[Optional[str]] = [None] * len(lines)
        for item in items:
            try:
                idx = int(item["id"])
            except (KeyError, TypeError, ValueError):
                continue
            if 0 <= idx < len(lines):
                ko = " ".join(str(item.get("ko", "")).split())
                result[idx] = ko or None
        return result


def make_translator(options: Dict[str, Any]) -> Optional[BaseTranslator]:
    """설정에 맞는 번역기를 만든다. '번역 안 함'이면 None."""
    kind = (options.get("translator") or "google").lower()
    if kind == "none":
        return None
    if os.environ.get("KOSUB_FAKE"):
        from .fakes import FakeTranslator

        return FakeTranslator()
    if kind == "claude":
        return ClaudeTranslator(
            api_key=options.get("claude_api_key") or "",
            model=options.get("claude_model") or "claude-opus-5-5",
            effort=options.get("claude_effort") or "low",
        )
    return GoogleTranslator()
