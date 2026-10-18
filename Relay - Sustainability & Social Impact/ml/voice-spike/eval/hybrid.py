#!/usr/bin/env python3
"""Offline hybrid analysis: merge a Gemma system with RuleExtractor per field and score the result.

usage: python3 -I eval/hybrid.py results.jsonl [more.jsonl ...] [--gemma gemma_audio|gemma_text|gemma270_text]

Scoring is exactly summarize.py's (imported from the file next to this one), so the tables are comparable with summary.txt.
Same columns as summary.txt; `valid` is the share of clips where the Gemma output was usable (the rest fell back to rules).

CAVEAT (read before quoting a number): the `rule` entries in results.jsonl are RuleExtractor run on the REFERENCE
transcript (the sentence the speaker was asked to read), not on a speech-recognizer transcript. A real on-device ASR
(Odia/Hindi especially) will make rule outputs worse, so every row that takes a field from the rules is optimistic.
Rows marked (*) pick, per field, the winner on this same 34 clips: also optimistic (selection on the test set).
Rows marked (oracle) are upper bounds, not policies.

Policies (Gemma = the system chosen with --gemma; where Gemma output is invalid the clip falls back to the rules for every field):
  rules only              RuleExtractor alone
  gemma only              Gemma alone (invalid = all wrong, same as summary.txt)
  gemma, else rules       Gemma unless its JSON is invalid, then the rules
  merge A                 Gemma: category, item, vuln.  Rules: landmark, urgency, people
  merge B (*)             Gemma: category, item, people.  Rules: landmark, urgency, vuln
  merge C (*)             per field, whichever system scored higher over all clips (ties -> rules, it is free)
  oracle per field        per clip, a field counts right if either system got it right (vuln: the lower-error side)
"""
import importlib.util
import json
import os
import sys
from collections import defaultdict

_here = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("summarize", os.path.join(_here, "summarize.py"))
S = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(S)

FIELDS = ["category", "item", "landmark", "urgency", "people", "vuln"]
ZKEY = {"category": "cat", "item": "item", "landmark": "lmk", "urgency": "urg", "people": "ppl"}


def load(paths):
    rows = []
    for p in paths:
        for line in open(p, encoding="utf-8"):
            line = line.strip()
            if line:
                o = json.loads(line)
                if "fatal" in o:
                    print(f"!! fatal record in {p}: {o}")
                    continue
                rows.append(o)
    return rows


def merged(g, r, src):
    """src: field -> 'g' | 'r'. g/r = predicted dicts (g may be None when invalid)."""
    if g is None:
        return dict(r or {})
    return {f: (g if src[f] == "g" else (r or {})).get(f) for f in FIELDS}


def policy_sources(name, best):
    if name == "A":
        return dict(category="g", item="g", vuln="g", landmark="r", urgency="r", people="r")
    if name == "B":
        return dict(category="g", item="g", people="g", landmark="r", urgency="r", vuln="r")
    return best


