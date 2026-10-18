"""Shelter lists the control room builds with Gemini + Google Search, verifies, then publishes.

The shelter files shipped in ``data/`` are demo placeholders. Officers need the real list from the
disaster management authority (OSDMA for Puri / Odisha, DDMA for Delhi; elsewhere the State / District
Disaster Management Authority). Flow: **Discover -> Review/verify -> Publish**.

1. *Discover* (``POST /v1/ready/shelter-lists/discover``): one Gemini call with the Google Search tool asks for
   the authority's cyclone/flood shelter or relief camp list for the district. The answer is a draft list.
   An officer can also paste or type a list (``POST /v1/ready/shelter-lists``) without Gemini.
2. *Review*: every entry starts ``verified: false``. The officer fixes names, kinds, capacities and
   coordinates (``PATCH /v1/ready/shelter-lists/{id}``), follows each ``source_url`` and compares it with the
   authority's own document, then ticks ``verified``.
3. *Publish* (``POST .../{id}/publish``): only verified entries with coordinates inside the district are
   published; the district's previously published list is archived. From then on ``ready.shelters_for`` (pack,
   plans) and ``GET /v1/public/shelters`` (no auth) serve the published list instead of the demo files.

Trust model. The model's output is untrusted data. Per entry, deterministic checks decide what is kept:
name required (<= 120 chars), kind normalised, coordinates kept only if numeric and inside the district's
box (else dropped with an issue note), ``source_url`` must be http(s) and, on a model-made list, should be
one of the grounding sources (else flagged ``source_unconfirmed``), duplicates by normalised name dropped.
Text from the model is only ever stored and shown as text; it is never interpreted. Nothing the model says
reaches phones until an officer has verified the entry.

Google Search grounding terms. (a) Search Suggestions (``search_suggestions_html``, Google's rendered chips)
MUST be displayed wherever grounded results are shown: the dashboard renders them in a sandboxed iframe next
to the draft. (b) Search-grounded output must be reviewed against the authority's own document before
publishing; the officer who ticks ``verified`` is accountable. (c) Check the Gemini API terms on storing and
redistributing grounded results: published entries are meant to be the district's own verified data, not a
copy of model output. Google Maps grounding is not used (barred for emergency use).
"""

from __future__ import annotations

import json
import logging
import os
import re
import unicodedata
from typing import Any, Protocol
from urllib.parse import urlparse

from .agent import GenaiClient
from .textgen import DEFAULT_MODEL

log = logging.getLogger("relay.shelters")

KINDS = ("cyclone_shelter", "school", "relief_camp", "hospital", "other")
COORDS_FROM = ("source", "estimate", "officer", "none")
MAX_ENTRIES = 500
MAX_NAME = 120
MAX_TEXT = 300
MAX_URL = 500
MAX_SOURCES = 30
MAX_HTML = 20_000
MAX_CAPACITY = 1_000_000
FINDER_TIMEOUT_S = 60.0

AUTHORITIES = {
    "puri": "the Odisha State Disaster Management Authority (OSDMA) or the Puri District Collector / district administration",
    "delhi": "the Delhi Disaster Management Authority (DDMA) or the district magistrate's office",
}


class ShelterFinder(Protocol):
    """One grounded Gemini call. Returns a google-genai style response: ``.text`` and
    ``.candidates[0].grounding_metadata`` (tests inject a fake with the same attributes)."""

    def search(self, prompt: str) -> Any: ...


class GeminiShelterFinder(GenaiClient):
    """``GenaiClient`` plumbing (API key or Vertex, lazy client) with the Google Search tool.
    Search grounding does not combine with ``response_schema``, so the prompt asks for a fenced JSON block."""

    def search(self, prompt: str) -> Any:
        t = self._types
        return self._get().models.generate_content(
            model=self._model,
            contents=prompt,
            config=t.GenerateContentConfig(tools=[t.Tool(google_search=t.GoogleSearch())], temperature=0.1),
        )


