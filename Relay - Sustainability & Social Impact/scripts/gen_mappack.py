#!/usr/bin/env python3
"""Build the phones' offline map pack from the backend gazetteer.

Phones use it during triage to say where the nearest shelter or hospital is and which river to keep away from
(android/core-protocol .../triage/Places.kt). Only places whose name says what they are are kept, so nothing is
guessed: "... cyclone shelter" is a shelter, "... hospital" a hospital, "... river embankment" and beaches are water.
Names in Devanagari and Odia script are taken from the gazetteer aliases, so a Hindi or Odia speaker hears the place
in their own script.

Usage: python3 scripts/gen_mappack.py > android/app/src/main/assets/mappack.json
"""
import json
import re
import sys
from pathlib import Path

GAZETTEER = Path(__file__).resolve().parent.parent / "backend" / "app" / "data" / "gazetteer_puri.json"

KINDS = [
    ("shelter", re.compile(r"\bcyclone shelter\b|\bflood shelter\b|\brelief camp\b")),
    ("hospital", re.compile(r"\bhospital\b|\bphc\b|\bchc\b|\bhealth cent")),
    ("water", re.compile(r"\briver\b|\bembankment\b|\bcanal\b|\bbeach\b|\bswargadwar\b")),
]
SCRIPTS = {"hi": re.compile(r"[ऀ-ॿ]"), "or": re.compile(r"[଀-୿]")}


def kind_of(entry):
    text = " ".join([entry["name"], *entry.get("aliases", [])]).lower()
    return next((k for k, rx in KINDS if rx.search(text)), None)


def main():
    places = []
    for e in json.loads(GAZETTEER.read_text(encoding="utf-8")):
        kind = kind_of(e)
        if kind is None:
            continue
        names = {}
        for alias in e.get("aliases", []):
            for lang, rx in SCRIPTS.items():
                if rx.search(alias) and len(alias) > len(names.get(lang, "")):
                    names[lang] = alias  # the longest: usually the one that also says what the place is
        places.append({
            "name": e["name"], "kind": kind,
            "lat_e5": round(e["lat"] * 100000), "lon_e5": round(e["lon"] * 100000),
            **({"names": names} if names else {}),
        })
    out = {"version": 1, "source": "backend/app/data/gazetteer_puri.json", "places": places}
    json.dump(out, sys.stdout, ensure_ascii=False, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
