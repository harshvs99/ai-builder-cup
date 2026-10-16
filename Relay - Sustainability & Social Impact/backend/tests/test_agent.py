import pytest

from app.agent import Triage
from app.config import Settings
from app.llm import NoLLM
from app.protocol import AckStatus, Category, Flag, Lang, decode_ack, encode_need
from app.store import Store

from .conftest import T0, Clock, FakeLLM, make_need


@pytest.fixture
def store():
    return Store(":memory:")


def triage_with(store, llm=None, clock=None):
    return Triage(store, llm or NoLLM(), Settings(db_path=":memory:"), clock or Clock())


def ingest(triage, need, hops=2):
    return triage.ingest(need, encode_need(need), "d0d0d0d0", hops)


def acks(store):
    return [decode_ack(a.raw) for a in store.acks_after(0, 0)]


def test_new_need_opens_ticket_and_queues_received_ack(store):
    t = triage_with(store)
    need = make_need()
    result = ingest(t, need)
    assert result.outcome == "created"
    ticket = store.get_ticket(result.ticket_id)
    assert (ticket.category, ticket.item, ticket.flags, ticket.max_hops) == ("MEDICAL", "insulin", ["ELDERLY"], 2)
    assert f"#{ticket.id}" in ticket.dispatch_draft["en"]
    assert "19.81352, 85.83119" in ticket.dispatch_draft["en"]
    [ack] = acks(store)
    assert ack.status is AckStatus.RECEIVED
    assert ack.target_node == need.origin_node and ack.ref_msg_id == need.msg_id
    assert ack.text.startswith("ଅନୁରୋଧ")  # Odia template


def test_same_msg_id_is_a_duplicate(store):
    t = triage_with(store)
    need = make_need()
    ingest(t, need)
    assert ingest(t, need).outcome == "duplicate"
    assert len(acks(store)) == 1


def test_nearby_matching_reports_merge(store):
    t = triage_with(store)
    first = ingest(t, make_need(origin_node=1, people=1, loc_acc_m=40))
    # ~110 m north, a neighbour reporting the same need with more detail.
    second = ingest(
        t, make_need(origin_node=2, lat=19.81452, people=2, flags=Flag.ELDERLY | Flag.DISABLED, loc_acc_m=10)
    )
    assert second.outcome == "merged" and second.ticket_id == first.ticket_id
    ticket = store.get_ticket(first.ticket_id)
    assert ticket.report_count == 2
    assert ticket.people == 2
    assert ticket.flags == ["ELDERLY", "DISABLED"]
    assert ticket.lat == pytest.approx(19.81452)  # the more accurate fix wins
    assert {a.target_node for a in acks(store)} == {1, 2}


@pytest.mark.parametrize(
    "overrides",
    [
        {"category": Category.WATER, "item": "water"},
        {"lat": 19.83352},  # ~2.2 km away
    ],
)
def test_different_category_or_place_opens_new_ticket(store, overrides):
    t = triage_with(store)
    first = ingest(t, make_need(origin_node=1))
    second = ingest(t, make_need(origin_node=2, **overrides))
    assert second.outcome == "created" and second.ticket_id != first.ticket_id


def test_reports_outside_merge_window_do_not_merge(store):
    clock = Clock()
    t = triage_with(store, clock=clock)
    ingest(t, make_need(origin_node=1))
    clock.advance(7 * 3600)
    assert ingest(t, make_need(origin_node=2)).outcome == "created"


def test_different_items_ask_gemini(store):
    llm = FakeLLM({"SameNeedVerdict": {"same": True, "reason": "both are diabetes medication"}})
    t = triage_with(store, llm)
    ingest(t, make_need(origin_node=1))
    assert ingest(t, make_need(origin_node=2, item="diabetes medicine")).outcome == "merged"
    assert llm.prompts[0][0] == "SameNeedVerdict"


