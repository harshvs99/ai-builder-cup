"""Dispatch teams: the seed file and the public view (load and status are derived).

A team's ``load`` is the number of approved, unresolved tickets assigned to it; the store
counts it (``Store.team_loads``). Status is ``off`` when an operator took the team out, else
``busy`` at capacity, else ``free``.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

DATA_DIR = Path(__file__).resolve().parent / "data"
TEAM_KINDS = ("boat", "medical", "supply")


def load_seed(path: Path | None = None) -> list[dict[str, Any]]:
    teams = json.loads((path or DATA_DIR / "teams_puri.json").read_text(encoding="utf-8"))
    for t in teams:
        if t["kind"] not in TEAM_KINDS:
            raise ValueError(f"team {t['id']}: unknown kind {t['kind']!r}")
    return teams


def team_view(team: dict[str, Any], loads: dict[str, int]) -> dict[str, Any]:
    load = loads.get(team["id"], 0)
    if team["status"] == "off":
        status = "off"
    else:
        status = "busy" if load >= team["capacity"] else "free"
    return {
        "id": team["id"], "name": team["name"], "kind": team["kind"], "capacity": team["capacity"],
        "load": load, "base_lat": team["base_lat"], "base_lon": team["base_lon"], "status": status,
    }