def shelter_finder_from_env(env: dict[str, str] | None = None) -> ShelterFinder | None:
    """``SHELTER_GEMINI_MODEL`` (default ``textgen.DEFAULT_MODEL``); Vertex wins over an API key like
    ``textgen_from_env``; ``None`` = no credentials (discover answers 503)."""
    env = os.environ if env is None else env
    model = env.get("SHELTER_GEMINI_MODEL") or DEFAULT_MODEL
    try:
        if env.get("GOOGLE_GENAI_USE_VERTEXAI", "").strip().lower() in ("true", "1"):
            return GeminiShelterFinder(
                model=model, timeout_s=FINDER_TIMEOUT_S, vertexai=True,
                project=env.get("GOOGLE_CLOUD_PROJECT") or None, location=env.get("GOOGLE_CLOUD_LOCATION") or "global",
            )
        if env.get("GEMINI_API_KEY"):
            return GeminiShelterFinder(env["GEMINI_API_KEY"], model, timeout_s=FINDER_TIMEOUT_S)
    except Exception as e:
        log.error("shelter finder init failed (%s)", e)
    return None


def model_name(finder: Any) -> str:
    return str(getattr(finder, "_model", None) or os.environ.get("SHELTER_GEMINI_MODEL") or DEFAULT_MODEL)


# --------------------------------------------------------------------------------------
# prompt and response parsing
# --------------------------------------------------------------------------------------

def build_prompt(district_name: str, district_id: str) -> str:
    authority = AUTHORITIES.get(district_id, "the State Disaster Management Authority or the District Disaster Management Authority")
    return f"""Use Google Search to find the OFFICIAL list of cyclone shelters, multipurpose cyclone shelters, flood shelters and relief camps for {district_name} district, India.
Prefer documents and pages from {authority}, and other .gov.in / .nic.in sites. Do not use news articles or blogs when an official list exists.
List every shelter you can find in an official source. Do not invent entries. If a value is not in the source, leave it out (null).
Coordinates: give lat/lon only when the source itself states them (coords_from "source"); if you can only estimate a location, give your estimate with coords_from "estimate"; if you do not know, omit lat/lon and use coords_from "none".
Answer with ONE fenced JSON block and nothing important outside it: a JSON array of objects with exactly these keys:
  "name" (English name), "name_local" (name in the local script, or null), "kind" (one of cyclone_shelter, school, relief_camp, hospital, other),
  "address", "block" (block / tehsil, or null), "capacity" (integer people, or null), "lat" (number or null), "lon" (number or null),
  "coords_from" ("source", "estimate" or "none"), "source_url" (the page or document this entry came from).
```json
[]
```"""


def _find_json(text: str) -> Any:
    """Defensive parse: a fenced block, else the outermost array/object in the text."""
    candidates: list[str] = [m.group(1) for m in re.finditer(r"```(?:json|JSON)?\s*\n?(.*?)```", text, re.S)]
    for opener, closer in (("[", "]"), ("{", "}")):
        i, j = text.find(opener), text.rfind(closer)
        if 0 <= i < j:
            candidates.append(text[i:j + 1])
    for c in candidates:
        try:
            return json.loads(c.strip())
        except ValueError:
            continue
    raise ValueError("the model answer contains no parseable JSON list")


def parse_entries(text: str) -> list[Any]:
    data = _find_json(text or "")
    if isinstance(data, dict):
        for key in ("shelters", "entries", "items", "results"):
            if isinstance(data.get(key), list):
                data = data[key]
                break
        else:
            data = [data]
    if not isinstance(data, list):
        raise ValueError("the model answer is not a list")
    return data


def extract_grounding(resp: Any) -> tuple[list[dict[str, str]], str]:
    """``(sources, search_suggestions_html)`` from a google-genai response; missing parts are empty."""
    sources: list[dict[str, str]] = []
    html = ""
    seen: set[str] = set()
    for cand in (getattr(resp, "candidates", None) or [])[:1]:
        gm = getattr(cand, "grounding_metadata", None)
        if gm is None:
            continue
        for ch in getattr(gm, "grounding_chunks", None) or []:
            web = getattr(ch, "web", None)
            uri = str(getattr(web, "uri", "") or "")
            if web is None or not uri or uri in seen or len(sources) >= MAX_SOURCES:
                continue
            seen.add(uri)
            sources.append({"uri": uri[:MAX_URL * 2], "title": clean(getattr(web, "title", ""), MAX_TEXT)})
        sep = getattr(gm, "search_entry_point", None)
        html = str(getattr(sep, "rendered_content", "") or "")[:MAX_HTML]
    return sources, html


# --------------------------------------------------------------------------------------
# deterministic validation (the model's output and officers' edits both go through this)
# --------------------------------------------------------------------------------------