def test_different_items_stay_separate_without_gemini(store):
    t = triage_with(store)
    ingest(t, make_need(origin_node=1))
    assert ingest(t, make_need(origin_node=2, item="bandages")).outcome == "created"


def test_same_phone_without_gps_merges(store):
    t = triage_with(store)
    first = ingest(t, make_need(origin_node=7))
    second = ingest(t, make_need(origin_node=7, lat=None, lon=None, loc_acc_m=None))
    assert second.ticket_id == first.ticket_id


def test_resolved_tickets_do_not_absorb_new_reports(store):
    t = triage_with(store)
    first = ingest(t, make_need(origin_node=1))
    t.set_status(first.ticket_id, AckStatus.RESOLVED)
    assert ingest(t, make_need(origin_node=2)).outcome == "created"


def test_phone_clock_far_in_future_is_ignored(store):
    t = triage_with(store)
    result = ingest(t, make_need(created_at=int(T0) + 86_400))
    assert store.get_ticket(result.ticket_id).first_reported_at == T0


def test_enrich_applies_gemini_review_and_bilingual_draft(store):
    llm = FakeLLM(
        {
            "PriorityReview": {"tier_adjust": 5, "reason": "Insulin-dependent elderly patient without insulin."},
            "DispatchDraft": {"english": "WHAT: insulin\nWHERE: 19.81352, 85.83119", "local": "କଣ: ଇନସୁଲିନ"},
        }
    )
    t = triage_with(store, llm)
    ticket_id = ingest(t, make_need()).ticket_id
    ticket = t.enrich(ticket_id)
    assert ticket.enriched_by == "gemini"
    assert ticket.tier_adjust == 1  # clamped
    assert ticket.priority_reason.startswith("Insulin-dependent")
    assert set(ticket.dispatch_draft) == {"en", "or"}
    prompt = dict(llm.prompts)["DispatchDraft"]
    assert "Odia" in prompt and "<report>mother needs insulin, diabetic</report>" in prompt


def test_enrich_without_llm_is_a_no_op(store):
    t = triage_with(store)
    ticket_id = ingest(t, make_need()).ticket_id
    before = store.get_ticket(ticket_id)
    assert t.enrich(ticket_id) == before


def test_enrich_falls_back_when_gemini_fails(store):
    t = triage_with(store, FakeLLM({}))
    ticket_id = ingest(t, make_need()).ticket_id
    assert t.enrich(ticket_id).enriched_by == "rules"


def test_approve_confirms_every_reporter_in_their_language(store):
    llm = FakeLLM({"Translation": {"text": "ପୁରୀ ବ୍ଲକ ଅଫିସରୁ ଦଳ ବାହାରିଛି।"}})
    t = triage_with(store, llm)
    ticket_id = ingest(t, make_need(origin_node=1, lang=Lang.OR)).ticket_id
    ingest(t, make_need(origin_node=2, lang=Lang.EN))
    ticket, queued = t.set_status(ticket_id, AckStatus.DISPATCHED, eta_min=45, note="Team leaving Puri block office.")
    assert ticket.status == "DISPATCHED" and queued == 2
    dispatched = {a.target_node: a for a in acks(store) if a.status is AckStatus.DISPATCHED}
    assert dispatched[1].text.endswith("ପୁରୀ ବ୍ଲକ ଅଫିସରୁ ଦଳ ବାହାରିଛି।")
    assert dispatched[2].text == f"Request #{ticket_id}: a relief team has been sent. Team leaving Puri block office."
    assert all(a.eta_min == 45 for a in dispatched.values())
    # English is the only language that never needs translating.
    assert [name for name, _ in llm.prompts] == ["Translation"]


def test_approve_without_gemini_sends_english_note(store):
    t = triage_with(store)
    ticket_id = ingest(t, make_need(lang=Lang.OR)).ticket_id
    t.set_status(ticket_id, AckStatus.DISPATCHED, note="Boat coming.")
    assert acks(store)[-1].text.endswith("Boat coming.")
