import pytest

from app import codec, priority, templates


def _every(kind):
    """Reviewed and pending-review texts together."""
    return {**templates.TEMPLATES[kind], **templates.CONTRIB_TEMPLATES[kind]}


@pytest.mark.parametrize("kind", list(templates.TEMPLATES))
@pytest.mark.parametrize("lang", codec.LANG_CODES)
def test_every_template_fits_112_bytes_untruncated(kind, lang, all_langs):
    raw = _every(kind)[lang].format(n=99999, eta=999)
    assert len(raw.encode("utf-8")) <= codec.MAX_ACK_TEXT, (kind, lang, len(raw.encode()))
    assert templates.render(kind, lang, 99999, 999) == raw  # render() did not truncate
    # and survives a real ACK encode/decode unchanged
    ack = codec.Ack(bytes(8), bytes(8), bytes(8), 99999, 0, 999, raw)
    assert codec.decode_ack(codec.encode_ack(ack)).text == raw


def test_all_languages_have_both_templates():
    for kind in templates.TEMPLATES:
        assert set(templates.TEMPLATES[kind]) == {"en", "hi"}  # reviewed
        assert set(_every(kind)) == set(codec.LANG_CODES)  # plus the pending-review drafts


def test_languages_not_enabled_get_english(monkeypatch):
    monkeypatch.delenv("RELAY_LANGS", raising=False)
    en = templates.render("DISPATCHED", "en", 7)
    for lang in ("or", "tl", "war", "ceb"):
        assert templates.render("DISPATCHED", lang, 7) == en
    assert templates.render("DISPATCHED", "hi", 7) != en


def test_pending_review_templates_are_used_once_enabled(monkeypatch):
    monkeypatch.setenv("RELAY_LANGS", "en,hi,or")
    assert templates.render("RESOLVED", "or", 7) == templates.CONTRIB_TEMPLATES["RESOLVED"]["or"].format(n=7, eta=45)
    assert templates.render("RESOLVED", "tl", 7) == templates.render("RESOLVED", "en", 7)  # still off


def test_status_text_and_eta():
    text, eta = templates.status_text_and_eta(0, "en", 142)
    assert text == "Request #142 reached the control room. Help is being arranged." and eta == 0xFFFF
    text, eta = templates.status_text_and_eta(1, "en", 142)
    assert text == "Help dispatched for request #142. ETA 45 min." and eta == 45


def test_score_formula_exact():
    # MEDICAL 5 + 1.5*3 + 1*1 + log2(2) + 1.5*log2(1) + 0.25*2 = 12.0
    s = priority.compute("MEDICAL", 3, 1, 0b1, 1, 2.0)
    assert s.score == 12.0 and s.tier == "P1"
    # report_count 4 -> 1.5*log2(4)=3.0; waiting capped at 24h -> 6.0
    s = priority.compute("OTHER", 0, 0, 0, 4, 100.0)
    assert s.score == 10.0 and s.tier == "P2"


@pytest.mark.parametrize("score,tier", [(12, "P1"), (11.9, "P2"), (8, "P2"), (7.9, "P3"), (5, "P3"), (4.9, "P4"), (0, "P4")])
def test_tier_bands(score, tier):
    assert priority.tier_for(score) == tier


def test_rescue_trapped_beats_low_urgency_food():
    rescue = priority.compute("RESCUE", 3, 4, 0b10000, 1, 0.5)
    food = priority.compute("FOOD", 1, 4, 0, 1, 0.5)
    assert rescue.score > food.score
    assert rescue.tier == "P1" and food.tier in ("P3", "P4")


def test_reason_is_deterministic_and_lists_top_factors():
    a = priority.compute("RESCUE", 3, 4, 0b10000, 1, 0.5)
    b = priority.compute("RESCUE", 3, 4, 0b10000, 1, 0.5)
    assert a.reason == b.reason
    assert a.reason.startswith(f"{a.tier} (score {a.score:.1f}):")
    assert "category RESCUE +5.0" in a.reason and "urgency 3/3 (life-threatening) +4.5" in a.reason


def test_shift_tier_clamps():
    assert priority.shift_tier("P1", -1) == "P1"
    assert priority.shift_tier("P4", 1) == "P4"
    assert priority.shift_tier("P3", -1) == "P2"
