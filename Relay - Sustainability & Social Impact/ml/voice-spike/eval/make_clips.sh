#!/usr/bin/env bash
# Generate synthetic eval clips with macOS `say` and a manifest for the spike's batch eval.
#   eval/make_clips.sh [sentences.csv] [out_dir]
# CSV columns: id,lang,voice,text,category,urgency,item,landmark,people,vuln   (vuln = |-separated)
# `voice` may be empty -> a default per language is used (en: Rishi, hi: Lekha). Languages with no installed voice
# (or/tl/war/ceb on stock macOS) get a text-only manifest entry (no "file") so the text/rule paths still run.
# Output: <out_dir>/<id>.wav (16 kHz mono 16-bit) + <out_dir>/manifest.json
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CSV="${1:-$HERE/sentences.csv}"
OUT="${2:-$HERE/work/clips}"
mkdir -p "$OUT"
export OUT CSV
python3 -I - <<'PY'
import csv, json, os, subprocess, sys
out, src = os.environ["OUT"], os.environ["CSV"]
default_voice = {"en": "Rishi", "hi": "Lekha"}
installed = subprocess.run(["say", "-v", "?"], capture_output=True, text=True).stdout
manifest = []
with open(src, newline="", encoding="utf-8") as f:
    for r in csv.DictReader(f):
        lang = r["lang"].strip()
        voice = r.get("voice", "").strip() or default_voice.get(lang, "")
        entry = {
            "lang": lang,
            "expected": {
                "category": r["category"].strip(), "urgency": int(r["urgency"]),
                "item": r["item"].strip(), "landmark": r["landmark"].strip(), "people": int(r["people"]),
                "vuln": [v for v in r.get("vuln", "").split("|") if v.strip()],
            },
            "reference_transcript": r["text"].strip(),
        }
        if voice and any(line.split()[0] == voice for line in installed.splitlines() if line.strip()):
            aiff = os.path.join(out, r["id"] + ".aiff")
            wav = os.path.join(out, r["id"] + ".wav")
            subprocess.run(["say", "-v", voice, "-o", aiff, r["text"]], check=True)
            subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff, wav], check=True)
            os.remove(aiff)
            entry["file"] = r["id"] + ".wav"
        else:
            print(f"[make_clips] no voice for {r['id']} (lang={lang}, voice='{voice}'): text-only entry", file=sys.stderr)
        manifest.append(entry)
with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
print(f"[make_clips] wrote {len(manifest)} entries to {out}")
PY
