"""ACK text templates (deterministic, byte-safe). ACK text is never written by an LLM.

NOTE: non-English strings pending native-speaker review.

Every template rendered with a 5-digit ticket number and a 3-digit ETA fits the 112-byte
ACK text limit without truncation (asserted in tests/test_templates.py).
"""

from __future__ import annotations

from .codec import ETA_UNKNOWN, STATUS_DISPATCHED, STATUS_RECEIVED, MAX_ACK_TEXT, truncate_utf8

DEFAULT_DISPATCH_ETA_MIN = 45

TEMPLATES: dict[str, dict[str, str]] = {
    "RECEIVED": {
        "en": "Request #{n} reached the control room. Help is being arranged.",
        "hi": "अनुरोध #{n} नियंत्रण कक्ष तक पहुँच गया है।",
        "or": "ଅନୁରୋଧ #{n} ନିୟନ୍ତ୍ରଣ କକ୍ଷରେ ପହଞ୍ଚିଛି।",
        "tl": "Natanggap ng control room ang hiling #{n}. Inaayos na ang tulong.",
        "war": "Nakarawat na han control room an hangyo #{n}.",
        "ceb": "Nadawat na sa control room ang hangyo #{n}.",
    },
    "DISPATCHED": {
        "en": "Help dispatched for request #{n}. ETA {eta} min.",
        "hi": "अनुरोध #{n}: मदद भेजी गई। लगभग {eta} मिनट।",
        "or": "ଅନୁରୋଧ #{n}: ସାହାଯ୍ୟ ପଠାଗଲା। ପ୍ରାୟ {eta} ମିନିଟ।",
        "tl": "Naipadala na ang tulong para sa hiling #{n}. Mga {eta} minuto.",
        "war": "Ginpadara na an bulig para ha hangyo #{n}. Mga {eta} minuto.",
        "ceb": "Gipadala na ang tabang alang sa hangyo #{n}. Mga {eta} minuto.",
    },
}

_STATUS_KEYS = {STATUS_RECEIVED: "RECEIVED", STATUS_DISPATCHED: "DISPATCHED"}


def render(kind: str, lang: str, n: int, eta: int = DEFAULT_DISPATCH_ETA_MIN) -> str:
    """Render the template ``kind`` ("RECEIVED"/"DISPATCHED") in ``lang`` (falls back to en)."""
    table = TEMPLATES[kind]
    text = table.get(lang, table["en"]).format(n=n, eta=eta)
    return truncate_utf8(text, MAX_ACK_TEXT).decode("utf-8")


def status_text_and_eta(status: int, lang: str, n: int) -> tuple[str, int]:
    """(text, eta_min) for an ACK status code. RECEIVED -> eta unknown; DISPATCHED -> 45."""
    if status == STATUS_RECEIVED:
        return render("RECEIVED", lang, n), ETA_UNKNOWN
    if status == STATUS_DISPATCHED:
        return render("DISPATCHED", lang, n, DEFAULT_DISPATCH_ETA_MIN), DEFAULT_DISPATCH_ETA_MIN
    raise KeyError(f"no template for ACK status {status}")
