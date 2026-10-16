"""HTTP API.

Gateway phones:   POST /v1/sync                       (X-Relay-Key)
Dashboard:        GET  /v1/tickets, /v1/tickets/{id}  (X-Operator-Key, or ?key= for EventSource)
                  GET  /v1/changes?since=REV, /v1/stream (server-sent events)
                  POST /v1/tickets/{id}/approve | /on-the-way | /resolve
Development:      POST /v1/dev/inject                 (only when RELAY_DEV_MODE=1)
"""

from __future__ import annotations

import asyncio
import base64
import binascii
import json
import secrets
import time
from typing import Callable

from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from . import priority
from .agent import Triage
from .config import Settings
from .llm import LLM, make_llm
from .models import Ticket
from .protocol import (
    AckStatus,
    Category,
    DecodeError,
    Flag,
    Lang,
    NeedPacket,
    PacketType,
    decode_need,
    encode_need,
    new_msg_id,
    packet_type,
)
from .store import Store

MAX_PACKET_B64 = 512
ACK_BATCH = 200


class SyncPacketIn(BaseModel):
    data: str = Field(max_length=MAX_PACKET_B64, description="Base64 Relay payload (NEED)")
    hops: int | None = Field(None, ge=0, le=32, description="Hops travelled, from the transport TTL")


class SyncIn(BaseModel):
    gateway_node: str = Field(pattern=r"^[0-9a-fA-F]{1,8}$")
    packets: list[SyncPacketIn] = Field(default_factory=list, max_length=500)
    ack_cursor: int | None = Field(None, ge=0)


class Rejection(BaseModel):
    index: int
    reason: str


class SyncOut(BaseModel):
    created: int = 0
    merged: int = 0
    duplicates: int = 0
    ignored: int = 0
    rejected: list[Rejection] = Field(default_factory=list)
    acks: list[str] = Field(default_factory=list, description="Base64 ACK payloads to inject into the mesh")
    ack_cursor: int
    more_acks: bool = False


class StatusIn(BaseModel):
    eta_min: int | None = Field(None, ge=0, le=0xFFFF)
    note: str = Field("", max_length=200)


class NeedIn(BaseModel):
    category: str = "MEDICAL"
    urgency: int = Field(2, ge=0, le=3)
    people: int = Field(1, ge=0, le=255)
    flags: list[str] = Field(default_factory=list)
    lat: float | None = Field(None, ge=-90, le=90)
    lon: float | None = Field(None, ge=-180, le=180)
    loc_acc_m: int | None = Field(None, ge=1, le=1275)
    lang: str = "en"
    item: str = ""
    landmark: str = ""
    summary: str = ""
    origin_node: str | None = Field(None, pattern=r"^[0-9a-fA-F]{1,8}$")
    hops: int | None = Field(2, ge=0, le=32)


def ticket_view(ticket: Ticket, now: float) -> dict:
    view = ticket.model_dump()
    view.update(
        tier=priority.final_tier(ticket, now),
        score=priority.score(ticket, now),
        waiting_h=round(priority.waiting_hours(ticket, now), 2),
        report_count=ticket.report_count,
        max_hops=ticket.max_hops,
    )
    return view


