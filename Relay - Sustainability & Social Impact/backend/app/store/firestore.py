"""Firestore implementation of the ``Store`` interface.

Layout (all top-level collections in the configured database):

  packets/{msg_id}      NEED packet; ``ticket_no`` + ``link_ord`` are set when it is triaged
  tickets/{ticket_no}   ticket; ``links`` = [{msg_id, gateway_node, loc_acc}] (denormalised so the
                        dashboard list needs one read per ticket, not one per packet);
                        ``open_category`` = category while OPEN, null once DISPATCHED (and after: ON_THE_WAY, RESOLVED)
  loc_hints/{hint_key}  LOC_HINT (create-if-absent); ``ref_msg_id`` (automatic single-field index) finds a NEED's hints
  acks/{seq}            ACK; ``seq`` is the gateway sync cursor
  ack_keys/{ref}_{st}   marker doc: at most one ack per (ref_msg_id, status)
  gateways/{node}       last_seen
  teams/{id}            dispatch team (seeded once, create-if-absent)
  dispatch/{ticket_no}  ``proposal`` / ``dispatch`` maps (or null); ``team_id`` and ``active`` (counts
                        toward the team's load) are set by approve
  sitreps/{id}          generated sitreps; ``id`` comes from counters/sitrep
  broadcasts/{id}       broadcast drafts and approvals; ``id`` comes from counters/broadcast
  counters/{name}       ``ticket_no`` and ``ack_seq``: ``value`` is the last number handed out

Atomicity: every multi-document write is one Firestore transaction (reads first, then
writes). Duplicates under concurrent Pub/Sub redelivery are impossible because the packet
doc is read inside the same transaction as everything it triggers: a second delivery either
serialises behind the first and sees ``ticket_no`` set, or aborts and retries.

Indexes: only automatic single-field indexes are used. The merge lookup queries
``open_category == X`` (one equality) and filters time window and item in memory; the ack
feed is ``seq > cursor`` ordered by ``seq`` (same field). Sorting of the ticket list is in memory.

The client is created lazily, so importing the app (and ``/healthz``) never touches
Firestore or credentials.
"""

from __future__ import annotations

import concurrent.futures
import logging
import os
import threading
from typing import Any, Callable

from ..codec import CATEGORY_NAMES
from .base import (
    ADVANCE_FROM,
    TICKET_COLUMNS,
    TICKET_DEFAULTS,
    AckSpec,
    AdvanceResult,
    HintIn,
    IngestOutcome,
    Lookup,
    PacketIn,
    Plan,
    RecordStatus,
    TeamAtCapacity,
    keep_mesh_geo,
    merge_dispatch,
)

log = logging.getLogger("relay.store.firestore")

TXN_ATTEMPTS = 10
RECORD_WORKERS = 8
IN_PROCESS_TICKET_MIN = 100  # first ticket number is 101


def _packet_doc(p: PacketIn, gateway_node: str, received_at: int) -> dict[str, Any]:
    n = p.need
    return {
        "msg_id": n.msg_id.hex(), "frame": p.frame, "origin_node": n.origin_node.hex(),
        "created_at": n.created_at, "reported_at": p.reported_at, "category": n.category,
        "urgency": n.urgency, "people": n.people, "vuln_flags": n.vuln_flags, "lat_e5": n.lat_e5,
        "lon_e5": n.lon_e5, "loc_acc": n.loc_acc, "lang": n.lang, "item": n.item,
        "landmark": n.landmark, "summary": n.summary, "gateway_node": gateway_node,
        "received_at": received_at, "hops": p.hops,
    }


def _ack_doc(a: AckSpec, seq: int, now: int) -> dict[str, Any]:
    return {
        "seq": seq, "ack_id": a.ack_id, "payload": a.payload, "target_node": a.target_node,
        "ref_msg_id": a.ref_msg_id, "ticket_no": a.ticket_no, "status": a.status, "created_at": now,
    }


