"""Relay backend HTTP API (protocol/SPEC.md section 7)."""

from __future__ import annotations

import base64
import hashlib
import hmac
import logging
import os
import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Callable

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, field_validator
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import dispatch as dispatching
from . import sitrep as sitreps
from . import teams as team_data
from .agent import Agent, IncomingNeed, TriagePipeline, agent_from_env, build_ack
from .codec import (
    CATEGORY_NAMES,
    HINT_ACC_MAX_M,
    INITIAL_TTL,
    LANG_CODES,
    RSSI_UNKNOWN,
    BROADCAST_TTL_S,
    DISASTER_CODES,
    STATUS_DISPATCHED,
    STATUS_ON_THE_WAY,
    STATUS_RESOLVED,
    TYPE_LOC_HINT,
    TYPE_NEED,
    VULN_NAMES,
    Broadcast,
    CodecError,
    LocHint,
    Need,
    answers_from_json,
    decode_frame,
    decode_loc_hint,
    decode_need,
    encode_broadcast,
    encode_frame,
    encode_loc_hint,
    encode_need,
    to_e5,
)
from .geocode import Geocoder
from .location import add_no_fix_note, best_loc_acc, scrub_legacy_draft, ticket_location
from .priority import vuln_names
from .pubsub import (
    Publisher,
    PubSubPublisher,
    TokenVerifier,
    build_messages,
    google_token_verifier,
    parse_push_envelope,
)
from .store import Store, TeamAtCapacity, open_store
from .textgen import TextGen, textgen_from_env

log = logging.getLogger("relay.api")

MAX_ACKS_PER_SYNC = 50
MAX_FRAMES_PER_SYNC = 500
GATEWAY_ONLINE_WINDOW_S = 60.0
DEV_GATEWAY = "0000000000000000"
DEFAULT_CORS = ["http://localhost:5173", "http://127.0.0.1:5173"]
HEX16 = r"^[0-9a-fA-F]{16}$"
TRIAGE_BUDGET_S = 50.0  # /internal/triage: ack deadline is 60 s, request timeout 120 s
# paths owned by the API; the static mount must never answer for them
API_PREFIXES = ("v1", "internal", "healthz")


# --------------------------------------------------------------------------------------
# request models
# --------------------------------------------------------------------------------------

class SyncRequest(BaseModel):
    gateway_node: str = Field(pattern=HEX16)
    frames: list[str] = Field(default_factory=list, max_length=MAX_FRAMES_PER_SYNC)
    ack_cursor: int = Field(default=0, ge=0)


class BroadcastRequest(BaseModel):
    """The control room declares a disaster (every phone in reach then asks the right questions for 24 h) or clears it."""

    kind: str = "disaster"
    disaster: str
    lat: float | None = Field(default=None, ge=-90, le=90)
    lon: float | None = Field(default=None, ge=-180, le=180)
    radius_km: int = Field(default=0, ge=0, le=65535)  # 0 = the whole control-room area

    @field_validator("kind")
    @classmethod
    def _kind(cls, v: str) -> str:
        if v not in ("disaster", "cleared"):
            raise ValueError("kind must be 'disaster' or 'cleared'")
        return v

    @field_validator("disaster")
    @classmethod
    def _disaster(cls, v: str) -> str:
        if v not in DISASTER_CODES.values():
            raise ValueError(f"disaster must be one of {sorted(DISASTER_CODES.values())}")
        return v


class MeshHintJson(BaseModel):
    """Dev-inject only: a nearby phone with a fix that heard this (no-fix) NEED directly from its requester."""

    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    accuracy_m: float = Field(default=20, gt=0, le=100)
    rssi_dbm: int | None = Field(default=None, ge=-127, le=0)


class NeedJson(BaseModel):
    category: str
    urgency: int = Field(ge=0, le=3)
    people: int = Field(default=1, ge=0, le=255)
    vuln_flags: list[str] = Field(default_factory=list)
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    lang: str = "en"
    item: str = ""
    landmark: str = ""
    summary: str = ""
    origin_node: str | None = Field(default=None, pattern=HEX16)
    loc_acc: int = Field(default=1, ge=0, le=5)  # extra, optional
    created_at: int | None = Field(default=None, ge=0, lt=1 << 32)  # extra, optional (unix s)
    answers: dict[str, str] = Field(default_factory=dict)  # extra, optional: {question key: answer key}, max 3
    mesh_hints: list[MeshHintJson] = Field(default_factory=list, max_length=8)  # extra, optional: needs loc_acc 0

    @field_validator("category")
    @classmethod
    def _category(cls, v: str) -> str:
        v = v.strip().upper()
        if v not in CATEGORY_NAMES:
            raise ValueError(f"category must be one of {list(CATEGORY_NAMES)}")
        return v

    @field_validator("vuln_flags")
    @classmethod
    def _flags(cls, v: list[str]) -> list[str]:
        out = [f.strip().upper() for f in v]
        bad = [f for f in out if f not in VULN_NAMES]
        if bad:
            raise ValueError(f"unknown vuln flags {bad}; allowed {list(VULN_NAMES)}")
        return out

    @field_validator("answers")
    @classmethod
    def _answers(cls, v: dict[str, str]) -> dict[str, str]:
        if len(v) > 3:
            raise ValueError("at most 3 answers")
        try:
            answers_from_json(v)
        except CodecError as e:
            raise ValueError(str(e)) from e
        return v

    @field_validator("lang")
    @classmethod
    def _lang(cls, v: str) -> str:
        v = v.strip().lower()
        if v not in LANG_CODES:
            raise ValueError(f"lang must be one of {list(LANG_CODES)}")
        return v


