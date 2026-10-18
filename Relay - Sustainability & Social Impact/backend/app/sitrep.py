"""Situation report, one per district: counts from the tickets, prose from a template or Gemini.

The numbers are never model-written. They are computed here from the district's tickets and handed
to the model, which writes sentences around them in English and the district's language (Puri:
Odia, Delhi: Hindi). Its text is accepted only if every number in it is one of the numbers it was
handed (and the open count is stated); otherwise the deterministic template is used. So a sitrep
can be wrong in tone, never in figures.

A ticket with no location counts in every district (``districts.ticket_in``). Gateways carry no
location, so ``gateways_online`` is the deployment-wide figure in every district's report.
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any

from pydantic import BaseModel

from .codec import CATEGORY_NAMES
from .districts import DEFAULT_DISTRICT, LANG_NAMES, District, ticket_in
from .store import Store
from .textgen import TextGen, call_json

log = logging.getLogger("relay.sitrep")

GATEWAY_WINDOW_S = 15 * 60  # hourly report: a gateway that synced in the last 15 min is online
DEFAULT_PERIOD_S = 3600
MAX_TICKET_NOS = 10
MAX_TEXT_CHARS = 1500
MODEL_TIMEOUT_S = 90.0  # Pro first, then the Flash fallback (textgen.sitrep_textgen_from_env)


def district_ticket_counts(tickets: list[dict[str, Any]], district: District) -> dict[str, Any]:
    """``Store.ticket_counts()`` shape, over the district's tickets (ticket JSON dicts)."""
    mine = [t for t in tickets if ticket_in(t, district)]
    open_ = [t for t in mine if t["status"] == "OPEN"]
    p1 = sorted(
        (t for t in open_ if t["priority_tier"] == "P1"),
        key=lambda t: (-t["priority_score"], t["first_reported_at"], t["ticket_no"]),
    )
    oldest = min(open_, key=lambda t: (t["first_reported_at"], t["ticket_no"]), default=None)
    by_cat = {name: 0 for name in CATEGORY_NAMES}
    for t in open_:
        by_cat[t["category"]] = by_cat.get(t["category"], 0) + 1
    return {
        "open": len(open_),
        "p1_open": len(p1),
        "p1_open_nos": [t["ticket_no"] for t in p1],
        "dispatched": sum(1 for t in mine if t["status"] in ("DISPATCHED", "ON_THE_WAY")),
        "resolved": sum(1 for t in mine if t["status"] == "RESOLVED"),
        "open_by_category": by_cat,
        "oldest_open_no": oldest["ticket_no"] if oldest else None,
        "oldest_open_at": oldest["first_reported_at"] if oldest else None,
    }


def compute_counts(store: Store, now: float, c: dict[str, Any] | None = None) -> dict[str, Any]:
    """The contract's ``counts`` object. ``c`` is a ``Store.ticket_counts()`` result (queried if omitted)."""
    c = c if c is not None else store.ticket_counts()
    oldest_at = c["oldest_open_at"]
    return {
        "open": c["open"],
        "open_by_category": c["open_by_category"],
        "p1_open": c["p1_open"],
        "dispatched": c["dispatched"],
        "resolved": c["resolved"],
        "gateways_online": store.gateways_online(now, GATEWAY_WINDOW_S),
        "oldest_open_ticket_no": c["oldest_open_no"],
        "oldest_open_age_s": max(0, int(now - oldest_at)) if oldest_at is not None else None,
    }


def referenced_tickets(c: dict[str, Any], counts: dict[str, Any]) -> list[int]:
    """The tickets the report talks about: open P1 tickets by priority, then the oldest open one."""
    nos = list(c["p1_open_nos"][:MAX_TICKET_NOS])
    oldest = counts["oldest_open_ticket_no"]
    if oldest is not None and oldest not in nos:
        nos.append(oldest)
    return nos


def _age_min(counts: dict[str, Any]) -> int | None:
    age = counts["oldest_open_age_s"]
    return None if age is None else age // 60


# --------------------------------------------------------------------------------------
# deterministic text
# --------------------------------------------------------------------------------------

def template_en(counts: dict[str, Any], district_name: str | None = None) -> str:
    cats = ", ".join(f"{k.replace('_', ' ').lower()} {v}" for k, v in counts["open_by_category"].items() if v)
    parts = [
        f"Situation report{', ' + district_name if district_name else ''}: {counts['open']} open request{'s' if counts['open'] != 1 else ''}, "
        f"{counts['p1_open']} of them top priority.",
        f"{counts['dispatched']} dispatched, {counts['resolved']} resolved.",
    ]
    if cats:
        parts.append(f"Open by category: {cats}.")
    if counts["oldest_open_ticket_no"] is not None:
        parts.append(f"Oldest open request: #{counts['oldest_open_ticket_no']}, waiting {_age_min(counts)} min.")
    parts.append(f"{counts['gateways_online']} gateway{'s' if counts['gateways_online'] != 1 else ''} online.")
    return " ".join(parts)


