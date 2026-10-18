"""ACK text templates (deterministic, byte-safe). ACK text is never written by an LLM.

NOTE: non-English strings pending native-speaker review.

Every template rendered with a 5-digit ticket number and a 3-digit ETA fits the 112-byte
ACK text limit without truncation (asserted in tests/test_templates.py).
"""

from __future__ import annotations

from .codec import (
    ETA_UNKNOWN,
    MAX_ACK_TEXT,
    STATUS_DISPATCHED,
    STATUS_ON_THE_WAY,
    STATUS_RECEIVED,
    STATUS_RESOLVED,
    truncate_utf8,
)

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
    # ON_THE_WAY carries the ETA when the operator gave one, else the no-ETA wording
    "ON_THE_WAY": {
        "en": "Help is on the way for request #{n}. ETA {eta} min.",
        "hi": "अनुरोध #{n}: मदद रास्ते में है। लगभग {eta} मिनट।",
        "or": "ଅନୁରୋଧ #{n}: ସାହାଯ୍ୟ ଆସୁଛି। ପ୍ରାୟ {eta} ମିନିଟ।",
        "tl": "Papunta na ang tulong para sa hiling #{n}. Mga {eta} minuto.",
        "war": "Pakadto na an bulig para ha hangyo #{n}. Mga {eta} minuto.",
        "ceb": "Padulong na ang tabang alang sa hangyo #{n}. Mga {eta} minuto.",
    },
    "ON_THE_WAY_NO_ETA": {
        "en": "Help is on the way for request #{n}.",
        "hi": "अनुरोध #{n}: मदद रास्ते में है।",
        "or": "ଅନୁରୋଧ #{n}: ସାହାଯ୍ୟ ଆସୁଛି।",
        "tl": "Papunta na ang tulong para sa hiling #{n}.",
        "war": "Pakadto na an bulig para ha hangyo #{n}.",
        "ceb": "Padulong na ang tabang alang sa hangyo #{n}.",
    },
    "RESOLVED": {
        "en": "Request #{n} is resolved. Stay safe.",
        "hi": "अनुरोध #{n} का समाधान हो गया है।",
        "or": "ଅନୁରୋଧ #{n} ସମାଧାନ ହୋଇଛି।",
        "tl": "Naresolba na ang hiling #{n}.",
        "war": "Nasolbar na an hangyo #{n}.",
        "ceb": "Nahuman na ang hangyo #{n}.",
    },
}

_STATUS_KEYS = {
    STATUS_RECEIVED: "RECEIVED", STATUS_DISPATCHED: "DISPATCHED",
    STATUS_ON_THE_WAY: "ON_THE_WAY", STATUS_RESOLVED: "RESOLVED",
}


def render(kind: str, lang: str, n: int, eta: int = DEFAULT_DISPATCH_ETA_MIN) -> str:
    """Render the template ``kind`` (a key of ``TEMPLATES``) in ``lang`` (falls back to en)."""
    table = TEMPLATES[kind]
    text = table.get(lang, table["en"]).format(n=n, eta=eta)
    return truncate_utf8(text, MAX_ACK_TEXT).decode("utf-8")


def status_text_and_eta(status: int, lang: str, n: int) -> tuple[str, int]:
    """(text, eta_min) for an ACK status code. RECEIVED, ON_THE_WAY and RESOLVED -> eta unknown
    (an ON_THE_WAY ack with an ETA is built by ``dispatch.build_status_ack``); DISPATCHED -> 45."""
    if status == STATUS_RECEIVED:
        return render("RECEIVED", lang, n), ETA_UNKNOWN
    if status == STATUS_DISPATCHED:
        return render("DISPATCHED", lang, n, DEFAULT_DISPATCH_ETA_MIN), DEFAULT_DISPATCH_ETA_MIN
    if status == STATUS_ON_THE_WAY:
        return render("ON_THE_WAY_NO_ETA", lang, n), ETA_UNKNOWN
    if status == STATUS_RESOLVED:
        return render("RESOLVED", lang, n), ETA_UNKNOWN
    raise KeyError(f"no template for ACK status {status}")
