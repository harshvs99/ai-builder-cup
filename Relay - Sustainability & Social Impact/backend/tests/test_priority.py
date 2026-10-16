from app import priority
from app.models import Report, Ticket

from .conftest import T0


def ticket(category="MEDICAL", urgency=2, flags=(), people=1, reports=1, first=T0, adjust=0):
    return Ticket(
        category=category,
        urgency=urgency,
        flags=list(flags),
        people=people,
        first_reported_at=first,
        tier_adjust=adjust,
        reports=[
            Report(msg_id=f"{i:016x}", origin_node="0a0a0a0a", lang="en", summary="", created_at=0,
                   received_at=first, gateway_node="00000000")
            for i in range(reports)
        ],
    )


def test_trapped_rescue_outranks_medical_outranks_food():
    rescue = ticket("RESCUE", 3, ["TRAPPED"], people=4)
    medical = ticket("MEDICAL", 3, ["ELDERLY"])
    food = ticket("FOOD", 1, people=6)
    ranked = sorted([food, medical, rescue], key=lambda t: priority.sort_key(t, T0))
    assert [t.category for t in ranked] == ["RESCUE", "MEDICAL", "FOOD"]
    assert priority.final_tier(rescue, T0) == 1


def test_corroborating_reports_raise_score():
    assert priority.score(ticket(reports=4), T0) > priority.score(ticket(reports=1), T0)


def test_waiting_bonus_grows_and_is_capped():
    t = ticket()
    assert priority.score(t, T0 + 3600) == priority.score(t, T0) + 2
    assert priority.score(t, T0 + 1000 * 3600) == priority.score(t, T0) + priority.MAX_WAITING_BONUS


def test_tier_adjust_moves_one_step_and_clamps():
    base = ticket("FOOD", 0)
    assert priority.final_tier(base, T0) == 4
    assert priority.final_tier(base.model_copy(update={"tier_adjust": 1}), T0) == 3
    assert priority.final_tier(base.model_copy(update={"tier_adjust": -1}), T0) == 4
    top = ticket("RESCUE", 3, ["TRAPPED"], adjust=1)
    assert priority.final_tier(top, T0) == 1


def test_explain_mentions_key_facts():
    text = priority.explain(ticket("MEDICAL", 3, ["ELDERLY"], reports=2).model_copy(update={"item": "insulin"}), T0 + 7200)
    assert "insulin" in text and "elderly" in text and "2 reports" in text and "waiting 2.0 h" in text
