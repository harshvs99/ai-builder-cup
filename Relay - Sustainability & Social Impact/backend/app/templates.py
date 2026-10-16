"""Fixed confirmation texts, one per ACK status and language.

Confirmations are formulaic, so they come from templates rather than an LLM.
They are instant, work without Gemini, and can't be garbled. Gemini is only used
to translate free-text operator notes (see agent.py).

NOTE: the non-English strings below are first drafts. They must be checked by a
native speaker of each language before the demo.
"""

from __future__ import annotations

from .protocol import ACK_TEXT_MAX, AckStatus, truncate_utf8

_TEMPLATES: dict[str, dict[AckStatus, str]] = {
    "en": {
        AckStatus.RECEIVED: "Request #{n} reached the control room.",
        AckStatus.DISPATCHED: "Request #{n}: a relief team has been sent.",
        AckStatus.ON_THE_WAY: "Request #{n}: help is on the way.",
        AckStatus.RESOLVED: "Request #{n} is marked resolved.",
    },
    "hi": {
        AckStatus.RECEIVED: "अनुरोध #{n} नियंत्रण कक्ष तक पहुँच गया।",
        AckStatus.DISPATCHED: "अनुरोध #{n}: सहायता दल भेजा गया है।",
        AckStatus.ON_THE_WAY: "अनुरोध #{n}: मदद रास्ते में है।",
        AckStatus.RESOLVED: "अनुरोध #{n} पूरा हो गया।",
    },
    "or": {
        AckStatus.RECEIVED: "ଅନୁରୋଧ #{n} ନିୟନ୍ତ୍ରଣ କକ୍ଷରେ ପହଞ୍ଚିଲା।",
        AckStatus.DISPATCHED: "ଅନୁରୋଧ #{n}: ସହାୟତା ଦଳ ପଠାଯାଇଛି।",
        AckStatus.ON_THE_WAY: "ଅନୁରୋଧ #{n}: ସହାୟତା ଆସୁଛି।",
        AckStatus.RESOLVED: "ଅନୁରୋଧ #{n} ସମାଧାନ ହୋଇଛି।",
    },
    "tl": {
        AckStatus.RECEIVED: "Natanggap na ng control room ang request #{n}.",
        AckStatus.DISPATCHED: "Request #{n}: may relief team nang ipinadala.",
        AckStatus.ON_THE_WAY: "Request #{n}: parating na ang tulong.",
        AckStatus.RESOLVED: "Request #{n}: tapos na.",
    },
    "war": {
        AckStatus.RECEIVED: "Nakarawat na han control room an request #{n}.",
        AckStatus.DISPATCHED: "Request #{n}: may relief team na nga ginpadara.",
        AckStatus.ON_THE_WAY: "Request #{n}: paabot na an bulig.",
        AckStatus.RESOLVED: "Request #{n}: nahuman na.",
    },
    "ceb": {
        AckStatus.RECEIVED: "Nadawat na sa control room ang request #{n}.",
        AckStatus.DISPATCHED: "Request #{n}: may relief team nang gipadala.",
        AckStatus.ON_THE_WAY: "Request #{n}: padulong na ang tabang.",
        AckStatus.RESOLVED: "Request #{n}: nasulbad na.",
    },
}

SUPPORTED_LANGS = tuple(_TEMPLATES)


def ack_text(lang: str, status: AckStatus, ticket_no: int, extra: str = "") -> str:
    """Status line in ``lang`` (English fallback), plus optional extra text, within ACK_TEXT_MAX bytes."""
    template = _TEMPLATES.get(lang, _TEMPLATES["en"])[status]
    text = template.format(n=ticket_no)
    if extra:
        text = f"{text} {extra.strip()}"
    return truncate_utf8(text, ACK_TEXT_MAX).decode("utf-8")
