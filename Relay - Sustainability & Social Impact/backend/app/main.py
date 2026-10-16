"""Relay backend HTTP API (protocol/SPEC.md section 7)."""

from __future__ import annotations

import base64
import hashlib
import hmac
import logging
import os
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

from .agent import Agent, IncomingNeed, TriagePipeline, agent_from_env, build_ack
from .codec import (
    CATEGORY_NAMES,
    INITIAL_TTL,
    LANG_CODES,
    STATUS_DISPATCHED,
    TYPE_NEED,
    VULN_NAMES,
    CodecError,
    Need,
    decode_frame,
    decode_need,
    encode_frame,
    encode_need,
)
from .location import add_no_fix_note, scrub_legacy_draft, ticket_location
from .priority import vuln_names
from .pubsub import (
    Publisher,
    PubSubPublisher,
    TokenVerifier,
    build_messages,
    google_token_verifier,
    parse_push_envelope,
)
from .store import Store, open_store

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

    @field_validator("lang")
    @classmethod
    def _lang(cls, v: str) -> str:
        v = v.strip().lower()
        if v not in LANG_CODES:
            raise ValueError(f"lang must be one of {list(LANG_CODES)}")
        return v


class InjectRequest(BaseModel):
    needs: list[NeedJson] = Field(max_length=200)


# --------------------------------------------------------------------------------------
# serialisation
# --------------------------------------------------------------------------------------

def _ticket_json(row: dict[str, Any], links: list[dict[str, Any]]) -> dict[str, Any]:
    gateways: list[str] = []
    for lk in links:
        if lk["gateway_node"] not in gateways:
            gateways.append(lk["gateway_node"])
    loc = ticket_location(row, links)
    draft_en, draft_local, reason = row["dispatch_draft_en"], row["dispatch_draft_local"], row["priority_reason"]
    if not loc["has_location"]:  # rows triaged before no-fix handling carry "0.00000, 0.00000" in their text
        draft_en, draft_local = (scrub_legacy_draft(d, row["landmark"]) for d in (draft_en, draft_local))
        reason = add_no_fix_note(reason, row["landmark"])
    return {
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


def _packet_json(p: dict[str, Any]) -> dict[str, Any]:
    return {
        "msg_id": p["msg_id"],
        "origin_node": p["origin_node"],
        "gateway_node": p["gateway_node"],
        "hops": p["hops"],
        "loc_acc": p["loc_acc"],
        "received_at": p["received_at"],
        "summary": p["summary"],
    }


def decode_frames(b64_frames: list[str]) -> tuple[list[IncomingNeed], int]:
    """Validate uploaded frames. Returns (decoded NEEDs, number rejected)."""
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
    env: str | None = None,
    enrich_budget_s: float | None = None,
    pubsub_topic: str | None = None,
    publisher: Publisher | None = None,
    token_verifier: TokenVerifier | None = None,
    pubsub_audience: str | None = None,
    invoker_sa: str | None = None,
    static_dir: str | None = None,
    triage_budget_s: float | None = None,
) -> FastAPI:
    """Every keyword defaults to its environment variable (see README); the keywords exist so
    tests can inject fakes. ``pubsub_topic=""`` forces in-process triage."""
    clock = clock or time.time
    device_key = device_key if device_key is not None else os.environ.get("RELAY_DEVICE_KEY", "dev-relay-key")
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

    store = store if store is not None else open_store(db_path=db_path)
    pipeline = TriagePipeline(
        store,
        agent if agent is not None else agent_from_env(),
        clock=clock,
        enrich_budget_s=enrich_budget_s if enrich_budget_s is not None else float(os.environ.get("RELAY_AGENT_BUDGET_S", "20")),
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
        items, rejected = decode_frames(req.frames)

        if topic and publisher is not None:
            accepted, duplicates = publish_batch(req.gateway_node, gateway, items)
        else:
            res = pipeline.process_batch(gateway, items)
            accepted, duplicates = res.accepted, res.duplicates

        cursor = store.clamp_ack_cursor(req.ack_cursor)  # beyond the newest ack = store was reset
        acks = store.acks_after(cursor, MAX_ACKS_PER_SYNC)
        return {
            "accepted": accepted,
            "duplicates": duplicates,
            "rejected": rejected,
            "acks": [base64.b64encode(a["payload"]).decode("ascii") for a in acks],
            "ack_cursor": acks[-1]["id"] if acks else cursor,
        }

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
    @app.get("/v1/tickets")
    def list_tickets() -> dict[str, Any]:
        return {"tickets": [_ticket_json(r, links) for r, links in store.list_tickets_with_links()]}

    @app.get("/v1/tickets/{ticket_no}")
    def get_ticket(ticket_no: int) -> dict[str, Any]:
        row = ticket_or_404(ticket_no)
        packets = store.ticket_packets(ticket_no)
        out = _ticket_json(row, packets)
        out["packets"] = [_packet_json(p) for p in packets]
        return out

    @app.post("/v1/tickets/{ticket_no}/approve")
    def approve(ticket_no: int) -> dict[str, Any]:
        # atomic: only the first approval transitions and queues the DISPATCHED acks
        done = store.approve_ticket(
            ticket_no, int(clock()),
            lambda p: build_ack(
                ref_msg_id=p["msg_id"], target_node=p["origin_node"], ticket_no=ticket_no,
                status=STATUS_DISPATCHED, lang=LANG_CODES[p["lang"]],
            ),
        )
        if done is None:
            raise HTTPException(status_code=404, detail="ticket not found")
        row = ticket_or_404(ticket_no)
        return _ticket_json(row, store.ticket_packets(ticket_no))

    @app.get("/v1/stats")
    def stats() -> dict[str, Any]:
        s = store.stats()
        s["gateways_online"] = store.gateways_online(clock(), GATEWAY_ONLINE_WINDOW_S)
        return {
            "open_by_category": s["open_by_category"],
            "gateways_online": s["gateways_online"],
            "tickets": s["tickets"],
        }

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
                )
                frame = encode_frame(TYPE_NEED, INITIAL_TTL, int(now * 1000), origin, encode_need(need))
                # round-trip through the decoder so injected needs see exactly what the wire would carry
                items.append(IncomingNeed(frame, decode_need(decode_frame(frame).payload), INITIAL_TTL))
                msg_ids.append(msg_id.hex())
            res = pipeline.process_batch(DEV_GATEWAY, items)
            return {
                "accepted": res.accepted,
                "duplicates": res.duplicates,
                "msg_ids": msg_ids,
                "ticket_nos": [res.packet_tickets[m] for m in msg_ids if m in res.packet_tickets],
            }

    # ------------------------------------------------------------------ static dashboard
    # Last on purpose: routes are matched in order, so every API route above wins.
    if static_path and Path(static_path).is_dir():
        app.mount("/", SpaStaticFiles(directory=static_path, html=True), name="dashboard")

    return app


app = create_app()