class ApproveRequest(BaseModel):
    """Optional body of ``POST /v1/tickets/{no}/approve``; absent fields fall back to the pending
    proposal, and with neither the approval is the plain 45 min one."""

    team_id: str | None = Field(default=None, max_length=64)
    eta_min: int | None = Field(default=None, ge=0, le=dispatching.MAX_ETA_MIN)
    order_en: str | None = Field(default=None, max_length=dispatching.MAX_ORDER_CHARS)


class OnTheWayRequest(BaseModel):
    """Optional body of ``POST /v1/tickets/{no}/on-the-way``: the ETA for the on-the-way ACK."""

    eta_min: int | None = Field(default=None, ge=0, le=dispatching.MAX_ETA_MIN)


class InjectRequest(BaseModel):
    needs: list[NeedJson] = Field(max_length=200)


# --------------------------------------------------------------------------------------
# serialisation
# --------------------------------------------------------------------------------------

def _ticket_json(
    row: dict[str, Any], links: list[dict[str, Any]], record: dict[str, Any] | None = None
) -> dict[str, Any]:
    gateways: list[str] = []
    for lk in links:
        if lk["gateway_node"] not in gateways:
            gateways.append(lk["gateway_node"])
    loc = ticket_location(row, links)
    draft_en, draft_local, reason = row["dispatch_draft_en"], row["dispatch_draft_local"], row["priority_reason"]
    if not loc["has_location"]:  # rows triaged before no-fix handling carry "0.00000, 0.00000" in their text
        draft_en, draft_local = (scrub_legacy_draft(d, row["landmark"]) for d in (draft_en, draft_local))
        reason = add_no_fix_note(reason, row["landmark"])
    out = {
        "ticket_no": row["ticket_no"],
        "category": row["category"],
        "item": row["item"],
        "landmark": row["landmark"],
        "summary": row["summary"],
        **loc,
        "urgency": row["urgency"],
        "people": row["people"],
        "vuln_flags": vuln_names(row["vuln_flags"]),
        "lang": row["lang"],
        "report_count": row["report_count"],
        "priority_score": row["priority_score"],
        "priority_tier": row["priority_tier"],
        "priority_reason": reason,
        "status": row["status"],
        "dispatch_draft": {"en": draft_en, "local": draft_local},
        "first_reported_at": row["first_reported_at"],
        "max_hops": row["max_hops"],
        "gateways": gateways,
        "msg_ids": [lk["msg_id"] for lk in links],
    }
    # Optional fields, absent when unset. ``geo`` is an estimate from the landmark for a ticket with no
    # GPS fix (has_location stays false); ``reclass`` is {from, to, reason} and ``category`` is the new one;
    # ``answers`` is {question key: answer key} from the intake follow-up questions.
    for key in ("geo", "reclass", "answers"):
        if row.get(key):
            out[key] = row[key]
    for key in ("proposal", "dispatch"):  # optional: absent until proposed / approved
        if record and record.get(key):
            out[key] = record[key]
    return out


def _broadcast_json(b: Broadcast) -> dict[str, Any]:
    out: dict[str, Any] = {
        "id": b.bcast_id.hex(), "kind": b.kind, "disaster": b.disaster, "issued_at": b.issued_at,
        "expires_at": b.issued_at + BROADCAST_TTL_S,
    }
    if b.radius_km:
        out["area"] = {"lat": b.lat_e5 / 100000, "lon": b.lon_e5 / 100000, "radius_km": b.radius_km}
    return out


def _packet_json(p: dict[str, Any]) -> dict[str, Any]:
    return {
        "msg_id": p["msg_id"],
        "origin_node": p["origin_node"],
        "gateway_node": p["gateway_node"],
        "hops": p["hops"],
        "loc_acc": p["loc_acc"],
        "received_at": p["received_at"],
        "reported_at": p["reported_at"],
        "summary": p["summary"],
    }