# NOTE: the Odia and Hindi templates are first drafts pending native-speaker review.
def template_or(counts: dict[str, Any]) -> str:
    parts = [
        f"ପରିସ୍ଥିତି ରିପୋର୍ଟ: {counts['open']}ଟି ଖୋଲା ଅନୁରୋଧ, ସେଥିରୁ {counts['p1_open']}ଟି ସର୍ବାଧିକ ପ୍ରାଥମିକତା।",
        f"{counts['dispatched']}ଟି ପଠାଯାଇଛି, {counts['resolved']}ଟି ସମାଧାନ ହୋଇଛି।",
    ]
    if counts["oldest_open_ticket_no"] is not None:
        parts.append(f"ସବୁଠୁ ପୁରୁଣା ଖୋଲା ଅନୁରୋଧ #{counts['oldest_open_ticket_no']}, {_age_min(counts)} ମିନିଟ ଅପେକ୍ଷା।")
    parts.append(f"{counts['gateways_online']}ଟି ଗେଟୱେ ଅନଲାଇନ୍।")
    return " ".join(parts)


def template_hi(counts: dict[str, Any]) -> str:
    parts = [
        f"स्थिति रिपोर्ट: {counts['open']} खुले अनुरोध, जिनमें से {counts['p1_open']} सर्वोच्च प्राथमिकता के हैं।",
        f"{counts['dispatched']} भेजे गए, {counts['resolved']} हल हुए।",
    ]
    if counts["oldest_open_ticket_no"] is not None:
        parts.append(f"सबसे पुराना खुला अनुरोध #{counts['oldest_open_ticket_no']}, {_age_min(counts)} मिनट से प्रतीक्षा में।")
    parts.append(f"{counts['gateways_online']} गेटवे ऑनलाइन।")
    return " ".join(parts)


LOCAL_TEMPLATES = {"or": template_or, "hi": template_hi}


# --------------------------------------------------------------------------------------
# model text, validated against the numbers
# --------------------------------------------------------------------------------------

class SitrepText(BaseModel):
    english: str
    local: str


_PROMPT = """You write the hourly situation report for the {district} district disaster control room after a cyclone.
The figures below were computed by the system and are final. Write prose AROUND them; do not
recompute, round, convert or add any number, time or date. Use only the numbers that appear in the
JSON (the ticket numbers in "ticket_nos" may be written as #N). Say "top priority" instead of
writing priority tiers as P1/P2.

Figures JSON:
{figures}

Return JSON with two fields, each 3 to 5 plain sentences (no markdown):
- english: the report in English.
- local: the same report in {local_lang_name}.
Mention the open count, how many are top priority, how many are dispatched, which request has waited
longest (if any), and how many gateways are online.
"""


def allowed_numbers(counts: dict[str, Any], ticket_nos: list[int]) -> set[int]:
    nums: set[int] = set(ticket_nos)
    for v in counts.values():
        if isinstance(v, dict):
            nums.update(x for x in v.values() if isinstance(x, int))
        elif isinstance(v, int) and not isinstance(v, bool):
            nums.add(v)
    age = _age_min(counts)
    if age is not None:
        nums.add(age)
    return nums


_TIER = re.compile(r"\bP[1-4]\b")


def numbers_in(text: str) -> list[int]:
    # \d also matches Odia and Devanagari digits, which int() understands
    return [int(m) for m in re.findall(r"\d+", _TIER.sub("", text))]


def valid_text(text: Any, counts: dict[str, Any], allowed: set[int]) -> str | None:
    if not isinstance(text, str):
        return None
    text = text.strip()
    if not text or len(text) > MAX_TEXT_CHARS:
        return None
    found = numbers_in(text)
    if any(n not in allowed for n in found) or counts["open"] not in found:
        return None
    return text


def write_text(
    counts: dict[str, Any], ticket_nos: list[int], textgen: TextGen | None, district: District = DEFAULT_DISTRICT
) -> tuple[str, str]:
    """(english, local): model prose if it passes validation, else the templates. Never raises."""
    en, local = template_en(counts, district.name), LOCAL_TEMPLATES[district.local_lang](counts)
    if textgen is None:
        return en, local
    figures = json.dumps({**counts, "oldest_open_age_min": _age_min(counts), "ticket_nos": ticket_nos})
    prompt = _PROMPT.format(
        figures=figures, district=district.name, local_lang_name=LANG_NAMES[district.local_lang]
    )
    try:
        data = call_json(textgen, prompt, SitrepText, timeout_s=MODEL_TIMEOUT_S)
    except Exception as e:
        log.warning("sitrep model call failed, using templates: %s: %s", type(e).__name__, e)
        return en, local
    allowed = allowed_numbers(counts, ticket_nos)
    model_en = valid_text(data.get("english"), counts, allowed)
    model_local = valid_text(data.get("local"), counts, allowed)
    if model_en is None or model_local is None:
        # all or nothing: an English/local pair that disagree on content would be worse than templates
        log.warning("sitrep model text rejected (numbers not in the counts); using templates")
        return en, local
    return model_en, model_local


def run(
    store: Store, textgen: TextGen | None, now: float, tickets: list[dict[str, Any]], district: District
) -> dict[str, Any]:
    """Compute, write and store one district's sitrep covering the time since its previous one.
    ``tickets`` are ticket JSON dicts (every district's; this filters)."""
    c = district_ticket_counts(tickets, district)
    counts = compute_counts(store, now, c)
    ticket_nos = referenced_tickets(c, counts)
    en, local = write_text(counts, ticket_nos, textgen, district)
    previous = store.latest_sitrep(district.id)
    start = previous["period_end"] if previous else int(now) - DEFAULT_PERIOD_S
    return store.add_sitrep(
        {
            "period_start": int(start), "period_end": int(now), "generated_at": int(now),
            "district": district.id, "counts": counts, "text_en": en, "text_local": local,
            "local_lang": district.local_lang, "ticket_nos": ticket_nos,
        }
    )
