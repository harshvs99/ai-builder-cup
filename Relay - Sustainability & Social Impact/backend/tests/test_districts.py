"""The district table (pure) and its agreement with the dashboard's ``src/lib/districts.ts``."""

import re
from pathlib import Path

import pytest

from app import districts
from app.districts import DISTRICTS, District, by_id, district_containing, nearest


def test_table_matches_the_dashboard_ids_names_and_centres():
    src = (Path(__file__).resolve().parents[2] / "dashboard" / "src" / "lib" / "districts.ts").read_text()
    ts = re.findall(r"id: '(\w+)', name: '(\w+)', center: \[([\d.]+), ([\d.]+)\]", src)
    assert [(i, n) for i, n, _, _ in ts] == [(d.id, d.name) for d in DISTRICTS]
    for (_, _, lon, lat), d in zip(ts, DISTRICTS):  # the dashboard stores [lon, lat]
        assert (float(lat), float(lon)) == pytest.approx(d.centre, abs=0.01)


def test_shapes_and_frozen():
    assert [d.id for d in DISTRICTS] == ["puri", "delhi"]
    assert districts.PURI_BBOX == (19.45, 85.05, 20.35, 86.30) and DISTRICTS[0].centre == (19.81, 85.83)
    assert DISTRICTS[1].bbox == (28.40, 76.83, 28.89, 77.35) and DISTRICTS[1].centre == (28.614, 77.209)
    for d in DISTRICTS:
        s, w, n, e = d.bbox
        assert s < d.centre[0] < n and w < d.centre[1] < e
    with pytest.raises(Exception):
        DISTRICTS[0].id = "x"  # type: ignore[misc]


def test_by_id():
    assert by_id("puri").name == "Puri" and by_id("delhi").name == "Delhi"
    assert by_id("all") is None and by_id("") is None and by_id("PURI") is None


def test_nearest_always_answers():
    assert nearest(19.80, 85.83).id == "puri"
    assert nearest(28.6, 77.2).id == "delhi"
    assert nearest(20.29, 85.82).id == "puri"  # Bhubaneswar
    assert nearest(26.0, 80.0).id == "delhi"  # nowhere near either; still the closer centre


def test_district_containing_uses_the_bbox():
    assert district_containing(19.81, 85.83) is DISTRICTS[0]
    assert district_containing(28.6139, 77.2090) is DISTRICTS[1]
    for lat, lon in ((0.0, 0.0), (20.5, 85.83), (28.95, 77.2), (19.81, 87.0), (float("nan"), 85.8), (19.8, float("inf"))):
        assert district_containing(lat, lon) is None, (lat, lon)
    # edges are inside
    assert district_containing(19.45, 85.05).id == "puri" and district_containing(28.89, 77.35).id == "delhi"
    assert isinstance(DISTRICTS[0], District) and DISTRICTS[0].contains(19.81, 85.83)
