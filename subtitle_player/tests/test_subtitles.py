from kosub.subtitles import (
    Cue,
    Unit,
    build_cues,
    cues_to_srt,
    cues_to_units,
    parse_srt,
    split_by_weights,
    srt_time,
    wrap_text,
)


def test_srt_time_format():
    assert srt_time(0) == "00:00:00,000"
    assert srt_time(3661.2346) == "01:01:01,235"
    assert srt_time(-1) == "00:00:00,000"


def test_wrap_text_balances_lines():
    text = "오늘은 날씨가 정말 좋아서 공원에 산책을 가기로 했어요"
    wrapped = wrap_text(text, 22)
    lines = wrapped.split("\n")
    assert len(lines) == 2
    assert all(len(l) <= 22 for l in lines)
    assert "".join(lines).replace(" ", "") == text.replace(" ", "")
    assert wrap_text("짧은 문장", 22) == "짧은 문장"


def test_wrap_text_without_spaces():
    text = "今日はとても天気が良いので、公園に散歩に行くことにしました。"
    lines = wrap_text(text, 18, spaced=False).split("\n")
    assert len(lines) == 2
    assert "".join(lines) == text
    assert lines[0].endswith("、")  # 문장부호 뒤에서 나눔


def test_split_by_weights_follows_source_ratio():
    ko = "어제 우리는 아주 멀리 있는 산에 올라가서 해가 지는 것을 오래 바라보았다"
    pieces = split_by_weights(ko, [30, 30])
    assert pieces is not None and len(pieces) == 2
    assert " ".join(pieces) == ko
    assert abs(len(pieces[0]) - len(pieces[1])) < 12
    assert split_by_weights("네", [10, 10]) is None


def test_build_cues_splits_parts_and_extends_short_cues():
    units = [
        Unit(1, 0.0, 0.4, "Hi.", "안녕."),
        Unit(
            2,
            5.0,
            14.0,
            "This is a very long sentence, and it keeps going for a while.",
            "이것은 아주 긴 문장이고 한동안 계속 이어집니다.",
            parts=[
                {"s": 5.0, "e": 9.0, "t": "This is a very long sentence,"},
                {"s": 9.2, "e": 14.0, "t": "and it keeps going for a while."},
            ],
        ),
        Unit(3, 14.0, 15.0, "Untranslated", None),
    ]
    cues = build_cues(units)
    assert [c.unit_id for c in cues] == [1, 2, 2, 3]
    assert cues[0].end >= 0.8  # 최소 표시 시간
    assert cues[1].start == 5.0 and cues[2].start == 9.2
    assert cues[1].end <= cues[2].start  # 겹치지 않음
    assert cues[3].text == "Untranslated" and not cues[3].translated
    src = build_cues(units, mode="src")
    assert src[1].text == "This is a very long sentence,"


def test_srt_roundtrip_and_bilingual():
    units = [Unit(1, 1.0, 3.0, "Hello there.", "안녕하세요."), Unit(2, 4.0, 6.0, "Bye.", "잘 가요.")]
    cues = build_cues(units)
    srt = cues_to_srt(cues, mode="ko")
    parsed = parse_srt("﻿" + srt.replace("\n", "\r\n"))
    assert [c.text for c in parsed] == ["안녕하세요.", "잘 가요."]
    assert abs(parsed[0].start - 1.0) < 1e-6
    both = cues_to_srt(cues, mode="both", src_lang="en")
    assert "안녕하세요.\nHello there." in both
    restored = cues_to_units(parsed)
    assert restored[1].ko == "잘 가요."


def test_parse_srt_handles_tags_and_dot_millis():
    text = "1\n00:00:01.5 --> 00:00:02.25\n<i>기울임</i>\n\n2\n00:00:03,000 --> 00:00:04,000\n두 번째\n"
    cues = parse_srt(text)
    assert cues[0].text == "기울임"
    assert abs(cues[0].start - 1.5) < 1e-6 and abs(cues[0].end - 2.25) < 1e-6
    assert isinstance(cues[1], Cue)