_CTRL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f​-‏‪-‮⁦-⁩]")
_KIND_WORDS = (
    ("cyclone", "cyclone_shelter"), ("mcs", "cyclone_shelter"), ("relief", "relief_camp"), ("camp", "relief_camp"),
    ("school", "school"), ("college", "school"), ("hospital", "hospital"), ("health", "hospital"),
)


def clean(v: Any, limit: int) -> str:
    if v is None or isinstance(v, (dict, list)):
        return ""
    s = unicodedata.normalize("NFC", _CTRL.sub(" ", str(v)))
    return re.sub(r"[ \t]+", " ", s).strip()[:limit]


def norm_name(name: str) -> str:
    return re.sub(r"[\W_]+", " ", unicodedata.normalize("NFKC", name).casefold()).strip()


def norm_kind(v: Any) -> str:
    s = clean(v, 60).casefold().replace("-", "_").replace(" ", "_")
    if s in KINDS:
        return s
    return next((k for w, k in _KIND_WORDS if w in s), "other")


def _num(v: Any) -> float | None:
    if isinstance(v, bool) or v is None:
        return None
    try:
        f = float(str(v).strip()) if isinstance(v, str) else float(v)
    except (TypeError, ValueError):
        return None
    return f if f == f and abs(f) != float("inf") else None


def host_of(url: str) -> str:
    return (urlparse(url).hostname or "").lower().removeprefix("www.")


def source_confirmed(url: str, sources: list[dict[str, str]]) -> bool:
    """Grounding URIs are usually Google redirect links whose ``title`` is the site's domain, so a
    source_url is confirmed by an exact URI, or by its host matching a source's title or URI host."""
    h = host_of(url)
    for s in sources:
        if url == s.get("uri"):
            return True
        title = (s.get("title") or "").lower().removeprefix("www.")
        if h and (h == title or h == host_of(s.get("uri", ""))):
            return True
    return False


def in_bbox(lat: float, lon: float, bbox: tuple[float, float, float, float]) -> bool:
    s, w, n, e = bbox
    return s <= lat <= n and w <= lon <= e


def validate_entries(
    raw: list[Any], district_id: str, sources: list[dict[str, str]], *, discovered: bool = False,
) -> tuple[list[dict[str, Any]], list[str]]:
    """``(entries, notes)``. Each entry: ``id, name, name_local, kind, address, block, capacity, lat, lon,
    coords_from, source_url, verified, issues``. ``discovered`` (model output) forces ``verified: false`` and
    keeps only coords_from source/estimate/none; officers' edits may set ``verified`` and coords_from "officer"."""
    from .ready import DISTRICT_BBOX  # late: ready imports this module lazily too

    bbox = DISTRICT_BBOX[district_id]
    out: list[dict[str, Any]] = []
    notes: list[str] = []
    seen: dict[str, str] = {}
    if len(raw) > MAX_ENTRIES:
        notes.append(f"only the first {MAX_ENTRIES} of {len(raw)} entries were kept")
    next_id = 1 + max((int(m.group(1)) for r in raw if isinstance(r, dict) and (m := re.fullmatch(r"e(\d+)", str(r.get("id", "")))) ), default=0)
    used_ids: set[str] = set()
    for r in raw[:MAX_ENTRIES]:
        if not isinstance(r, dict):
            notes.append("an entry that is not an object was dropped")
            continue
        name = clean(r.get("name"), 400)
        if not name:
            notes.append("an entry without a name was dropped")
            continue
        if len(name) > MAX_NAME:
            notes.append(f"entry with a name over {MAX_NAME} characters was dropped: {name[:40]}...")
            continue
        key = norm_name(name)
        if key in seen:
            notes.append(f"duplicate of {seen[key]!r} dropped")
            continue
        seen[key] = name
        issues: list[str] = []
        lat, lon = _num(r.get("lat")), _num(r.get("lon"))
        coords_from = clean(r.get("coords_from"), 20).casefold()
        if coords_from not in COORDS_FROM or (discovered and coords_from == "officer"):
            coords_from = ("source" if discovered else "officer") if lat is not None and lon is not None else "none"
        if r.get("lat") not in (None, "") or r.get("lon") not in (None, ""):
            if lat is None or lon is None:
                issues.append("coordinates are not numeric and were dropped")
                lat = lon = None
            elif not in_bbox(lat, lon, bbox):
                issues.append(f"coordinates ({lat:g}, {lon:g}) are outside {district_id} and were dropped")
                lat = lon = None
        if lat is None:
            coords_from = "none"
            if not issues:
                issues.append("no coordinates: set them on the map before publishing")
        elif coords_from == "none":
            coords_from = "officer"
        if coords_from == "estimate":
            issues.append("coordinates are the model's estimate: check them on the map")
        url = clean(r.get("source_url"), MAX_URL)
        if url and urlparse(url).scheme not in ("http", "https"):
            issues.append("source_url is not an http(s) link and was dropped")
            url = ""
        elif url and not urlparse(url).hostname:
            issues.append("source_url is malformed and was dropped")
            url = ""
        if url and sources and not source_confirmed(url, sources):
            issues.append("source_unconfirmed: the link is not among the search sources")
        if not url:
            issues.append("no source link")
        cap = r.get("capacity")
        cap_n = _num(cap)
        capacity = int(cap_n) if cap_n is not None and cap_n == int(cap_n) and 0 < cap_n <= MAX_CAPACITY else None
        if cap not in (None, "") and capacity is None:
            issues.append("capacity is not a positive whole number and was dropped")
        eid = clean(r.get("id"), 20)
        if not re.fullmatch(r"e\d+", eid) or eid in used_ids:
            eid = f"e{next_id}"
            next_id += 1
        used_ids.add(eid)
        out.append({
            "id": eid, "name": name, "name_local": clean(r.get("name_local"), MAX_NAME) or None,
            "kind": norm_kind(r.get("kind")), "address": clean(r.get("address"), MAX_TEXT),
            "block": clean(r.get("block"), 80) or None, "capacity": capacity, "lat": lat, "lon": lon,
            "coords_from": coords_from, "source_url": url or None,
            "verified": False if discovered else r.get("verified") is True, "issues": issues,
        })
    return out, notes


