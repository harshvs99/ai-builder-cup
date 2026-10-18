"""Category re-classification at triage.

Phones tag a need with a category from a short list, and under stress the tag is sometimes
wrong: "drinking water" filed as MEDICAL, "rice" filed as WATER. A wrong category costs
twice, because it mis-scores the ticket and sends it to the wrong team.

Two sources, both producing the same record ``{"from", "to", "reason"}``:

  * deterministic lexicon rules (``deterministic``): phase 1, pure, run at ticket creation
  * a model proposal (``validate_proposal``): phase 2, only accepted if it passes the
    checks below

Decisions (not in the brief):
  * The lexicon looks at the ``item`` field only; the summary is longer free text and
    likelier to mix topics.
  * An item that matches more than one lexicon ("food and water") is ambiguous: no change.
    A medical word in the item ("ORS water", "saline") also blocks the rules.
  * Neither source ever moves a ticket OUT of RESCUE or MISSING_PERSON (life-safety
    categories): an injected or mistaken "reclass" must not be able to demote a rescue.
  * Model proposals are accepted at most once per ticket and never chain.
  * Clustering uses the NEW category (see ``agent.TriagePipeline``): later reports are put
    through the same deterministic rules before the lookup, so they find the reclassed ticket.
    A model-only reclass cannot be predicted for a later report, so such a report may open a
    sibling ticket in the original category: an accepted cost, the operator can see both.
  * The original packets are never modified: the as-reported category stays on the packet.
"""

from __future__ import annotations

from typing import Any

from .codec import CATEGORY_NAMES
from .location import tokenize

# Whole-word lexicons, lowercase. English, Hindi (Devanagari + romanised), Odia (script + romanised).
_WATER = {
    "water", "drinking", "paani", "pani", "jal", "peyjal", "bisleri", "tanker", "paaniya",
    "पानी", "जल", "पेयजल", "ପାଣି", "ଜଳ", "ପାନୀୟ",
}
_FOOD = {
    "food", "rice", "dal", "dalma", "chawal", "chaval", "roti", "bhojan", "khana", "khaana", "ration",
    "biscuit", "biscuits", "chuda", "chura", "muri", "atta", "flour", "meal", "meals",
    "खाना", "भोजन", "चावल", "राशन", "रोटी", "आटा", "दाल", "ଖାଦ୍ୟ", "ଚାଉଳ", "ଭାତ", "ଡାଲି",
}
_SHELTER = {
    "tent", "tarpaulin", "tirpal", "tripal", "shelter", "blanket", "blankets", "roof", "sheet",
    "तिरपाल", "तंबू", "तम्बू", "कंबल", "आश्रय", "ତାମ୍ବୁ", "ତିରପାଲ", "କମ୍ବଳ", "ଆଶ୍ରୟ",
}
# A medical word next to a water word ("ORS water", "saline") means the need may well be medical.
_MEDICAL = {
    "medicine", "medicines", "insulin", "tablet", "tablets", "injection", "ors", "saline", "dressing",
    "bandage", "oxygen", "dialysis", "doctor", "ambulance", "antibiotic", "paracetamol", "inhaler",
    "दवा", "दवाई", "इंसुलिन", "ଔଷଧ", "ଇନସୁଲିନ୍",
}

# (categories it may move from, lexicon, target, label)
_RULES: tuple[tuple[frozenset[str], set[str], str, str], ...] = (
    (frozenset({"MEDICAL", "FOOD", "SHELTER", "OTHER"}), _WATER, "WATER", "drinking water"),
    (frozenset({"WATER", "MEDICAL", "OTHER"}), _FOOD, "FOOD", "food"),
    (frozenset({"OTHER", "FOOD", "WATER"}), _SHELTER, "SHELTER", "shelter material"),
)
# Never moved out of by anything automatic.
PROTECTED = frozenset({"RESCUE", "MISSING_PERSON"})

def make(frm: str, to: str, reason: str) -> dict[str, str]:
    return {"from": frm, "to": to, "reason": reason}


def deterministic(category: str, item: str) -> dict[str, str] | None:
    """Rule-based correction for one (category, item), or None. Pure."""
    if category in PROTECTED or not item:
        return None
    tokens = set(tokenize(item))
    if tokens & _MEDICAL:
        return None
    hits = [(to, label) for frm, lex, to, label in _RULES if category in frm and to != category and tokens & lex]
    # Ambiguity is judged across ALL lexicons, not just the applicable rules.
    matched_lexicons = sum(1 for lex in (_WATER, _FOOD, _SHELTER) if tokens & lex)
    if len(hits) != 1 or matched_lexicons != 1:
        return None
    to, label = hits[0]
    return make(category, to, f'item "{item.strip()[:40]}" names {label}, filed as {category}')


def effective_category(category: str, item: str) -> str:
    """The category a NEED will be triaged under (used for the cluster lookup)."""
    r = deterministic(category, item)
    return r["to"] if r else category


def validate_proposal(
    current: str, proposed: Any, reason: Any, existing: dict[str, str] | None
) -> dict[str, str] | None:
    """Accept a model-proposed category change only if it is safe; otherwise None."""
    if existing is not None or current in PROTECTED:
        return None
    if not isinstance(proposed, str) or proposed not in CATEGORY_NAMES or proposed == current:
        return None
    if not isinstance(reason, str) or not reason.strip():
        return None
    return make(current, proposed, " ".join(reason.split())[:200])


def note(reclass: dict[str, str]) -> str:
    """The sentence appended to ``priority_reason``."""
    return f"Reclassified {reclass['from']} to {reclass['to']}: {reclass['reason']}."
