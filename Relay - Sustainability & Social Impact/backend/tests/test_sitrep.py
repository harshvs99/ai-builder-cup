"""Sitrep: figures come from store queries; model prose is validated against them."""

import json

from app import sitrep

from conftest import T0, need_frame, sync

COUNTS = {
    "open": 4, "open_by_category": {"MEDICAL": 3, "FOOD": 1}, "p1_open": 2, "dispatched": 5, "resolved": 0,
    "gateways_online": 3, "oldest_open_ticket_no": 101, "oldest_open_age_s": 5400,
}
NOS = [102, 101]


class FakeTextGen:
    def __init__(self, respond):
        self.respond = respond
        self.prompts = []

    def generate_json(self, prompt, schema):
        self.prompts.append(prompt)
        out = self.respond(prompt)
        if isinstance(out, Exception):
            raise out
        return out if isinstance(out, str) else json.dumps(out)


def seed(client, clock):
    sync(client, [
        need_frame(category=0, urgency=3, people=9, vuln_flags=3, lat=10.0, item="a", created_at=T0 - 3000)[0],
        need_frame(category=3, urgency=0, lat=11.0, item="b", created_at=T0 - 600)[0],
        need_frame(category=1, urgency=3, people=9, vuln_flags=3, lat=12.0, item="c", created_at=T0 - 1200)[0],
    ])
    client.post("/v1/tickets/102/approve")
    clock.t = T0 + 120


def test_counts_come_from_the_store(client, clock):
    seed(client, clock)
    s = client.post("/v1/sitreps/run").json()
    c = s["counts"]
    tickets = client.get("/v1/tickets").json()["tickets"]
    open_ = [t for t in tickets if t["status"] == "OPEN"]
    assert set(c) == {"open", "open_by_category", "p1_open", "dispatched", "resolved", "gateways_online",
                      "oldest_open_ticket_no", "oldest_open_age_s"}
    assert c["open"] == len(open_) == 2 and c["dispatched"] == 1 and c["resolved"] == 0
    assert c["p1_open"] == sum(1 for t in open_ if t["priority_tier"] == "P1")
    assert c["open_by_category"]["MEDICAL"] == 1 and c["open_by_category"]["RESCUE"] == 1
    assert c["gateways_online"] == 1
    oldest = min(open_, key=lambda t: t["first_reported_at"])
    assert c["oldest_open_ticket_no"] == oldest["ticket_no"] == 101
    assert c["oldest_open_age_s"] == int(clock.t) - oldest["first_reported_at"]
    assert set(s) == {"id", "period_start", "period_end", "generated_at", "counts", "text_en", "text_local",
                      "local_lang", "ticket_nos"}
    assert s["local_lang"] == "or" and s["generated_at"] == int(clock.t) == s["period_end"]
    assert 101 in s["ticket_nos"] and set(s["ticket_nos"]) <= {101, 103}


def test_template_text_states_the_numbers(client, clock):
    seed(client, clock)
    s = client.post("/v1/sitreps/run").json()
    assert "2 open requests" in s["text_en"] and "1 dispatched" in s["text_en"] and "#101" in s["text_en"]
    assert "2" in s["text_local"] and "#101" in s["text_local"]
    assert sitrep.template_en({**COUNTS, "open": 1}).count("1 open request,") == 1


def test_empty_district(client):
    s = client.post("/v1/sitreps/run").json()
    assert s["counts"]["open"] == 0 and s["counts"]["oldest_open_ticket_no"] is None
    assert s["counts"]["oldest_open_age_s"] is None and s["ticket_nos"] == []
    assert "0 open requests" in s["text_en"]


def test_latest_is_404_until_a_run_then_the_newest(client, clock):
    assert client.get("/v1/sitreps/latest").status_code == 404
    a = client.post("/v1/sitreps/run").json()
    clock.t += 3600
    b = client.post("/v1/sitreps/run").json()
    assert (a["id"], b["id"]) == (1, 2)
    assert client.get("/v1/sitreps/latest").json() == b
    assert a["period_start"] == int(T0 + 60) - 3600                  # first report: default hour
    assert b["period_start"] == a["period_end"]                      # then since the previous one