# --------------------------------------------------------------------------------------
# list records
# --------------------------------------------------------------------------------------

def discover(district_id: str, district_name: str, finder: ShelterFinder, now: float) -> dict[str, Any]:
    """One grounded call -> an unsaved draft record (``id`` is assigned by the store). Raises on a failed
    call or an answer with no parseable list; the caller turns that into a 502."""
    resp = finder.search(build_prompt(district_name, district_id))
    sources, html = extract_grounding(resp)
    entries, notes = validate_entries(parse_entries(getattr(resp, "text", "") or ""), district_id, sources, discovered=True)
    if not sources:
        notes.append("the answer came with no search sources: treat every entry as unconfirmed")
    return new_record(district_id, entries, now, sources=sources, html=html, model=model_name(finder), notes=notes, origin="gemini")


def new_record(
    district_id: str, entries: list[dict[str, Any]], now: float, *, sources: list[dict[str, str]] | None = None,
    html: str = "", model: str | None = None, notes: list[str] | None = None, origin: str = "manual",
) -> dict[str, Any]:
    return {
        "district": district_id, "status": "draft", "created_at": int(now), "entries": entries,
        "sources": sources or [], "search_suggestions_html": html, "model": model, "notes": notes or [],
        "origin": origin, "published_at": None, "published_by_note": None,
    }


def publishable(entry: dict[str, Any], district_id: str) -> str | None:
    """None when the entry may be published, else why not."""
    from .ready import DISTRICT_BBOX

    if not entry.get("verified"):
        return "not verified"
    lat, lon = entry.get("lat"), entry.get("lon")
    if lat is None or lon is None:
        return "no coordinates"
    if not in_bbox(lat, lon, DISTRICT_BBOX[district_id]):
        return "coordinates outside the district"
    return None


def split_for_publish(rec: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    ok, skipped = [], []
    for e in rec["entries"]:
        why = publishable(e, rec["district"])
        (skipped.append({"id": e["id"], "name": e["name"], "reason": why}) if why else ok.append(e))
    return ok, skipped


def published_shelters(rec: dict[str, Any]) -> list[dict[str, Any]]:
    """The list's public face: verified entries with in-district coordinates. Ids are unique across lists."""
    return [
        {
            "id": f"L{rec['id']}-{e['id']}", "name": e["name"], "name_local": e.get("name_local"), "kind": e["kind"],
            "address": e.get("address", ""), "block": e.get("block"), "lat": e["lat"], "lon": e["lon"],
            "capacity": e.get("capacity"), "source_url": e.get("source_url"),
        }
        for e in split_for_publish(rec)[0]
    ]


def current_published(store: Any, district_id: str) -> dict[str, Any] | None:
    return next((r for r in store.list_shelter_lists(district_id) if r.get("status") == "published"), None)