def decode_frames(b64_frames: list[str]) -> tuple[list[IncomingNeed], int]:
    """Validate uploaded frames. Returns (decoded NEEDs, number rejected); LOC_HINT frames count as rejected here
    (``/v1/sync`` takes them out first with ``split_hint_frames``)."""
    items: list[IncomingNeed] = []
    rejected = 0
    for b64 in b64_frames:
        try:
            raw = base64.b64decode(b64, validate=True)
            frame = decode_frame(raw)
            if frame.type != TYPE_NEED:
                raise CodecError("not a NEED")
            need = decode_need(frame.payload)
            if abs(need.lat_e5) > 9_000_000 or abs(need.lon_e5) > 18_000_000:
                raise CodecError("coordinates out of range")
        except ValueError:  # CodecError and binascii.Error are both ValueErrors
            rejected += 1
            continue
        items.append(IncomingNeed(raw, need, frame.ttl, b64))
    return items, rejected


def split_hint_frames(b64_frames: list[str]) -> tuple[list[str], list[LocHint], int]:
    """Take the LOC_HINT frames out of an upload: (the other frames, decoded hints, hints that did not decode or whose
    relay is not the frame's sender). Frames that are not hints are left alone for ``decode_frames``."""
    rest: list[str] = []
    hints: list[LocHint] = []
    rejected = 0
    for b64 in b64_frames:
        try:
            frame = decode_frame(base64.b64decode(b64, validate=True))
        except ValueError:
            rest.append(b64)  # decode_frames counts it as rejected
            continue
        if frame.type != TYPE_LOC_HINT:
            rest.append(b64)
            continue
        try:
            hint = decode_loc_hint(frame.payload)
            if hint.relay_node != frame.sender_id:
                raise CodecError("relay_node is not the frame sender")
        except CodecError:
            rejected += 1
            continue
        hints.append(hint)
    return rest, hints, rejected


class SpaStaticFiles(StaticFiles):
    """Static dashboard with ``index.html`` fallback for client-side routes.

    Mounted after every API route, so it only sees paths no route claimed. Anything under an
    API prefix stays a plain 404 (never the dashboard HTML), and so does a missing file
    (last segment has an extension), so a broken asset URL is not masked by a 200 page.
    """

    async def get_response(self, path: str, scope):
        first = path.strip("/").split("/", 1)[0]
        try:
            resp = await super().get_response(path, scope)
        except (StarletteHTTPException) as e:
            if e.status_code != 404 or first in API_PREFIXES or "." in path.rsplit("/", 1)[-1]:
                raise
            return await super().get_response("index.html", scope)
        if resp.status_code == 404 and first not in API_PREFIXES and "." not in path.rsplit("/", 1)[-1]:
            return await super().get_response("index.html", scope)
        return resp


# --------------------------------------------------------------------------------------
# app factory
# --------------------------------------------------------------------------------------