def create_app(
    settings: Settings | None = None,
    store: Store | None = None,
    llm: LLM | None = None,
    clock: Callable[[], float] = time.time,
) -> FastAPI:
    settings = settings or Settings.from_env()
    store = store or Store(settings.db_path)
    llm = llm or make_llm(settings)
    triage = Triage(store, llm, settings, clock)

    app = FastAPI(title="Relay control-room backend", version="0.1.0")
    app.state.settings = settings
    app.state.store = store
    app.state.triage = triage
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[o.strip() for o in settings.cors_origins.split(",") if o.strip()],
        allow_methods=["GET", "POST"],
        allow_headers=["Content-Type", "X-Operator-Key", "X-Relay-Key"],
    )

    def _check(expected: str | None, given: str | None) -> None:
        if expected and not secrets.compare_digest((given or "").encode(), expected.encode()):
            raise HTTPException(status_code=401, detail="invalid or missing key")

    def require_device(x_relay_key: str | None = Header(None)) -> None:
        _check(settings.device_key, x_relay_key)

    def require_operator(
        x_operator_key: str | None = Header(None), key: str | None = Query(None, include_in_schema=False)
    ) -> None:
        _check(settings.operator_key, x_operator_key or key)

    def schedule_enrichment(background: BackgroundTasks, ticket_ids: set[int]) -> None:
        if llm.name == "none":
            return
        for ticket_id in sorted(ticket_ids):
            background.add_task(triage.enrich, ticket_id)

    @app.get("/healthz")
    def healthz() -> dict:
        return {"ok": True, "llm": llm.name, "packets": store.packet_count(), "rev": store.current_rev()}

    @app.post("/v1/sync", response_model=SyncOut, dependencies=[Depends(require_device)])
    def sync(body: SyncIn, background: BackgroundTasks) -> SyncOut:
        gateway = body.gateway_node.lower().rjust(8, "0")
        out = SyncOut(ack_cursor=body.ack_cursor or 0)
        touched: set[int] = set()
        for index, item in enumerate(body.packets):
            try:
                raw = base64.b64decode(item.data, validate=True)
            except (binascii.Error, ValueError):
                out.rejected.append(Rejection(index=index, reason="invalid base64"))
                continue
            kind = packet_type(raw)
            if kind is PacketType.ACK:
                # Gateways relay everything they hear; ACKs flowing back are not for us.
                out.ignored += 1
                continue
            try:
                need = decode_need(raw)
            except DecodeError as exc:
                out.rejected.append(Rejection(index=index, reason=str(exc)))
                continue
            result = triage.ingest(need, raw, gateway, item.hops)
            if result.outcome == "duplicate":
                out.duplicates += 1
                continue
            setattr(out, result.outcome, getattr(out, result.outcome) + 1)
            touched.add(result.ticket_id)
        schedule_enrichment(background, touched)

        not_before = clock() - settings.ack_lookback_h * 3600
        acks = store.acks_after(body.ack_cursor, not_before, ACK_BATCH)
        out.acks = [base64.b64encode(a.raw).decode() for a in acks]
        if acks:
            out.ack_cursor = acks[-1].seq
        elif body.ack_cursor is None:
            out.ack_cursor = store.last_ack_seq()
        out.more_acks = len(acks) == ACK_BATCH
        return out

    @app.get("/v1/tickets", dependencies=[Depends(require_operator)])
    def list_tickets(status: str = Query("active", pattern="^(active|all|OPEN|DISPATCHED|RESOLVED)$")) -> dict:
        statuses = {"active": ("OPEN", "DISPATCHED"), "all": None}.get(status, (status,))
        now = clock()
        tickets = sorted(store.list_tickets(statuses), key=lambda t: priority.sort_key(t, now))
        return {"rev": store.current_rev(), "tickets": [ticket_view(t, now) for t in tickets]}

    @app.get("/v1/tickets/{ticket_id}", dependencies=[Depends(require_operator)])
    def get_ticket(ticket_id: int) -> dict:
        ticket = store.get_ticket(ticket_id)
        if ticket is None:
            raise HTTPException(status_code=404, detail="ticket not found")
        return ticket_view(ticket, clock())

    def _operator_action(ticket_id: int, status: AckStatus, body: StatusIn) -> dict:
        try:
            ticket, acks = triage.set_status(ticket_id, status, body.eta_min, body.note)
        except KeyError:
            raise HTTPException(status_code=404, detail="ticket not found") from None
        return {"ticket": ticket_view(ticket, clock()), "acks_queued": acks}

    @app.post("/v1/tickets/{ticket_id}/approve", dependencies=[Depends(require_operator)])
    def approve(ticket_id: int, body: StatusIn | None = None) -> dict:
        return _operator_action(ticket_id, AckStatus.DISPATCHED, body or StatusIn())

    @app.post("/v1/tickets/{ticket_id}/on-the-way", dependencies=[Depends(require_operator)])
    def on_the_way(ticket_id: int, body: StatusIn | None = None) -> dict:
        return _operator_action(ticket_id, AckStatus.ON_THE_WAY, body or StatusIn())

    @app.post("/v1/tickets/{ticket_id}/resolve", dependencies=[Depends(require_operator)])
    def resolve(ticket_id: int, body: StatusIn | None = None) -> dict:
        return _operator_action(ticket_id, AckStatus.RESOLVED, body or StatusIn())

    def _changes(since: int) -> dict:
        tickets = store.tickets_changed_since(since)
        now = clock()
        rev = max([since, *(t.rev for t in tickets)])
        return {"rev": rev, "tickets": [ticket_view(t, now) for t in tickets]}

    @app.get("/v1/changes", dependencies=[Depends(require_operator)])
    def changes(since: int = Query(0, ge=0)) -> dict:
        return _changes(since)

    @app.get("/v1/stream", dependencies=[Depends(require_operator)])
    async def stream(request: Request, since: int = Query(0, ge=0)) -> StreamingResponse:
        async def events():
            rev = since
            idle = 0.0
            while not await request.is_disconnected():
                batch = await run_in_threadpool(_changes, rev)
                if batch["tickets"]:
                    rev = batch["rev"]
                    idle = 0.0
                    yield f"event: tickets\ndata: {json.dumps(batch)}\n\n"
                elif idle >= 15:
                    idle = 0.0
                    yield ": ping\n\n"
                await asyncio.sleep(1)
                idle += 1

        return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache"})

    if settings.dev_mode:

        @app.post("/v1/dev/inject", dependencies=[Depends(require_device)])
        def dev_inject(body: NeedIn, background: BackgroundTasks) -> dict:
            """Inject a NEED as if a gateway had uploaded it, for building the dashboard without phones."""
            if body.category.upper() not in Category.__members__:
                raise HTTPException(status_code=422, detail=f"unknown category {body.category}")
            unknown = [f for f in body.flags if f.upper() not in Flag.__members__]
            if unknown:
                raise HTTPException(status_code=422, detail=f"unknown flags {unknown}")
            if (body.lat is None) != (body.lon is None):
                raise HTTPException(status_code=422, detail="give both lat and lon, or neither")
            flags = Flag(0)
            for name in body.flags:
                flags |= Flag[name.upper()]
            need = NeedPacket(
                msg_id=new_msg_id(),
                origin_node=int(body.origin_node, 16) if body.origin_node else secrets.randbits(32),
                created_at=int(clock()),
                category=Category[body.category.upper()],
                urgency=body.urgency,
                people=body.people,
                flags=flags,
                lat=body.lat,
                lon=body.lon,
                loc_acc_m=body.loc_acc_m,
                lang=Lang.from_code(body.lang),
                item=body.item.lower(),
                landmark=body.landmark,
                summary=body.summary,
            )
            raw = encode_need(need)
            result = triage.ingest(decode_need(raw), raw, "00000000", body.hops)
            if result.ticket_id is not None:
                schedule_enrichment(background, {result.ticket_id})
            return {
                "outcome": result.outcome,
                "ticket_id": result.ticket_id,
                "msg_id": need.msg_id_hex,
                "packet_bytes": len(raw),
                "packet_b64": base64.b64encode(raw).decode(),
            }

    return app