# ------------------------------------------------------------------ model text validation

def figures_in(prompt):
    return json.loads(prompt.split("Figures JSON:\n")[1].split("\n\nReturn JSON")[0])


def test_valid_model_text_is_used(make_client, clock):
    def respond(prompt):
        f = figures_in(prompt)
        return {"english": f"Update: {f['open']} requests open, {f['p1_open']} top priority, {f['dispatched']} dispatched. "
                           f"Request #{f['oldest_open_ticket_no']} has waited longest.",
                "local": f"{f['open']} ଖୋଲା, #{f['oldest_open_ticket_no']}"}

    fake = FakeTextGen(respond)
    client = make_client(sitrep_textgen=fake)
    seed(client, clock)
    s = client.post("/v1/sitreps/run").json()
    c = s["counts"]
    handed = figures_in(fake.prompts[0])
    assert {k: handed[k] for k in c} == c and handed["ticket_nos"] == s["ticket_nos"]   # the model got the stored figures
    assert s["text_en"].startswith(f"Update: {c['open']} requests open")
    assert s["text_en"] != sitrep.template_en(c) and s["text_local"].startswith(str(c["open"]))


def test_numbers_validation_rules():
    allowed = sitrep.allowed_numbers(COUNTS, NOS)
    assert {4, 3, 1, 2, 5, 0, 101, 102, 90} <= allowed and 7 not in allowed      # 90 = oldest age in minutes
    ok = "4 open, 2 top priority, 5 dispatched, 3 gateways. Oldest is #101 at 90 minutes."
    assert sitrep.valid_text(ok, COUNTS, allowed) == ok
    assert sitrep.valid_text("There are 7 open requests", COUNTS, allowed) is None       # 7 is invented
    assert sitrep.valid_text("Several requests are open", COUNTS, allowed) is None       # open count not stated
    assert sitrep.valid_text("4 open, ticket 103 is urgent", COUNTS, allowed) is None    # ticket not handed over
    assert sitrep.valid_text("4 open, P1 and P2 tiers", COUNTS, allowed) == "4 open, P1 and P2 tiers"  # tier labels
    assert sitrep.valid_text("୪ ଖୋଲା, ୩ ଗେଟୱେ", COUNTS, allowed)                          # Odia digits are read
    assert sitrep.valid_text("୭ ଖୋଲା, ୪", COUNTS, allowed) is None
    assert sitrep.valid_text("", COUNTS, allowed) is None and sitrep.valid_text(5, COUNTS, allowed) is None


def test_mismatched_number_triggers_the_template_fallback(make_client, clock):
    bad = {"english": "99 requests are open across the district.", "local": "99 ଖୋଲା"}
    client = make_client(sitrep_textgen=FakeTextGen(lambda p: bad))
    seed(client, clock)
    s = client.post("/v1/sitreps/run").json()
    assert s["text_en"] == sitrep.template_en(s["counts"]) and s["text_local"] == sitrep.template_or(s["counts"])
    assert "99" not in s["text_en"]


def test_one_bad_language_rejects_both():
    c = COUNTS
    fake = FakeTextGen(lambda p: {"english": "4 open, 2 top priority.", "local": "50 ଖୋଲା"})
    en, local = sitrep.write_text(c, NOS, fake)
    assert (en, local) == (sitrep.template_en(c), sitrep.template_or(c))


def test_model_failures_fall_back():
    for broken in ("not json", "[]", RuntimeError("quota"), TimeoutError(), {"english": 3}):
        fake = FakeTextGen(lambda p, b=broken: b)
        assert sitrep.write_text(COUNTS, NOS, fake) == (sitrep.template_en(COUNTS), sitrep.template_or(COUNTS))
    assert sitrep.write_text(COUNTS, NOS, None) == (sitrep.template_en(COUNTS), sitrep.template_or(COUNTS))