def create_app(
    *,
    db_path: str | None = None,
    store: Store | None = None,
    agent: Agent | None = None,
    clock: Callable[[], float] | None = None,
    device_key: str | None = None,
    operator_key: str | None = None,
    env: str | None = None,
    enrich_budget_s: float | None = None,
    pubsub_topic: str | None = None,
    publisher: Publisher | None = None,
    token_verifier: TokenVerifier | None = None,
    pubsub_audience: str | None = None,
    invoker_sa: str | None = None,
    static_dir: str | None = None,
    triage_budget_s: float | None = None,
    textgen: TextGen | None = None,
    sitrep_textgen: TextGen | None = None,
    geocoder: Geocoder | None = None,
) -> FastAPI:
    """Every keyword defaults to its environment variable (see README); the keywords exist so
    tests can inject fakes. ``pubsub_topic=""`` forces in-process triage. ``textgen`` writes dispatch
    orders and ``sitrep_textgen`` the sitrep (``SITREP_GEMINI_MODEL``, default ``GEMINI_MODEL``);
    both default to Gemini when the environment enables it, else to templates. ``geocoder`` (Maps-grounded
    landmark lookup, also used by ``POST /v1/tickets/{no}/locate``) defaults to ``RELAY_GEOCODER``."""
    clock = clock or time.time
    device_key = device_key if device_key is not None else os.environ.get("RELAY_DEVICE_KEY", "dev-relay-key")
    operator_key = operator_key if operator_key is not None else os.environ.get("RELAY_OPERATOR_KEY", "")
    env = env if env is not None else os.environ.get("RELAY_ENV", "dev")
    cors = DEFAULT_CORS + [o.strip() for o in os.environ.get("RELAY_CORS_ORIGINS", "").split(",") if o.strip()]
    topic = (pubsub_topic if pubsub_topic is not None else os.environ.get("RELAY_PUBSUB_TOPIC", "")).strip()
    audience = (pubsub_audience if pubsub_audience is not None else os.environ.get("RELAY_PUBSUB_AUDIENCE", "")).strip()
    invoker = (invoker_sa if invoker_sa is not None else os.environ.get("RELAY_PUBSUB_INVOKER_SA", "")).strip()
    static_path = static_dir if static_dir is not None else os.environ.get("RELAY_STATIC_DIR", "")
    triage_budget = (
        triage_budget_s if triage_budget_s is not None
        else float(os.environ.get("RELAY_TRIAGE_BUDGET_S", str(TRIAGE_BUDGET_S)))
    )
    if token_verifier is None and audience:
        token_verifier = google_token_verifier(audience)
    if topic and publisher is None:
        publisher = PubSubPublisher()

    textgen = textgen if textgen is not None else textgen_from_env("GEMINI_MODEL")
    sitrep_textgen = sitrep_textgen if sitrep_textgen is not None else textgen_from_env("SITREP_GEMINI_MODEL")
    store = store if store is not None else open_store(db_path=db_path)
    pipeline = TriagePipeline(
        store,
        agent if agent is not None else agent_from_env(),
        clock=clock,
        enrich_budget_s=enrich_budget_s if enrich_budget_s is not None else float(os.environ.get("RELAY_AGENT_BUDGET_S", "20")),
        geocoder=geocoder,
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        store.close()

    app = FastAPI(title="Relay backend", version="0.1.0", lifespan=lifespan)
    app.state.store = store
    app.state.pipeline = pipeline
    app.add_middleware(
        CORSMiddleware, allow_origins=cors, allow_methods=["*"], allow_headers=["*"],
    )

    # hash first: compare_digest then sees equal-length inputs, so the length is not leaked either
    device_key_digest = hashlib.sha256(device_key.encode()).digest()

    def require_key(x_relay_key: str | None = Header(default=None)) -> None:
        ok = x_relay_key is not None and hmac.compare_digest(
            hashlib.sha256(x_relay_key.encode()).digest(), device_key_digest
        )
        if not ok:
            raise HTTPException(status_code=401, detail="invalid or missing X-Relay-Key")

    # Control-room routes: open while RELAY_OPERATOR_KEY is unset, X-Operator-Key required once it is set.
    operator_key_digest = hashlib.sha256(operator_key.encode()).digest() if operator_key else None

    def require_operator(x_operator_key: str | None = Header(default=None)) -> None:
        if operator_key_digest is None:
            return
        ok = x_operator_key is not None and hmac.compare_digest(
            hashlib.sha256(x_operator_key.encode()).digest(), operator_key_digest
        )
        if not ok:
            raise HTTPException(status_code=401, detail="invalid or missing X-Operator-Key")

    operator = [Depends(require_operator)]

    # Teams are seeded on first use, not at startup: importing the app must not touch the store.
    teams_seeded = threading.Event()
    seed_lock = threading.Lock()

    def ensure_teams() -> None:
        if not teams_seeded.is_set():
            with seed_lock:
                if not teams_seeded.is_set():
                    store.seed_teams(team_data.load_all_seeds())
                    teams_seeded.set()

    def team_views() -> list[dict[str, Any]]:
        ensure_teams()
        loads = store.team_loads()
        return [team_data.team_view(t, loads) for t in store.list_teams()]

    def ticket_or_404(ticket_no: int) -> dict[str, Any]:
        row = store.get_ticket(ticket_no)
        if row is None:
            raise HTTPException(status_code=404, detail="ticket not found")
        return row

    # ------------------------------------------------------------------ health
    # /healthz is for the Cloud Run startup probe. Cloud Run's front end reserves
    # /healthz for external requests, so callers outside the service use /v1/health.
    @app.get("/healthz")
    @app.get("/v1/health")
    def healthz() -> dict[str, bool]:
        return {"ok": True}  # deliberately touches nothing: no store, no Pub/Sub

    # ------------------------------------------------------------------ gateway sync
    @app.post("/v1/sync", dependencies=[Depends(require_key)])
    def sync(req: SyncRequest) -> dict[str, Any]:
        gateway = req.gateway_node.lower()
        store.touch_gateway(gateway, clock())
        other, hints, hints_bad = split_hint_frames(req.frames)
        items, rejected = decode_frames(other)

        if topic and publisher is not None:
            accepted, duplicates = publish_batch(req.gateway_node, gateway, items)
        else:
            res = pipeline.process_batch(gateway, items)
            accepted, duplicates = res.accepted, res.duplicates

        # Location hints after the NEEDs, so a hint for a NEED in this very upload finds its ticket at once. Applied
        # in-process in both triage modes: it is a few small writes, and a hint for a NEED that Pub/Sub has not
        # triaged yet is kept and applied when that NEED is.
        hint_res = pipeline.process_hints(gateway, hints) if hints else None

        cursor = store.clamp_ack_cursor(req.ack_cursor)  # beyond the newest ack = store was reset
        live_broadcasts = store.live_broadcasts(int(clock()) - BROADCAST_TTL_S)
        acks = store.acks_after(cursor, MAX_ACKS_PER_SYNC)
        out = {
            "accepted": accepted,
            "duplicates": duplicates,
            "rejected": rejected,
            "acks": [base64.b64encode(a["payload"]).decode("ascii") for a in acks],
            "ack_cursor": acks[-1]["id"] if acks else cursor,
        }
        if live_broadcasts:  # every sync returns every live broadcast, so every gateway puts it into the mesh (SPEC 5.6)
            out["broadcasts"] = [base64.b64encode(p).decode("ascii") for p in live_broadcasts]
        if hint_res is not None or hints_bad:  # only when the upload held hints: older gateways see no change
            out["hints"] = {
                "accepted": hint_res.accepted if hint_res else 0,
                "duplicates": hint_res.duplicates if hint_res else 0,
                "rejected": hints_bad + (hint_res.rejected if hint_res else 0),
            }
        return out

    def publish_batch(gateway_as_sent: str, gateway: str, items: list[IncomingNeed]) -> tuple[int, int]:
        """Pub/Sub mode: record packets idempotently, publish the ones not yet triaged.

        A frame stored by an earlier sync whose triage never happened (publish failed, message
        dead-lettered) is published again, so a gateway retry heals it instead of leaving the
        packet stranded. Triage dedupes, so republishing is always safe."""
        assert publisher is not None
        now_i = int(clock())
        unique: list[IncomingNeed] = []
        seen: set[bytes] = set()
        for it in items:
            if it.need.msg_id not in seen:
                seen.add(it.need.msg_id)
                unique.append(it)
        statuses = store.record_packets([pipeline.to_packet(it, now_i) for it in unique], gateway, now_i)
        to_publish = [it.b64 for it, st in zip(unique, statuses) if st != "done"]
        try:
            for data in build_messages(gateway_as_sent, to_publish):
                publisher.publish(topic, data)
        except Exception as e:
            log.exception("pub/sub publish failed")
            raise HTTPException(status_code=503, detail="could not queue batch for triage; retry") from e
        accepted = statuses.count("new")
        return accepted, len(items) - accepted

    # ------------------------------------------------------------------ pub/sub push target
    def authenticate_push(authorization: str | None) -> None:
        """OIDC check. The route is public, so this is the only guard on it."""
        if not authorization or authorization[:7].lower() != "bearer " or not authorization[7:].strip():
            raise HTTPException(status_code=401, detail="missing bearer token")
        if token_verifier is None or not invoker:
            log.error("/internal/triage called but RELAY_PUBSUB_AUDIENCE / RELAY_PUBSUB_INVOKER_SA are not set")
            raise HTTPException(status_code=503, detail="triage endpoint is not configured")
        try:
            claims = token_verifier(authorization[7:].strip())
        except Exception as e:
            from google.auth.exceptions import TransportError

            if isinstance(e, TransportError):  # could not fetch Google's certs: not the caller's fault
                log.warning("token verification unavailable: %s", e)
                raise HTTPException(status_code=503, detail="token verification unavailable") from e
            raise HTTPException(status_code=401, detail="invalid token") from e
        if claims.get("email_verified") not in (True, "true"):
            raise HTTPException(status_code=403, detail="caller email not verified")
        email = claims.get("email")
        if not isinstance(email, str) or not hmac.compare_digest(email.encode(), invoker.encode()):
            raise HTTPException(status_code=403, detail="caller is not the expected invoker")

    def run_triage(raw: bytes) -> dict[str, Any]:
        try:
            gateway, frames = parse_push_envelope(raw)
        except ValueError as e:
            # permanently bad: 4xx retries then dead-letters it, where it can be inspected
            raise HTTPException(status_code=400, detail=str(e)) from e
        items, rejected = decode_frames(frames)  # bad frames inside a good message are dropped
        res = pipeline.process_batch(gateway.lower(), items, total_budget_s=triage_budget)
        if res.incomplete:
            raise HTTPException(status_code=503, detail="triage deadline reached; redeliver")
        return {"accepted": res.accepted, "duplicates": res.duplicates, "rejected": rejected}

    @app.post("/internal/triage")
    async def triage(request: Request) -> dict[str, Any]:
        # Auth strictly before the body is read or parsed. Not part of the /v1 device-key scheme:
        # X-Relay-Key is not looked at here.
        await run_in_threadpool(authenticate_push, request.headers.get("authorization"))
        return await run_in_threadpool(run_triage, await request.body())

    # ------------------------------------------------------------------ dashboard
    @app.get("/v1/tickets", dependencies=operator)
    def list_tickets() -> dict[str, Any]:
        records = store.dispatch_records()
        return {"tickets": [_ticket_json(r, links, records.get(r["ticket_no"])) for r, links in store.list_tickets_with_links()]}

    @app.get("/v1/tickets/{ticket_no}", dependencies=operator)
    def get_ticket(ticket_no: int) -> dict[str, Any]:
        row = ticket_or_404(ticket_no)
        packets = store.ticket_packets(ticket_no)
        out = _ticket_json(row, packets, store.get_dispatch_record(ticket_no))
        out["packets"] = [_packet_json(p) for p in packets]
        return out

    @app.post("/v1/tickets/{ticket_no}/locate", dependencies=operator)
    def locate(ticket_no: int) -> dict[str, Any]:
        """Estimate where a no-fix ticket is from its landmark now: the gazetteer, then the Maps geocoder. Writes
        ``geo`` (replacing an earlier landmark estimate) and returns the ticket. 409 for a ticket with a GPS fix or
        a mesh estimate, 503 if only the (unconfigured) Maps geocoder could help, 422 if nothing confident was found."""
        row = ticket_or_404(ticket_no)
        packets = store.ticket_packets(ticket_no)
        if best_loc_acc(p["loc_acc"] for p in packets) != 0:
            raise HTTPException(status_code=409, detail="ticket has a GPS fix")
        if (row.get("geo") or {}).get("source") == "mesh":
            raise HTTPException(status_code=409, detail="ticket already has a mesh location estimate")
        landmark = (row["landmark"] or "").strip()
        if not landmark:
            raise HTTPException(status_code=422, detail="ticket has no landmark to locate")
        geo = pipeline.gazetteer.locate(landmark)
        if geo is None:
            if pipeline.geocoder.name == "none":
                raise HTTPException(status_code=503, detail="no Maps geocoder configured (RELAY_GEOCODER=gemini_maps)")
            geo = pipeline.geocoder.locate(landmark, pipeline.ticket_district(packets))
        if geo is None:
            raise HTTPException(status_code=422, detail="could not place that landmark with confidence")
        if not store.set_geo(ticket_no, geo):  # a fix or a mesh estimate arrived since the reads above
            raise HTTPException(status_code=409, detail="ticket has a GPS fix or a mesh location estimate")
        row = ticket_or_404(ticket_no)
        return _ticket_json(row, store.ticket_packets(ticket_no), store.get_dispatch_record(ticket_no))

    @app.post("/v1/tickets/{ticket_no}/approve", dependencies=operator)
    def approve(ticket_no: int, body: ApproveRequest | None = None) -> dict[str, Any]:
        body = body or ApproveRequest()
        record = store.get_dispatch_record(ticket_no) or {}
        proposal = record.get("proposal")
        team_id = body.team_id or (proposal or {}).get("team_id")
        eta: int | None = body.eta_min
        dispatch: dict[str, Any] | None = None
        capacity: int | None = None
        if team_id:
            team = next((t for t in team_views() if t["id"] == team_id), None)
            if team is None:
                raise HTTPException(status_code=422, detail=f"unknown team {team_id!r}")
            if team["status"] == "off":
                raise HTTPException(status_code=409, detail=f"team {team_id!r} is off duty")
            capacity = team["capacity"]
            same_team = proposal is not None and proposal["team_id"] == team_id
            if eta is None and same_team:
                eta = proposal["eta_min"]
            if eta is None:  # operator picked a team: same ETA rule as the allocator
                row = ticket_or_404(ticket_no)
                point = dispatching.ticket_point(_ticket_json(row, store.ticket_packets(ticket_no), record))
                eta = dispatching.eta_minutes(team, point)
            order_en = body.order_en or (proposal["order_en"] if same_team else None)
            dispatch = {"team_id": team_id, "team_name": team["name"], "eta_min": eta, "approved_at": int(clock())}
            if order_en:
                dispatch["order_en"] = order_en
        elif eta is not None or body.order_en:
            dispatch = {"team_id": None, "team_name": None, "eta_min": eta, "approved_at": int(clock())}
            if body.order_en:
                dispatch["order_en"] = body.order_en

        def make_ack(p: dict[str, Any]):
            lang = LANG_CODES[p["lang"]]
            if eta is None:  # plain approval: the stock templated ACK
                return build_ack(
                    ref_msg_id=p["msg_id"], target_node=p["origin_node"], ticket_no=ticket_no,
                    status=STATUS_DISPATCHED, lang=lang,
                )
            return dispatching.build_dispatch_ack(
                ref_msg_id=p["msg_id"], target_node=p["origin_node"], ticket_no=ticket_no, lang=lang, eta_min=eta
            )

        # atomic: only the first approval transitions and queues the DISPATCHED acks
        try:
            done = store.approve_ticket(ticket_no, int(clock()), make_ack, dispatch=dispatch, capacity=capacity)
        except TeamAtCapacity:
            raise HTTPException(status_code=409, detail=f"team {team_id!r} is at capacity") from None
        if done is None:
            raise HTTPException(status_code=404, detail="ticket not found")
        row = ticket_or_404(ticket_no)
        return _ticket_json(row, store.ticket_packets(ticket_no), store.get_dispatch_record(ticket_no))

    def advance(
        ticket_no: int, status: str, ack_status: int, eta: int | None, patch: dict[str, Any]
    ) -> dict[str, Any]:
        def make_ack(p: dict[str, Any]):
            return dispatching.build_status_ack(
                ref_msg_id=p["msg_id"], target_node=p["origin_node"], ticket_no=ticket_no,
                lang=LANG_CODES[p["lang"]], status=ack_status, eta_min=eta,
            )

        # atomic and idempotent: only the first call transitions and queues the acks
        result = store.advance_ticket(ticket_no, status, int(clock()), make_ack, patch=patch)
        if result is None:
            raise HTTPException(status_code=404, detail="ticket not found")
        if result == "conflict":
            cur = ticket_or_404(ticket_no)["status"]
            raise HTTPException(status_code=409, detail=f"cannot mark a {cur} ticket {status}")
        row = ticket_or_404(ticket_no)
        return _ticket_json(row, store.ticket_packets(ticket_no), store.get_dispatch_record(ticket_no))

    @app.post("/v1/tickets/{ticket_no}/on-the-way", dependencies=operator)
    def on_the_way(ticket_no: int, body: OnTheWayRequest | None = None) -> dict[str, Any]:
        """DISPATCHED -> ON_THE_WAY; queues an ON_THE_WAY ack (with the ETA if given) per reporter."""
        eta = body.eta_min if body else None
        patch: dict[str, Any] = {"on_the_way_at": int(clock())}
        if eta is not None:
            patch["eta_min"] = eta
        return advance(ticket_no, "ON_THE_WAY", STATUS_ON_THE_WAY, eta, patch)

    @app.post("/v1/tickets/{ticket_no}/resolve", dependencies=operator)
    def resolve(ticket_no: int) -> dict[str, Any]:
        """DISPATCHED / ON_THE_WAY -> RESOLVED; queues a RESOLVED ack per reporter and frees the team."""
        return advance(ticket_no, "RESOLVED", STATUS_RESOLVED, None, {"resolved_at": int(clock())})

    # ------------------------------------------------------------------ teams + dispatch
    @app.get("/v1/teams", dependencies=operator)
    def list_teams() -> list[dict[str, Any]]:
        return team_views()

    @app.post("/v1/dispatch/propose", dependencies=operator)
    def propose(ticket_no: int) -> dict[str, Any]:
        """Draft a team, ETA and order for one OPEN ticket (``?ticket_no=N``, required: the dashboard asks when
        an operator opens a ticket). Stored on the ticket as a pending proposal, replacing the earlier one;
        nothing is sent to the mesh until an operator approves. The allocation runs over every open ticket, so
        team room and priority count as they would for the whole queue, but no other ticket's proposal is
        written or cleared and only this ticket's order text is drafted. 404 unknown, 409 not open."""
        views = team_views()
        records = store.dispatch_records()
        tickets = [_ticket_json(r, links, records.get(r["ticket_no"])) for r, links in store.list_tickets_with_links()]
        target = next((t for t in tickets if t["ticket_no"] == ticket_no), None)
        if target is None:
            raise HTTPException(status_code=404, detail="unknown ticket")
        if target["status"] != "OPEN":
            raise HTTPException(status_code=409, detail="ticket is not open")
        assignments = dispatching.allocate([t for t in tickets if t["status"] == "OPEN"], views)
        assignments = [a for a in assignments if a.ticket_no == ticket_no]
        proposals = dispatching.build_proposals(assignments, [target], textgen, int(clock()))
        if proposals:
            store.set_proposals({ticket_no: {k: v for k, v in proposals[0].items() if k != "ticket_no"}})
        elif target.get("proposal"):
            store.set_proposals({ticket_no: None})  # stale: no team has room now
        return {"proposals": proposals}

    # ------------------------------------------------------------------ sitreps
    @app.post("/v1/sitreps/run", dependencies=operator)
    def run_sitrep() -> dict[str, Any]:
        return sitreps.run(store, sitrep_textgen, clock())

    @app.get("/v1/sitreps/latest", dependencies=operator)
    def latest_sitrep() -> dict[str, Any]:
        latest = store.latest_sitrep()
        if latest is None:
            raise HTTPException(status_code=404, detail="no sitrep yet")
        return latest

    @app.get("/v1/gateways", dependencies=operator)
    def list_gateways() -> dict[str, Any]:
        """Every gateway phone that has synced: when it was last seen and whether that counts as online."""
        now = clock()
        return {
            "gateways": [
                {**g, "online": g["last_seen"] >= now - GATEWAY_ONLINE_WINDOW_S} for g in store.list_gateways()
            ],
            "online_window_s": GATEWAY_ONLINE_WINDOW_S,
        }

    @app.get("/v1/stats", dependencies=operator)
    def stats() -> dict[str, Any]:
        s = store.stats()
        c = store.ticket_counts()
        views = team_views()
        return {
            "open_by_category": s["open_by_category"],
            "gateways_online": store.gateways_online(clock(), GATEWAY_ONLINE_WINDOW_S),
            "tickets": s["tickets"],
            "open": c["open"],
            "p1_open": c["p1_open"],
            "oldest_open_at": c["oldest_open_at"],
            "teams_free": sum(1 for t in views if t["status"] == "free"),
            "teams_total": len(views),
        }

    # ------------------------------------------------------------------ control-room broadcasts (SPEC 5.6)
    @app.post("/v1/broadcasts", dependencies=operator)
    def declare(body: BroadcastRequest) -> dict[str, Any]:
        has_area = body.radius_km > 0
        if has_area and (body.lat is None or body.lon is None):
            raise HTTPException(status_code=422, detail="radius_km needs lat and lon")
        now = int(clock())
        b = Broadcast(
            bcast_id=os.urandom(8), kind=body.kind, disaster=body.disaster, issued_at=now,
            lat_e5=to_e5(body.lat) if has_area and body.lat is not None else 0,
            lon_e5=to_e5(body.lon) if has_area and body.lon is not None else 0,
            radius_km=body.radius_km if has_area else 0,
        )
        store.add_broadcast(b.bcast_id.hex(), b.issued_at, encode_broadcast(b))
        return _broadcast_json(b)

    @app.get("/v1/broadcasts", dependencies=operator)
    def live() -> dict[str, Any]:
        from .codec import decode_broadcast

        return {"broadcasts": [_broadcast_json(decode_broadcast(p)) for p in store.live_broadcasts(int(clock()) - BROADCAST_TTL_S)]}

    # ------------------------------------------------------------------ dev only
    if env != "prod":

        @app.post("/v1/dev/inject")
        def inject(body: InjectRequest) -> dict[str, Any]:
            now = clock()
            items: list[IncomingNeed] = []
            msg_ids: list[str] = []
            for nj in body.needs:
                msg_id = os.urandom(8)
                origin = bytes.fromhex(nj.origin_node) if nj.origin_node else os.urandom(8)
                flags = 0
                for f in nj.vuln_flags:
                    flags |= 1 << VULN_NAMES.index(f)
                need = Need.make(
                    msg_id=msg_id, origin_node=origin, created_at=nj.created_at if nj.created_at is not None else int(now),
                    category=CATEGORY_NAMES.index(nj.category), urgency=nj.urgency, people=nj.people,
                    vuln_flags=flags, lat=nj.lat, lon=nj.lon, loc_acc=nj.loc_acc,
                    lang=LANG_CODES.index(nj.lang), item=nj.item, landmark=nj.landmark, summary=nj.summary,
                    answers=answers_from_json(nj.answers),
                )
                frame = encode_frame(TYPE_NEED, INITIAL_TTL, int(now * 1000), origin, encode_need(need))
                # round-trip through the decoder so injected needs see exactly what the wire would carry
                items.append(IncomingNeed(frame, decode_need(decode_frame(frame).payload), INITIAL_TTL))
                msg_ids.append(msg_id.hex())
            res = pipeline.process_batch(DEV_GATEWAY, items)
            hint_count = 0
            for nj, msg_hex in zip(body.needs, msg_ids):
                if nj.loc_acc != 0 or not nj.mesh_hints:
                    continue
                hints = []
                for k, mh in enumerate(nj.mesh_hints):
                    bucket = next(b for b, bound in HINT_ACC_MAX_M.items() if mh.accuracy_m <= bound)
                    relay = hashlib.sha256(bytes.fromhex(msg_hex) + bytes([k])).digest()[:8]  # a stable fake phone each
                    hint = LocHint(
                        bytes.fromhex(msg_hex), relay, to_e5(mh.lat), to_e5(mh.lon), bucket,
                        mh.rssi_dbm if mh.rssi_dbm is not None else RSSI_UNKNOWN, int(now),
                    )
                    hints.append(decode_loc_hint(encode_loc_hint(hint)))  # exactly what the wire would carry
                hint_count += pipeline.process_hints(DEV_GATEWAY, hints).accepted
            return {
                "accepted": res.accepted,
                "duplicates": res.duplicates,
                "msg_ids": msg_ids,
                "ticket_nos": [res.packet_tickets[m] for m in msg_ids if m in res.packet_tickets],
                **({"hints": hint_count} if hint_count else {}),
            }

    # ------------------------------------------------------------------ static dashboard
    # Last on purpose: routes are matched in order, so every API route above wins.
    if static_path and Path(static_path).is_dir():
        app.mount("/", SpaStaticFiles(directory=static_path, html=True), name="dashboard")

    return app


app = create_app()
