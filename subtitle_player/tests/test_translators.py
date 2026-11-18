import io
import json
import types
import urllib.parse

import pytest

from kosub import translators
from kosub.translators import ClaudeTranslator, GoogleTranslator, TranslatorFatalError


class _FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _google_payload(text: str) -> bytes:
    # 실제 응답처럼 문장별 조각으로 나눠 돌려준다
    pieces = [[p + ("\n" if i < len(text.split("\n")) - 1 else ""), "src", None, None, 10]
              for i, p in enumerate(text.split("\n"))]
    return json.dumps([pieces, None, "en"]).encode("utf-8")


def test_google_batches_lines_and_keeps_order(monkeypatch):
    calls = []

    def fake_urlopen(req, timeout=0):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(req.full_url).query)
        q = query["q"][0]
        calls.append((query["sl"][0], q))
        translated = "\n".join(f"번역<{line}>" for line in q.split("\n"))
        return _FakeResponse(_google_payload(translated))

    monkeypatch.setattr(translators.urllib.request, "urlopen", fake_urlopen)
    tr = GoogleTranslator()
    tr._last_call = 0
    out = tr.translate(["Hello.", "♪ ♪", "How are you?"], "en")
    assert out == ["번역<Hello.>", "♪ ♪", "번역<How are you?>"]
    assert calls[0][0] == "en" and calls[0][1] == "Hello.\nHow are you?"


def test_google_falls_back_to_line_by_line_when_lines_merge(monkeypatch):
    def fake_urlopen(req, timeout=0):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(req.full_url).query)["q"][0]
        translated = q.replace("\n", " ")  # 줄바꿈이 사라진 응답
        return _FakeResponse(_google_payload("번역 " + translated))

    monkeypatch.setattr(translators.urllib.request, "urlopen", fake_urlopen)
    out = GoogleTranslator().translate(["One.", "Two."], "en")
    assert out == ["번역 One.", "번역 Two."]


class _Block:
    def __init__(self, text):
        self.type = "text"
        self.text = text


def _claude_with_fake_client(responder):
    tr = ClaudeTranslator.__new__(ClaudeTranslator)
    import anthropic

    tr._anthropic = anthropic
    tr.model = "claude-opus-5-5"
    tr.effort = "low"
    calls = []

    def create(**kwargs):
        calls.append(kwargs)
        return responder(kwargs)

    tr.client = types.SimpleNamespace(beta=types.SimpleNamespace(messages=types.SimpleNamespace(create=create)))
    return tr, calls


def test_claude_request_shape_and_parsing():
    def responder(kwargs):
        lines = json.loads(kwargs["messages"][0]["content"])["lines"]
        data = {"translations": [{"id": l["id"], "ko": f"한국어 {l['text']}"} for l in reversed(lines)]}
        return types.SimpleNamespace(stop_reason="end_turn", content=[_Block(json.dumps(data, ensure_ascii=False))])

    tr, calls = _claude_with_fake_client(responder)
    out = tr.translate(["Hi.", "Bye."], "en", context=[("Earlier.", "앞 문장.")])
    assert out == ["한국어 Hi.", "한국어 Bye."]
    req = calls[0]
    assert req["model"] == "claude-opus-5-5"
    assert req["fallbacks"] == "default" and "server-side-fallback-2026-07-01" in req["betas"]
    assert req["output_config"]["effort"] == "low"
    assert req["output_config"]["format"]["type"] == "json_schema"
    payload = json.loads(req["messages"][0]["content"])
    assert payload["earlier_context"] == [{"source": "Earlier.", "korean": "앞 문장."}]
    assert "temperature" not in req and "thinking" not in req


def test_claude_refusal_and_missing_ids_become_none():
    def responder(kwargs):
        lines = json.loads(kwargs["messages"][0]["content"])["lines"]
        if len(lines) == 1:
            return types.SimpleNamespace(stop_reason="refusal", content=[])
        data = {"translations": [{"id": 0, "ko": "첫째"}]}
        return types.SimpleNamespace(stop_reason="end_turn", content=[_Block(json.dumps(data))])

    tr, _ = _claude_with_fake_client(responder)
    assert tr.translate(["one", "two"], "en") == ["첫째", None]
    assert tr.translate(["only"], "en") == [None]


def test_claude_bad_key_is_fatal():
    import anthropic
    import httpx2

    def responder(kwargs):
        req = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")
        resp = httpx2.Response(401, request=req, json={"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key"}})
        raise anthropic.AuthenticationError("invalid x-api-key", response=resp, body=None)

    tr, _ = _claude_with_fake_client(responder)
    with pytest.raises(TranslatorFatalError):
        tr.translate(["Hello"], "en")


def test_make_translator_choices(monkeypatch):
    monkeypatch.delenv("KOSUB_FAKE", raising=False)
    assert translators.make_translator({"translator": "none"}) is None
    assert isinstance(translators.make_translator({"translator": "google"}), GoogleTranslator)
    tr = translators.make_translator({"translator": "claude", "claude_api_key": "sk-test", "claude_model": "claude-sonnet-5-5"})
    assert isinstance(tr, ClaudeTranslator) and tr.model == "claude-sonnet-5-5"
