from kosub.segmentation import Segment, Word, build_units, ends_sentence, is_hallucination


def _seg(words, lang_space=True):
    """[(start, end, text), ...] → Segment"""
    ws = [Word(s, e, (" " + t) if lang_space else t) for s, e, t in words]
    text = "".join(w.text for w in ws)
    return Segment(ws[0].start, ws[-1].end, text, ws)


def test_splits_multiple_sentences_in_one_segment():
    seg = _seg([
        (0.0, 0.4, "Hello"), (0.4, 0.9, "there."), (1.0, 1.3, "How"), (1.3, 1.5, "are"), (1.5, 2.0, "you?"),
        (2.4, 2.6, "I'm"), (2.6, 3.0, "fine,"), (3.0, 3.5, "thanks."),
    ])
    units = build_units([seg], "en")
    # 짧은 'Hello there.' 는 다음 문장과 합쳐질 수 있다
    texts = [u.text for u in units]
    assert "".join(t.replace(" ", "") for t in texts) == "Hellothere.Howareyou?I'mfine,thanks."
    assert len(units) >= 2
    assert all(u.parts is None for u in units)


def test_merges_sentence_continuing_across_segments():
    a = _seg([(0.0, 0.5, "I"), (0.5, 1.0, "went"), (1.0, 1.4, "to"), (1.4, 2.0, "the"), (2.0, 2.6, "store")])
    b = _seg([(2.7, 3.2, "and"), (3.2, 3.7, "bought"), (3.7, 4.0, "some"), (4.0, 4.6, "milk.")])
    units = build_units([a, b], "en")
    assert len(units) == 1
    assert units[0].text == "I went to the store and bought some milk."


def test_long_sentence_gets_parts_at_comma_or_pause():
    words = []
    t = 0.0
    tokens = "So yesterday I walked all the way across the old town, and then I finally found the little bookshop".split()
    for i, tok in enumerate(tokens):
        words.append((t, t + 0.45, tok))
        t += 0.5
        if tok.endswith(","):
            t += 0.6  # 쉼
    units = build_units([_seg(words)], "en")
    assert len(units) == 1
    u = units[0]
    assert u.parts and len(u.parts) >= 2
    assert u.parts[0]["t"].endswith("town,")
    for p in u.parts:
        assert p["e"] - p["s"] <= 7.0 + 1e-6


def test_abbreviation_does_not_split():
    seg = _seg([(0, 0.3, "Mr."), (0.3, 0.8, "Smith"), (0.8, 1.2, "is"), (1.2, 2.0, "here.")])
    units = build_units([seg], "en")
    assert [u.text for u in units] == ["Mr. Smith is here."]


def test_long_pause_breaks_sentence():
    seg = _seg([(0, 0.5, "Wait"), (0.5, 1.0, "for"), (1.0, 1.5, "it"), (4.0, 4.5, "Now"), (4.5, 5.0, "go")])
    units = build_units([seg], "en")
    assert [u.text for u in units] == ["Wait for it", "Now go"]


def test_cjk_without_word_timestamps():
    seg = Segment(0.0, 6.0, "今日は晴れです。散歩に行きましょう。", None)
    units = build_units([seg], "ja")
    assert [u.text for u in units] == ["今日は晴れです。", "散歩に行きましょう。"]
    assert 0.0 <= units[0].start < units[0].end <= units[1].start <= 6.0


def test_hallucination_filter_needs_low_confidence():
    assert is_hallucination(Segment(0, 2, " Thank you for watching!", None, 0.6, -0.9))
    assert not is_hallucination(Segment(0, 2, " Thank you for watching!", None, 0.01, -0.1))
    assert not is_hallucination(Segment(0, 2, " Hello world", None, 0.9, -2.0))
    assert ends_sentence("done.”") and not ends_sentence("and")
