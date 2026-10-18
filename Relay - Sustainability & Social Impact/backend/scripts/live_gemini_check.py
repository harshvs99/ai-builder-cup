"""Live smoke test of every Gemini path, through the backend's own factories (no fakes).

    cd backend && uv run python scripts/live_gemini_check.py [--only models,flash,sitrep,orders,broadcast,embed]

Needs GEMINI_API_KEY (or Vertex env). Costs a few cents: about ten short calls. Prints which model
answered, whether the validated model text was used or the template fallback kicked in, and the
embedding similarity of multilingual item pairs against the merge threshold (to tune
RELAY_EMBED_THRESHOLD). Exit status 1 if any check fails outright. Never prints the key.
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pydantic import BaseModel  # noqa: E402

from app import broadcast, dispatch, districts, embed, sitrep, textgen  # noqa: E402

CHECKS = ("models", "flash", "sitrep", "orders", "broadcast", "embed")
COUNTS = {
    "open": 4, "open_by_category": {"MEDICAL": 3, "FOOD": 1}, "p1_open": 2, "dispatched": 5, "resolved": 1,
    "gateways_online": 3, "oldest_open_ticket_no": 101, "oldest_open_age_s": 5400,
}
# (a, b, expected): pairs the merge should / should not join, all within the same category
PAIRS = [
    ("MEDICAL: insulin", "MEDICAL: इंसुलिन", True),
    ("MEDICAL: insulin", "MEDICAL: ଇନସୁଲିନ", True),
    ("MEDICAL: insulin", "MEDICAL: इंसुल", True),            # the wire truncates long Indic items
    ("WATER: drinking water", "WATER: paani", True),
    ("WATER: drinking water", "WATER: पीने का पानी", True),
    ("FOOD: rice", "FOOD: food packets", True),
    ("FOOD: rice", "FOOD: baby milk", False),
    ("MEDICAL: insulin", "MEDICAL: bandages", False),
    ("SHELTER: tarpaulin", "SHELTER: blankets", False),
]
results: list[tuple[str, bool, str]] = []


def record(name: str, ok: bool, detail: str) -> None:
    results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {detail}")


class Ping(BaseModel):
    answer: str


def check_models() -> None:
    from google import genai

    client = genai.Client(api_key=os.environ["GEMINI_API_KEY"])
    names = {m.name.removeprefix("models/") for m in client.models.list()}
    for want in (textgen.DEFAULT_MODEL, textgen.SITREP_DEFAULT_MODEL, embed.DEFAULT_MODEL):
        record(f"model {want}", want in names, "listed" if want in names else "NOT in models.list")
    print("  pro/flash models visible:", ", ".join(sorted(n for n in names if "pro" in n or "3.8" in n)[:20]))


def check_flash() -> None:
    tg = textgen.textgen_from_env("GEMINI_MODEL")
    if tg is None:
        return record("flash", False, "no client (credentials?)")
    t = time.monotonic()
    data = textgen.call_json(tg, 'Reply with JSON {"answer": "ok"}.', Ping)
    record("flash structured JSON", data.get("answer") == "ok", f"{data} in {time.monotonic() - t:.1f}s")


def check_sitrep() -> None:
    tg = textgen.sitrep_textgen_from_env()
    if tg is None:
        return record("sitrep", False, "no client")
    for d in districts.DISTRICTS:
        t = time.monotonic()
        en, local = sitrep.write_text(COUNTS, [101, 102], tg, d)
        used_model = en != sitrep.template_en(COUNTS, d.name)
        record(f"sitrep {d.id} ({d.local_lang})", used_model,
               f"{'model text accepted' if used_model else 'TEMPLATE fallback (see warnings above)'} "
               f"in {time.monotonic() - t:.1f}s")
        print(f"    EN: {en}\n    {d.local_lang.upper()}: {local}")


def check_orders() -> None:
    tg = textgen.textgen_from_env("GEMINI_MODEL")
    for d, at in ((districts.DISTRICTS[0], (19.8135, 85.8312)), (districts.DISTRICTS[1], (28.6315, 77.2167))):
        ticket = {"ticket_no": 7, "category": "MEDICAL", "item": "insulin", "people": 4, "landmark": "school",
                  "lang": "en", "has_location": True, "lat": at[0], "lon": at[1], "vuln_flags": ["ELDERLY"],
                  "summary": "mother needs insulin"}
        en, local, lang, source = dispatch.draft_order(ticket, "Test Medical Team", 35, tg)
        record(f"order {d.id}", source == "gemini" and lang == d.local_lang, f"source={source} lang={lang}")
        print(f"    EN: {en}\n    {lang.upper()}: {local}")


def check_broadcast() -> None:
    tg = textgen.textgen_from_env("BROADCAST_GEMINI_MODEL")
    src = "Cyclone shelter at Puri Zilla School is open. Drinking water at the bus stand from 14:00 for 300 families."
    t = time.monotonic()
    out = broadcast.draft(src, "en", "puri", tg, tg)
    ok = [lang for lang, r in out.items() if r["status"] == "ok"]
    record("broadcast", len(ok) >= 4, f"ok: {ok} in {time.monotonic() - t:.1f}s")
    for lang, r in out.items():
        print(f"    {lang} [{r['status']}] {r['text']}\n         back: {r.get('back_translation', '')}"
              + (f"\n         issues: {r['issues']}" if r.get("issues") else ""))


def check_embed() -> None:
    em = embed.embedder_from_env()
    if em is None:
        return record("embed", False, "no embedder (RELAY_EMBED_MERGE off or no credentials)")
    threshold = embed.threshold_from_env()
    texts = sorted({t for a, b, _ in PAIRS for t in (a, b)})
    vecs = dict(zip(texts, em.embed(texts)))
    print(f"  threshold {threshold:.2f}")
    wrong = 0
    for a, b, want in PAIRS:
        sim = embed.cosine(vecs[a], vecs[b])
        got = sim >= threshold
        wrong += got != want
        print(f"    {sim:.3f} {'merge' if got else 'apart'} (want {'merge' if want else 'apart'})"
              f"{'' if got == want else '  <-- MISMATCH'}  {a!r} ~ {b!r}")
    record("embed pairs", wrong == 0, f"{len(PAIRS) - wrong}/{len(PAIRS)} as expected at {threshold:.2f}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default=",".join(CHECKS))
    args = ap.parse_args()
    logging.basicConfig(level=logging.WARNING, format="    log %(name)s: %(message)s")
    if not (os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_GENAI_USE_VERTEXAI")):
        print("GEMINI_API_KEY is not set in this shell; nothing to check.")
        return 2
    for name in args.only.split(","):
        print(f"== {name}")
        try:
            globals()[f"check_{name}"]()
        except Exception as e:  # report and keep going
            record(name, False, f"{type(e).__name__}: {e}")
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
