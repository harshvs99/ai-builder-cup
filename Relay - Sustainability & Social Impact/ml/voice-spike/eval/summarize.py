#!/usr/bin/env python3
"""Summarize results.jsonl from the voice spike's batch eval.

usage: python3 -I eval/summarize.py results.jsonl [more.jsonl ...] [--diff-all]

One table per system (Gemma-audio, Gemma-text-on-reference, RuleExtractor-on-reference), one row per language
plus ALL. Accuracy denominators are ALL clips for that system in that language (invalid outputs count as wrong).
  cat      category accuracy
  item/lmk normalised match: lowercase, punctuation stripped, leading near/by/at/behind/the/a/an dropped
           (repeatedly) and trailing Hindi "के पास" dropped; then exact match OR similarity >= 0.8
  urg      urgency within +/-1
  ppl      people, exact match (0 = "unknown" is a legal expected and predicted value)
  vulnF1   micro-averaged F1 over vuln flags (n/a if no flag is expected or predicted anywhere)
  valid    share of outputs that parsed to schema-valid JSON
  p50/p95  total latency (ms, nearest-rank percentiles); gemma systems only
After the tables: a per-clip diff listing of every miss (Gemma-audio; all systems with --diff-all).
The same logic is mirrored on the device in Summary.kt (keep the two in sync).
"""
import difflib, json, math, sys, unicodedata
from collections import defaultdict

SIM = 0.8
LEADING = {"near", "by", "at", "behind", "the", "a", "an"}
POSTPOSITION = ["के", "पास"]


def norm(s):
    """lowercase, punctuation -> space (letters, digits and combining marks are kept), drop leading
    near/by/at/behind/the/a/an repeatedly and a trailing Hindi 'के पास'."""
    s = unicodedata.normalize("NFC", s or "").lower()
    s = "".join(ch if unicodedata.category(ch)[0] in "LNM" else " " for ch in s)
    toks = s.split()
    changed = True
    while changed:
        changed = False
        if toks and toks[0] in LEADING:
            toks.pop(0)
            changed = True
        if len(toks) >= 2 and toks[-2:] == POSTPOSITION:
            toks = toks[:-2]
            changed = True
    return " ".join(toks)


def similar(a, b):
    a, b = norm(a), norm(b)
    if a == b:
        return True
    if not a or not b:
        return False
    return difflib.SequenceMatcher(None, a, b).ratio() >= SIM


def pct(vals, p):
    if not vals:
        return None
    v = sorted(vals)
    return v[max(0, math.ceil(p / 100 * len(v)) - 1)]


def fmt(x, nd=0):
    return "-" if x is None else (f"{x:.{nd}f}" if isinstance(x, float) else str(x))


def pick(row, system):
    """-> (predicted dict or None, valid bool|None, total_ms|None, first_token_ms|None)"""
    if system == "rule":
        d = row.get("rule")
        return (d, None, d.get("latency_ms") if d else None, None)
    g = row.get(system)
    if not g:
        return (None, False, None, None)
    ok = bool(g.get("valid"))
    lat = g.get("latency_ms") or {}
    return (g.get("parsed") if g.get("json_found") or g.get("parsed") else None, ok, lat.get("total"), lat.get("first_token"))


def vset(x):
    return {str(v).upper() for v in (x or [])}


def score(pred, ok, exp):
    """-> dict of per-clip results. Invalid / missing output earns nothing and its expected flags count as FN."""
    exp_v = vset(exp.get("vuln"))
    z = dict(cat=False, item=False, lmk=False, urg=False, ppl=False, tp=0, fp=0, fn=len(exp_v))
    if not pred or ok is False:
        return z
    z["cat"] = str(pred.get("category", "")).upper() == str(exp.get("category", "")).upper()
    z["item"] = similar(pred.get("item"), exp.get("item"))
    z["lmk"] = similar(pred.get("landmark"), exp.get("landmark"))
    try:
        z["urg"] = abs(int(pred.get("urgency")) - int(exp.get("urgency"))) <= 1
    except (TypeError, ValueError):
        pass
    try:
        z["ppl"] = int(pred.get("people")) == int(exp.get("people"))
    except (TypeError, ValueError):
        pass
    pv = vset(pred.get("vuln"))
    z["tp"], z["fp"], z["fn"] = len(pv & exp_v), len(pv - exp_v), len(exp_v - pv)
    return z


def f1(tp, fp, fn):
    d = 2 * tp + fp + fn
    return None if d == 0 else 2 * tp / d