class FirestoreStore:
    def __init__(
        self, project: str | None = None, database: str | None = None, *, client: Any = None,
    ):
        self._project = project if project is not None else os.environ.get("GOOGLE_CLOUD_PROJECT")
        self._database = database if database is not None else (os.environ.get("RELAY_FIRESTORE_DB") or None)
        self._client = client
        self._client_lock = threading.Lock()
        # In-process serialisation of the multi-document transactions. Cross-instance safety
        # comes from Firestore itself; this only avoids self-inflicted aborts on the counters.
        self._write_lock = threading.Lock()
        self._loc_acc_cache: dict[str, int] = {}  # msg_id -> loc_acc, for legacy ticket links

    # ------------------------------------------------------------------ plumbing
    @property
    def db(self):
        if self._client is None:
            with self._client_lock:
                if self._client is None:
                    from google.cloud import firestore

                    self._client = firestore.Client(project=self._project, database=self._database)
        return self._client

    def _col(self, name: str):
        return self.db.collection(name)

    def close(self) -> None:
        with self._client_lock:
            if self._client is not None:
                self._client.close()
                self._client = None

    @staticmethod
    def _eq(query, field: str, op: str, value: Any):
        from google.cloud.firestore_v1.base_query import FieldFilter

        return query.where(filter=FieldFilter(field, op, value))

    def _count(self, query) -> int:
        """Server-side COUNT (billed per 1000 index entries, not per document)."""
        result = query.count().get()
        return int(result[0][0].value)

    @staticmethod
    def _ticket_row(snap) -> dict[str, Any]:
        d = snap.to_dict()
        row = {k: d[k] for k in TICKET_COLUMNS if k in d}
        row["ticket_no"] = int(d["ticket_no"])
        return row

    @staticmethod
    def _triaged(packet_snap) -> bool:
        """A packet doc without ``ticket_no`` was recorded by /v1/sync but not triaged yet."""
        return packet_snap.exists and (packet_snap.to_dict() or {}).get("ticket_no") is not None

    def _next(self, txn, counter_name: str, floor: int = 0) -> tuple[Any, int]:
        ref = self._col("counters").document(counter_name)
        snap = ref.get(transaction=txn)
        return ref, (int(snap.get("value")) if snap.exists else floor) + 1

    # ------------------------------------------------------------------ gateways
    def touch_gateway(self, node: str, now: float) -> None:
        self._col("gateways").document(node).set({"last_seen": now})

    def gateways_online(self, now: float, window_s: float = 60.0) -> int:
        return self._count(self._eq(self._col("gateways"), "last_seen", ">=", now - window_s))

    def list_gateways(self) -> list[dict[str, Any]]:
        out = [{"node": d.id, "last_seen": d.to_dict()["last_seen"]} for d in self._col("gateways").stream()]
        out.sort(key=lambda g: (-g["last_seen"], g["node"]))
        return out

    # ------------------------------------------------------------------ packets
    def record_packets(self, packets: list[PacketIn], gateway_node: str, received_at: int) -> list[RecordStatus]:
        from google.api_core.exceptions import AlreadyExists

        def one(p: PacketIn) -> RecordStatus:
            ref = self._col("packets").document(p.need.msg_id.hex())
            try:
                ref.create(_packet_doc(p, gateway_node, received_at))  # create-if-absent
                return "new"
            except AlreadyExists:
                return "done" if self._triaged(ref.get()) else "pending"

        if len(packets) <= 1:
            return [one(p) for p in packets]
        with concurrent.futures.ThreadPoolExecutor(max_workers=RECORD_WORKERS) as pool:
            return list(pool.map(one, packets))

    def ingest(
        self, *, packet: PacketIn, gateway_node: str, received_at: int, lookup: Lookup | None,
        plan: Callable[[list[dict[str, Any]]], Plan],
    ) -> IngestOutcome:
        from google.cloud import firestore

        msg_hex = packet.need.msg_id.hex()
        pref = self._col("packets").document(msg_hex)

        @firestore.transactional
        def run(txn) -> IngestOutcome:
            # ---- reads
            psnap = pref.get(transaction=txn)
            if self._triaged(psnap):
                return IngestOutcome("duplicate")
            status = "resumed" if psnap.exists else "new"

            candidates: list[dict[str, Any]] = []
            if lookup:
                category, reported_at, window_s = lookup
                q = self._eq(self._col("tickets"), "open_category", "==", category)
                for s in q.stream(transaction=txn):
                    row = self._ticket_row(s)
                    if row["status"] == "OPEN" and abs(row["first_reported_at"] - reported_at) <= window_s:
                        candidates.append(row)
                candidates.sort(key=lambda r: r["ticket_no"])

            decision = plan(candidates)
            created = decision.ticket_no is None
            if created:
                counter_ref, ticket_no = self._next(txn, "ticket_no", IN_PROCESS_TICKET_MIN)
            else:
                ticket_no = decision.ticket_no
            ack = decision.make_ack(ticket_no)
            ack_counter_ref, seq = self._next(txn, "ack_seq")
            key_ref = self._col("ack_keys").document(f"{ack.ref_msg_id}_{ack.status}")
            key_exists = key_ref.get(transaction=txn).exists

            # ---- writes
            link = {"msg_id": msg_hex, "gateway_node": gateway_node, "loc_acc": packet.need.loc_acc}
            tref = self._col("tickets").document(str(ticket_no))
            if created:
                doc = {**TICKET_DEFAULTS, **decision.fields, "ticket_no": ticket_no, "links": [link],
                       "open_category": decision.fields["category"]}
                txn.set(tref, doc)
                txn.set(counter_ref, {"value": ticket_no})
            else:
                txn.update(tref, {**decision.fields, "links": firestore.ArrayUnion([link])})
            link_ord = int(decision.fields.get("report_count", 1))
            if psnap.exists:
                txn.update(pref, {"ticket_no": ticket_no, "link_ord": link_ord})
            else:
                txn.set(pref, {**_packet_doc(packet, gateway_node, received_at),
                               "ticket_no": ticket_no, "link_ord": link_ord})
            if not key_exists:
                txn.set(self._col("acks").document(str(seq)), _ack_doc(ack, seq, received_at))
                txn.set(ack_counter_ref, {"value": seq})
                txn.set(key_ref, {"seq": seq})
            return IngestOutcome(status, ticket_no, created)

        with self._write_lock:
            return run(self.db.transaction(max_attempts=TXN_ATTEMPTS))

    def _packets_of(self, ticket_no: int, txn=None) -> list[dict[str, Any]]:
        q = self._eq(self._col("packets"), "ticket_no", "==", ticket_no)
        docs = [s.to_dict() for s in q.stream(transaction=txn)]
        docs.sort(key=lambda d: (d.get("link_ord", 0), d["received_at"], d["msg_id"]))
        return docs

    def ticket_packets(self, ticket_no: int) -> list[dict[str, Any]]:
        return self._packets_of(ticket_no)

    def origins_for_ticket(self, ticket_no: int) -> list[dict[str, Any]]:
        return self._origins(self._packets_of(ticket_no))

    # ------------------------------------------------------------------ mesh location hints
    def add_hints(self, hints: list[HintIn]) -> list[bool]:
        from google.api_core.exceptions import AlreadyExists

        def one(h: HintIn) -> bool:
            doc = {
                "hint_key": h.key, "ref_msg_id": h.ref_msg_id, "relay_node": h.relay_node, "lat_e5": h.lat_e5,
                "lon_e5": h.lon_e5, "acc": h.acc, "rssi_dbm": h.rssi_dbm, "observed_at": h.observed_at,
                "gateway_node": h.gateway_node, "received_at": h.received_at,
            }
            try:
                self._col("loc_hints").document(h.key).create(doc)  # create-if-absent: the first hint wins
                return True
            except AlreadyExists:
                return False

        if len(hints) <= 1:
            return [one(h) for h in hints]
        with concurrent.futures.ThreadPoolExecutor(max_workers=RECORD_WORKERS) as pool:
            return list(pool.map(one, hints))

    def hints_for(self, msg_ids: list[str]) -> list[dict[str, Any]]:
        out: list[dict[str, Any]] = []
        for msg_id in msg_ids:
            for s in self._eq(self._col("loc_hints"), "ref_msg_id", "==", msg_id).stream():
                d = s.to_dict()
                out.append({**d, "key": d["hint_key"]})
        out.sort(key=lambda d: (d["received_at"], d["key"]))
        return out

    def ticket_for_msg(self, msg_id: str) -> int | None:
        snap = self._col("packets").document(msg_id).get()
        no = (snap.to_dict() or {}).get("ticket_no") if snap.exists else None
        return int(no) if no is not None else None

    def set_mesh_geo(self, ticket_no: int, geo: dict[str, Any]) -> bool:
        from google.cloud import firestore

        tref = self._col("tickets").document(str(ticket_no))

        @firestore.transactional
        def run(txn) -> bool:
            snap = tref.get(transaction=txn)
            if not snap.exists:
                return False
            # ``links`` are the ticket's packets, written in the same transaction that links them
            for lk in snap.get("links") or []:
                acc = lk.get("loc_acc")
                if acc is None:  # written before loc_acc was denormalised
                    p = self._col("packets").document(lk["msg_id"]).get(transaction=txn)
                    acc = (p.to_dict() or {}).get("loc_acc") if p.exists else None
                if acc != 0:
                    return False  # a GPS fix (or unknown quality): never shadow it
            txn.update(tref, {"geo": geo})
            return True

        return run(self.db.transaction(max_attempts=TXN_ATTEMPTS))

    @staticmethod
    def _origins(packets: list[dict[str, Any]]) -> list[dict[str, Any]]:
        seen: dict[str, dict[str, Any]] = {}
        for p in packets:
            seen.setdefault(p["origin_node"], p)
        return list(seen.values())

    # ------------------------------------------------------------------ tickets
    def get_ticket(self, ticket_no: int) -> dict[str, Any] | None:
        snap = self._col("tickets").document(str(ticket_no)).get()
        return self._ticket_row(snap) if snap.exists else None

    def list_tickets_with_links(self) -> list[tuple[dict[str, Any], list[dict[str, Any]]]]:
        out = [(self._ticket_row(s), [dict(lk) for lk in (s.get("links") or [])]) for s in self._col("tickets").stream()]
        self._fill_legacy_loc_acc(out)
        out.sort(key=lambda rl: (-rl[0]["priority_score"], rl[0]["first_reported_at"], rl[0]["ticket_no"]))
        return out

    def _fill_legacy_loc_acc(self, rows: list[tuple[dict[str, Any], list[dict[str, Any]]]]) -> None:
        """Links written before ``loc_acc`` was denormalised lack it: read it from the packet
        doc once per msg_id (it never changes) and remember it in-process."""
        missing: dict[str, list[dict[str, Any]]] = {}
        for _, links in rows:
            for lk in links:
                if lk.get("loc_acc") is None:
                    if lk["msg_id"] in self._loc_acc_cache:
                        lk["loc_acc"] = self._loc_acc_cache[lk["msg_id"]]
                    else:
                        missing.setdefault(lk["msg_id"], []).append(lk)
        if not missing:
            return
        for snap in self.db.get_all([self._col("packets").document(m) for m in missing]):
            acc = (snap.to_dict() or {}).get("loc_acc") if snap.exists else None
            if acc is not None:
                self._loc_acc_cache[snap.id] = int(acc)
                for lk in missing[snap.id]:
                    lk["loc_acc"] = int(acc)

    def update_ticket_if_unchanged(self, ticket_no: int, report_count: int, fields: dict[str, Any]) -> bool:
        from google.cloud import firestore

        assert set(fields) <= TICKET_COLUMNS, set(fields) - TICKET_COLUMNS
        tref = self._col("tickets").document(str(ticket_no))

        @firestore.transactional
        def run(txn) -> bool:
            snap = tref.get(transaction=txn)
            if not snap.exists or snap.get("status") != "OPEN" or snap.get("report_count") != report_count:
                return False
            fields_ = keep_mesh_geo((snap.to_dict() or {}).get("geo"), fields)
            if not fields_:
                return True
            # the ticket is OPEN here, so its merge-lookup category follows a reclassification
            txn.update(tref, {**fields_, "open_category": fields_["category"]} if "category" in fields_ else fields_)
            return True

        return run(self.db.transaction(max_attempts=TXN_ATTEMPTS))

    def approve_ticket(
        self, ticket_no: int, now: int, make_ack: Callable[[dict[str, Any]], AckSpec], *,
        dispatch: dict[str, Any] | None = None, capacity: int | None = None,
    ) -> bool | None:
        from google.cloud import firestore

        tref = self._col("tickets").document(str(ticket_no))
        dref = self._col("dispatch").document(str(ticket_no))
        team_id = dispatch.get("team_id") if dispatch else None

        @firestore.transactional
        def run(txn) -> bool | None:
            snap = tref.get(transaction=txn)
            if not snap.exists:
                return None
            if snap.get("status") != "OPEN":
                return False
            if team_id and capacity is not None:
                q = self._eq(self._col("dispatch"), "team_id", "==", team_id)
                if sum(1 for d in q.stream(transaction=txn) if d.get("active")) >= capacity:
                    raise TeamAtCapacity(team_id)
            acks = [make_ack(p) for p in self._origins(self._packets_of(ticket_no, txn))]
            ack_counter_ref, seq = self._next(txn, "ack_seq")
            keys = [(a, self._col("ack_keys").document(f"{a.ref_msg_id}_{a.status}")) for a in acks]
            fresh = [(a, k) for a, k in keys if not k.get(transaction=txn).exists]
            txn.update(tref, {"status": "DISPATCHED", "updated_at": now, "open_category": None})
            if dispatch is not None:
                txn.set(dref, {"ticket_no": ticket_no, "team_id": team_id, "active": True, "dispatch": dispatch},
                        merge=True)
            for a, k in fresh:
                txn.set(self._col("acks").document(str(seq)), _ack_doc(a, seq, now))
                txn.set(k, {"seq": seq})
                seq += 1
            if fresh:
                txn.set(ack_counter_ref, {"value": seq - 1})
            return True

        with self._write_lock:
            return run(self.db.transaction(max_attempts=TXN_ATTEMPTS))

    def advance_ticket(
        self, ticket_no: int, status: str, now: int, make_ack: Callable[[dict[str, Any]], AckSpec], *,
        patch: dict[str, Any] | None = None,
    ) -> AdvanceResult | None:
        from google.cloud import firestore

        tref = self._col("tickets").document(str(ticket_no))
        dref = self._col("dispatch").document(str(ticket_no))

        @firestore.transactional
        def run(txn) -> AdvanceResult | None:
            snap = tref.get(transaction=txn)
            if not snap.exists:
                return None
            if snap.get("status") == status:
                return "noop"
            if snap.get("status") not in ADVANCE_FROM[status]:
                return "conflict"
            dsnap = dref.get(transaction=txn)
            acks = [make_ack(p) for p in self._origins(self._packets_of(ticket_no, txn))]
            ack_counter_ref, seq = self._next(txn, "ack_seq")
            keys = [(a, self._col("ack_keys").document(f"{a.ref_msg_id}_{a.status}")) for a in acks]
            fresh = [(a, k) for a, k in keys if not k.get(transaction=txn).exists]
            txn.update(tref, {"status": status, "updated_at": now, "open_category": None})
            doc: dict[str, Any] = {"ticket_no": ticket_no}
            if patch:
                doc["dispatch"] = merge_dispatch((dsnap.to_dict() or {}).get("dispatch") if dsnap.exists else None, patch)
            if status == "RESOLVED":
                doc["active"] = False
            if len(doc) > 1:
                txn.set(dref, doc, merge=True)
            for a, k in fresh:
                txn.set(self._col("acks").document(str(seq)), _ack_doc(a, seq, now))
                txn.set(k, {"seq": seq})
                seq += 1
            if fresh:
                txn.set(ack_counter_ref, {"value": seq - 1})
            return "done"

        with self._write_lock:
            return run(self.db.transaction(max_attempts=TXN_ATTEMPTS))

    def stats(self) -> dict[str, Any]:
        tickets = self._col("tickets")
        open_by_cat = {
            name: self._count(self._eq(tickets, "open_category", "==", name)) for name in CATEGORY_NAMES
        }
        return {"open_by_category": open_by_cat, "tickets": self._count(tickets)}

    def ticket_counts(self) -> dict[str, Any]:
        tickets = self._col("tickets")
        open_rows = [self._ticket_row(s) for s in self._eq(tickets, "status", "==", "OPEN").stream()]
        open_rows.sort(key=lambda r: (-r["priority_score"], r["first_reported_at"], r["ticket_no"]))
        open_by_cat = {name: 0 for name in CATEGORY_NAMES}
        for r in open_rows:
            open_by_cat[r["category"]] = open_by_cat.get(r["category"], 0) + 1
        p1 = [r["ticket_no"] for r in open_rows if r["priority_tier"] == "P1"]
        oldest = min(open_rows, key=lambda r: (r["first_reported_at"], r["ticket_no"]), default=None)
        return {
            "open": len(open_rows),
            "p1_open": len(p1),
            "p1_open_nos": p1,
            "dispatched": sum(self._count(self._eq(tickets, "status", "==", st)) for st in ("DISPATCHED", "ON_THE_WAY")),
            "resolved": self._count(self._eq(tickets, "status", "==", "RESOLVED")),
            "open_by_category": open_by_cat,
            "oldest_open_no": oldest["ticket_no"] if oldest else None,
            "oldest_open_at": oldest["first_reported_at"] if oldest else None,
        }

    # ------------------------------------------------------------------ teams + dispatch
    def seed_teams(self, teams: list[dict[str, Any]]) -> None:
        from google.api_core.exceptions import AlreadyExists

        for t in teams:
            doc = {k: t[k] for k in ("id", "name", "kind", "capacity", "base_lat", "base_lon")}
            doc["status"] = t.get("status", "free")
            try:
                self._col("teams").document(t["id"]).create(doc)
            except AlreadyExists:
                pass

    def list_teams(self) -> list[dict[str, Any]]:
        out = [s.to_dict() for s in self._col("teams").stream()]
        out.sort(key=lambda t: t["id"])
        return out

    def set_team_status(self, team_id: str, status: str) -> bool:
        from google.api_core.exceptions import NotFound

        try:
            self._col("teams").document(team_id).update({"status": status})
            return True
        except NotFound:
            return False

    def team_loads(self) -> dict[str, int]:
        loads: dict[str, int] = {}
        for s in self._eq(self._col("dispatch"), "active", "==", True).stream():
            tid = s.get("team_id")
            if tid:
                loads[tid] = loads.get(tid, 0) + 1
        return loads

    @staticmethod
    def _dispatch_doc(d: dict[str, Any]) -> dict[str, Any]:
        return {"proposal": d.get("proposal"), "dispatch": d.get("dispatch")}

    def dispatch_records(self) -> dict[int, dict[str, Any]]:
        return {int(s.id): self._dispatch_doc(s.to_dict()) for s in self._col("dispatch").stream()}

    def get_dispatch_record(self, ticket_no: int) -> dict[str, Any] | None:
        snap = self._col("dispatch").document(str(ticket_no)).get()
        return self._dispatch_doc(snap.to_dict()) if snap.exists else None

    def set_proposals(self, proposals: dict[int, dict[str, Any] | None]) -> None:
        for ticket_no, proposal in proposals.items():
            self._col("dispatch").document(str(ticket_no)).set(
                {"ticket_no": ticket_no, "proposal": proposal}, merge=True
            )

    def release_dispatch(self, ticket_no: int) -> None:
        from google.api_core.exceptions import NotFound

        try:
            self._col("dispatch").document(str(ticket_no)).update({"active": False})
        except NotFound:
            pass

    # ------------------------------------------------------------------ sitreps
    def add_sitrep(self, sitrep: dict[str, Any]) -> dict[str, Any]:
        from google.cloud import firestore

        @firestore.transactional
        def run(txn) -> dict[str, Any]:
            ref, sid = self._next(txn, "sitrep")
            saved = {**{k: v for k, v in sitrep.items() if k != "id"}, "id": sid}
            txn.set(self._col("sitreps").document(str(sid)), saved)
            if saved.get("district"):
                # a per-district pointer: a filtered, ordered query would need a composite index
                txn.set(self._col("sitrep_latest").document(saved["district"]), {"id": sid})
            txn.set(ref, {"value": sid})
            return saved

        with self._write_lock:
            return run(self.db.transaction(max_attempts=TXN_ATTEMPTS))

    def latest_sitrep(self, district: str | None = None) -> dict[str, Any] | None:
        from google.cloud import firestore

        if district is not None:
            ptr = self._col("sitrep_latest").document(district).get()
            if not ptr.exists:
                return None
            snap = self._col("sitreps").document(str(int(ptr.get("id")))).get()
            return snap.to_dict() if snap.exists else None
        docs = list(self._col("sitreps").order_by("id", direction=firestore.Query.DESCENDING).limit(1).stream())
        return docs[0].to_dict() if docs else None

    # ------------------------------------------------------------------ broadcasts
    def add_broadcast(self, broadcast: dict[str, Any]) -> dict[str, Any]:
        from google.cloud import firestore

        @firestore.transactional
        def run(txn) -> dict[str, Any]:
            ref, bid = self._next(txn, "broadcast")
            saved = {**{k: v for k, v in broadcast.items() if k != "id"}, "id": bid}
            txn.set(self._col("broadcasts").document(str(bid)), saved)
            txn.set(ref, {"value": bid})
            return saved

        with self._write_lock:
            return run(self.db.transaction(max_attempts=TXN_ATTEMPTS))

    def get_broadcast(self, broadcast_id: int) -> dict[str, Any] | None:
        snap = self._col("broadcasts").document(str(broadcast_id)).get()
        return snap.to_dict() if snap.exists else None

    def update_broadcast(self, broadcast_id: int, fields: dict[str, Any]) -> dict[str, Any] | None:
        from google.cloud import firestore

        ref = self._col("broadcasts").document(str(broadcast_id))

        @firestore.transactional
        def run(txn) -> dict[str, Any] | None:
            snap = ref.get(transaction=txn)
            if not snap.exists:
                return None
            saved = {**snap.to_dict(), **fields, "id": broadcast_id}
            txn.set(ref, saved)
            return saved

        with self._write_lock:
            return run(self.db.transaction(max_attempts=TXN_ATTEMPTS))

    def list_broadcasts(self, district: str | None = None, status: str | None = None) -> list[dict[str, Any]]:
        # equality filters only (no composite index); newest-first sort in memory
        q = self._col("broadcasts")
        for field, val in (("district", district), ("status", status)):
            if val is not None:
                q = self._eq(q, field, "==", val)
        docs = [s.to_dict() for s in q.stream()]
        docs.sort(key=lambda d: d["id"], reverse=True)
        return docs

    # ------------------------------------------------------------------ acks
    def _newest_ack_seq(self) -> int:
        snap = self._col("counters").document("ack_seq").get()
        return int(snap.get("value")) if snap.exists else 0

    def clamp_ack_cursor(self, cursor: int) -> int:
        return 0 if cursor > self._newest_ack_seq() else cursor

    def acks_after(self, cursor: int, limit: int = 50) -> list[dict[str, Any]]:
        q = self._eq(self._col("acks"), "seq", ">", cursor).order_by("seq").limit(limit)
        return [{"id": int(s.get("seq")), "payload": bytes(s.get("payload"))} for s in q.stream()]

    def ack_count(self) -> int:
        return self._newest_ack_seq()