def main(paths, gkey):
    rows = [r for r in load(paths) if r.get("reference_transcript")]
    rows = [r for r in rows if r.get("rule") and r.get(gkey)]
    if not rows:
        print(f"no rows with both rule and {gkey}")
        return
    print(f"clips={len(rows)}  gemma system={gkey}  (rules are on REFERENCE text, not ASR output: optimistic, see --help)\n")

    # per-clip data: (gemma pred or None if invalid, rule pred, expected, gemma ms, row)
    clips = []
    for r in rows:
        gp, gok, gms, _ = S.pick(r, gkey)
        usable = gp if (gp and gok) else None
        clips.append((usable, r["rule"], r.get("expected") or {}, gms, r))

    def field_ok(pred, exp, f):
        if pred is None:
            return False
        return S.score(pred, True, exp)[ZKEY[f]]

    def vuln_err(pred, exp):
        if pred is None:
            return 10 ** 6
        z = S.score(pred, True, exp)
        return z["fp"] + z["fn"]

    # per-field winner over all clips (merge C)
    best = {}
    for f in FIELDS:
        if f == "vuln":
            gs = S.f1(*[sum(S.score(c[0], True, c[2])[k] if c[0] else (len({str(v).upper() for v in c[2].get('vuln') or []}) if k == 'fn' else 0)
                            for c in clips) for k in ("tp", "fp", "fn")]) or 0
            rs = S.f1(*[sum(S.score(c[1], True, c[2])[k] for c in clips) for k in ("tp", "fp", "fn")]) or 0
        else:
            gs = sum(field_ok(c[0], c[2], f) for c in clips)
            rs = sum(field_ok(c[1], c[2], f) for c in clips)
        best[f] = "g" if gs > rs else "r"
    print("merge C picks: " + ", ".join(f"{f}={'gemma' if best[f] == 'g' else 'rules'}" for f in FIELDS) + "\n")

    # (label, builder(clip) -> (pred dict, used_gemma_ms or None, valid bool))
    def build_policy(label, fn):
        return label, fn

    pols = []
    pols.append(("rules only", lambda c: (c[1], None, True)))
    pols.append(("gemma only", lambda c: (c[0], c[3], c[0] is not None)))
    pols.append(("gemma, else rules", lambda c: (c[0] if c[0] else c[1], c[3], c[0] is not None)))
    for nm, label in (("A", "merge A: gemma cat/item/vuln, rules lmk/urg/ppl"),
                      ("B", "merge B (*): gemma cat/item/ppl, rules lmk/urg/vuln"),
                      ("C", "merge C (*): per-field winner")):
        src = policy_sources(nm, best)
        pols.append((label, (lambda s: lambda c: (merged(c[0], c[1], s), c[3], c[0] is not None))(src)))

    def oracle(c):
        g, r, exp = c[0], c[1], c[2]
        o = {}
        for f in FIELDS:
            if f == "vuln":
                o[f] = (g if vuln_err(g, exp) < vuln_err(r, exp) else r).get(f)
            else:
                o[f] = (g if field_ok(g, exp, f) else r).get(f)
        return o, c[3], g is not None
    pols.append(("oracle per field (upper bound)", oracle))

    by_lang = defaultdict(list)
    for c in clips:
        by_lang[c[4].get("lang", "?")].append(c)

    print("### Hybrid policies, ALL clips (same columns as summary.txt; p50 = Gemma latency when Gemma is in the loop)")
    print("| policy | n | cat | item | lmk | urg+-1 | ppl | vulnF1 | gemma valid | p50 ms |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    langs = [("ALL", clips)] + sorted(by_lang.items())
    tables = []
    for label, fn in pols:
        for lang, cs in langs:
            n = len(cs)
            tot = defaultdict(int)
            valid = 0
            lat = []
            for c in cs:
                pred, ms, ok = fn(c)
                z = S.score(pred, True if pred else False, c[2])
                for k in ("cat", "item", "lmk", "urg", "ppl", "tp", "fp", "fn"):
                    tot[k] += z[k]
                valid += ok
                if ms is not None and label != "rules only":
                    lat.append(ms)
            v = S.f1(tot["tp"], tot["fp"], tot["fn"])
            row = (f"| {n} | {tot['cat']}/{n} | {tot['item']}/{n} | {tot['lmk']}/{n} | {tot['urg']}/{n} | {tot['ppl']}/{n} | "
                   f"{'n/a' if v is None else f'{v:.2f}'} | {valid}/{n} | {S.fmt(S.pct(lat, 50)) if lat else '1'} |")
            tables.append((lang, label, row))
    for lang, _ in langs:
        if lang != "ALL":
            continue
        for l, label, row in tables:
            if l == lang:
                print(f"| {label} {row}")
    print()
    for lang, _ in langs[1:]:
        print(f"### per language: {lang}")
        print("| policy | n | cat | item | lmk | urg+-1 | ppl | vulnF1 | gemma valid | p50 ms |")
        print("|---|---|---|---|---|---|---|---|---|---|")
        for l, label, row in tables:
            if l == lang:
                print(f"| {label} {row}")
        print()
    print("latency note: a merge costs the Gemma call plus ~1 ms of rules; the 'gemma, else rules' and merge rows cannot be faster than Gemma alone.")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    gk = "gemma_audio"
    if "--gemma" in sys.argv:
        gk = sys.argv[sys.argv.index("--gemma") + 1]
        args = [a for a in args if a != gk]
    if not args:
        sys.exit(__doc__)
    main(args, gk)