def diff(row, pred, ok, exp):
    """List of 'field expected -> got' strings for every miss; [] if the clip is a full hit."""
    if not pred or ok is False:
        g = row.get("gemma_audio") or {}
        why = "; ".join((g.get("issues") or [])[:2]) or (g.get("error") or "no JSON")
        return [f"INVALID/no output ({why})"]
    z = score(pred, ok, exp)
    out = []
    if not z["cat"]:
        out.append(f"cat {exp.get('category')}->{pred.get('category')}")
    if not z["urg"]:
        out.append(f"urg {exp.get('urgency')}->{pred.get('urgency')}")
    if not z["ppl"]:
        out.append(f"ppl {exp.get('people')}->{pred.get('people')}")
    if z["fp"] or z["fn"]:
        out.append(f"vuln {sorted(vset(exp.get('vuln')))}->{sorted(vset(pred.get('vuln')))}")
    if not z["item"]:
        out.append(f"item '{exp.get('item')}'->'{pred.get('item')}'")
    if not z["lmk"]:
        out.append(f"lmk '{exp.get('landmark')}'->'{pred.get('landmark')}'")
    return out


def main(paths, diff_all=False):
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
    if not rows:
        print("no rows")
        return
    meta = rows[0]
    print(f"clips={len(rows)}  backend={meta.get('backend')}  model_load_ms={meta.get('model_load_ms')}\n")
    def first(key):
        lat = ((meta.get(key) or {}).get("latency_ms")) or {}
        return f"{lat.get('total')} ms (first token {lat.get('first_token')} ms)" if lat else "-"

    print(f"warm-up E2B: load {meta.get('model_load_ms')} ms, first turn on text row 0: {first('gemma_text')}")
    if any("gemma270_text" in r or "small_load_ms" in r for r in rows):
        print(f"warm-up 270M: load {meta.get('small_load_ms')} ms ({meta.get('small_backend')}), first turn: {first('gemma270_text')}")
    print()
    systems = [("gemma_audio", "Gemma 4 E2B on AUDIO (no ASR)"), ("gemma_text", "Gemma 4 E2B on reference TEXT"),
               ("gemma270_text", "Gemma 3 270M on reference TEXT"), ("rule", "RuleExtractor on reference TEXT")]
    for key, title in systems:
        by = defaultdict(list)
        for r in rows:
            if key == "gemma_audio" and not r.get("file"):
                continue  # text-only entry, no audio to evaluate
            if key != "gemma_audio" and not r.get("reference_transcript"):
                continue
            if key == "gemma270_text" and key not in r:
                continue  # 270M file was not pushed for this run
            by[r.get("lang", "?")].append(r)
        if not by:
            continue
        print(f"### {title}")
        print("| lang | n | cat | item | lmk | urg+-1 | ppl | vulnF1 | valid | p50 ms | p95 ms | p50 1st-tok ms |")
        print("|---|---|---|---|---|---|---|---|---|---|---|---|")
        allrows = [r for v in by.values() for r in v]
        for lang, rs in list(sorted(by.items())) + [("ALL", allrows)]:
            n = len(rs)
            cat = item = lmk = urg = ppl = valid = 0
            tp = fp = fn = 0
            tot, ft = [], []
            for r in rs:
                exp = r.get("expected") or {}
                pred, ok, t, f = pick(r, key)
                if ok:
                    valid += 1
                if t is not None:
                    tot.append(t)
                if f is not None:
                    ft.append(f)
                z = score(pred, ok, exp)
                cat += z["cat"]; item += z["item"]; lmk += z["lmk"]; urg += z["urg"]; ppl += z["ppl"]
                tp += z["tp"]; fp += z["fp"]; fn += z["fn"]
            vcol = "n/a" if key == "rule" else f"{valid}/{n}"
            v = f1(tp, fp, fn)
            print(f"| {lang} | {n} | {cat}/{n} | {item}/{n} | {lmk}/{n} | {urg}/{n} | {ppl}/{n} | "
                  f"{'n/a' if v is None else f'{v:.2f}'} | {vcol} | "
                  f"{fmt(pct(tot, 50))} | {fmt(pct(tot, 95))} | {fmt(pct(ft, 50))} |")
        print()
        if key == "gemma_audio" or diff_all:
            misses = []
            for r in allrows:
                exp = r.get("expected") or {}
                pred, ok, _, _ = pick(r, key)
                d = diff(r, pred, ok, exp)
                if d:
                    misses.append(f"  {r.get('file') or ('#' + str(r.get('index')))}: " + " | ".join(d))
            print(f"misses ({key}): {len(misses)}/{len(allrows)} clips" + ("" if misses else " - none"))
            for m in misses:
                print(m)
            print()
    pss = [r.get("pss_mb_after_audio") for r in rows if r.get("pss_mb_after_audio")]
    if pss:
        print(f"PSS after audio runs: min={min(pss):.0f} MB  max={max(pss):.0f} MB")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        sys.exit(__doc__)
    main(args, diff_all="--diff-all" in sys.argv)
