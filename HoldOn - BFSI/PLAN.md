# HoldOn: Build Plan

> Scam interception for the person being scammed. HoldOn listens to a call or voice note,
> recognises the scam script, checks the caller's claims against trusted sources, and
> interrupts with a plain-language warning. At the same moment it alerts a trusted contact
> and pre-drafts the bank fraud report.

Everything lives under `HoldOn - BFSI/` in this repository.

---

## 1. Goals and non-goals

### What the demo must show (definition of done)
1. **The staged call.** A pre-recorded "police officer" call plays out loud. The HoldOn app is
   listening on a phone. Within **≤ 5 s of the money ask** ("move your savings to a safe
   account"), a full-screen overlay reads *"Scam pattern: police never ask you to move money
   to a safe account"*, the phone vibrates, and a short spoken warning plays in the user's
   language.
2. **The daughter's phone buzzes.** The trusted contact gets a message saying what is
   happening, which scam type was detected, and a one-tap button to call the user.
3. **Claims checked against sources.** The overlay shows *why*: the official SPF / ScamShield
   hotline (from a curated list, never the number the caller gave), the bank's "we will never
   ask you to…" policy, and a police advisory found through Google Search grounding, with a
   link.
4. **The fraud report.** A pre-filled bank and police report with a timeline, transcript
   quotes, payee details read from a screenshot, and the amount. It is ready for the user
   to review and send.
5. **Four languages.** The same flow works in English, Hindi, Bahasa Indonesia/Melayu and
   Mandarin, with at least one non-English take in the live demo.
6. **Fallback mode.** Uploading a voice note and/or a screenshot of a payment request or
   fake "arrest warrant" triggers the same alert, contact notification and report pipeline.

### Non-goals (for the hackathon)
- Intercepting the phone's cellular call audio directly. Android and iOS don't allow
  third-party apps to tap call audio. HoldOn listens through the microphone while the call
  is on **speakerphone**, or to a voice note. We say this openly in the pitch; it is also
  how the real product would ship first.
- Native mobile apps. We build an installable **PWA** for mobile Chrome/Safari instead.
- Submitting reports automatically. HoldOn drafts them and a person always sends them.

---

## 2. Architecture

```
┌──────────────────────── Phone (PWA, Next.js) ────────────────────────┐
│  Listen mode: mic → AudioWorklet → 16 kHz PCM16 chunks ──┐           │
│  Fallback mode: voice-note upload / screenshot upload ───┼──┐        │
│  AlertOverlay · Spoken warning · Vibrate · "Call Priya"  │  │        │
└──────────────────────────────────────────────▲───────────┼──┼────────┘
                                   alert events │  WS      │  │ HTTPS
┌───────────────────────── Backend (FastAPI on Cloud Run) ─┼──┼────────┐
│                                                          ▼  ▼        │
│  LiveSessionProxy ──► Gemini Live API (native audio,                 │
│     │                 input transcription, tools: function calls     │
│     │                 + google_search grounding)                     │
│     │                                                                │
│     ├─ Tool executors (local, <100 ms):                              │
│     │    raise_scam_alert · lookup_official_contact ·                │
│     │    check_bank_policy                                           │
│     ├─ Transcript buffer (rolling, timestamped)                      │
│     ├─ Backstop classifier (Gemini Flash on rolling window, 3–5 s)   │
│     │                                                                │
│  AnalyzeUpload (fallback): generateContent(audio/image) → same tools │
│                                                                      │
│  On alert ─► AlertBus ─┬─► push to phone WS (overlay)                │
│                        ├─► TrustedContactNotifier (Telegram / SMS)   │
│                        └─► ReportAgent (Gemini, structured output)   │
│                                 → report JSON → PDF/Markdown         │
│  Knowledge base (JSON): hotlines · bank policies · scam scripts      │
└──────────────────────────────────────────────────────────────────────┘
```

### Key decisions

| Decision | Choice | Why |
|---|---|---|
| Client ↔ Gemini | **Backend WebSocket proxy** rather than the browser connecting directly with ephemeral tokens | Keeps tools, alert fan-out, transcript buffer and API keys in one place. The extra hop costs about 50 ms. Ephemeral tokens remain a later optimisation. |
| How detection surfaces | The model **calls `raise_scam_alert(...)`** with structured args. We never parse its speech. | Gives a reliable UI trigger, structured red flags, and a confidence score to apply thresholds to. |
| Keeping the model quiet during the call | The system prompt says to stay silent and only call tools. **Model audio is muted client-side unless an alert is active.** The warning is spoken from `warning_text` via TTS. Try the native-audio "proactive audio" setting so the model can choose not to respond. | A chatty AI talking over a call ruins the demo. We control exactly what is spoken and when. |
| Backstop detection | Every 3–5 s, a Gemini Flash call over the rolling transcript returns a JSON classification | Belt and braces in case the Live model misses or delays the function call. Whichever path fires first wins, deduplicated per session. |
| Trusted-contact channel | **A channel-agnostic `Notifier`** with three implementations: in-app (`/contact`, always on), **Telegram** (the demo channel), and **WhatsApp** (a stub behind the same interface, for production) | Telegram buzzes a real phone after 5 minutes of setup. WhatsApp is what contacts in SG/IN/MY/ID actually use, but it needs Meta business verification and approved templates, which take days. Every alert is the template `scam_alert_v1` plus named parameters, kept within WhatsApp's template limits, so switching channels needs no change to the message. |
| Hotline source | **Curated, verified JSON**. Search grounding is used only for advisories and context. | The core safety rule is never to trust a number the caller supplies or a number from an arbitrary web result. |
| Stack | Python 3.12 + FastAPI + `google-genai` SDK (uv); Next.js + TypeScript + Tailwind PWA | The Python SDK has the most complete Live API support, and Next.js PWAs are quick to ship. |
| Hosting | Backend on **Cloud Run** (WebSockets, session affinity); web on Firebase Hosting or Vercel | Microphone access requires HTTPS. Cloud Run is the natural fit for the Google stack. |

> **Model IDs are config, not code.** Pin them in `backend/.env`: a Live native-audio model
> for listen mode, a Flash model for the backstop and uploads, and a Pro/Flash model for the
> report. In the Phase 0 spike, check which current models support **function calling and
> Google Search in the same Live session**, plus input transcription.

> **Gemini API via AI Studio (decided), Vertex AI later if needed.** Use the `google-genai`
> SDK with a **paid-tier** AI Studio key; on the free tier, Google may use prompts to improve
> its products. Build the client in one place (`backend/app/genai_client.py`) so switching
> is a config change: set `GOOGLE_GENAI_USE_VERTEXAI=true`, `GOOGLE_CLOUD_PROJECT` and
> `GOOGLE_CLOUD_LOCATION`, then swap the model IDs (Live model names differ between AI
> Studio and Vertex). The pitch for moving to Vertex later: data residency in Singapore
> (`asia-southeast1`), VPC-SC and enterprise terms, which is what a bank partner would ask
> for.

> **Brand policy: "Lumen Bank" is the demo bank (decided).** Lumen Bank is made up. It is
> the user's bank in every scenario and the only bank with a logo, colours or branded
> screens anywhere in the demo: the report, the payee and payment-request screenshots, and
> the staged bank-impersonation call.
> - **Real banks (HDFC Bank, DBS, OCBC, UOB, etc.) appear only as plain text:** cited
>   policy quotes ("HDFC Bank says it will never ask for your OTP" + source link) and
>   entries in the hotline and policy lists. No logos, colours, screen designs or lookalike
>   names ("HDFK") ever.
> - **Real agencies (SPF, ScamShield, CBI, the 1930 cybercrime helpline)** may be *named* in staged
>   scam scripts, because impersonating them is how the scam works, and in cited
>   advisories. They never appear with logos, crests or uniforms in visuals.
> - **Every mock document** (warrant, payment request, report) carries a visible `DEMO`
>   watermark and uses Lumen Bank or obviously fictional details.
> - **Lumen Bank's phone numbers, URLs and account numbers are fictional and labelled
>   `DEMO`.** Never a string that could dial or reach a real person or service:
>   - Phone: `1800-LUMEN-DEMO`. On a keypad that is 13 digits, which is not a valid length
>     in Singapore or India. (`1800-LUMEN-00` would map to 1800-586-3600, a well-formed SG/IN
>     toll-free number, so it is **not** allowed.) Never wrapped in a `tel:` link.
>   - Web: `lumen-bank.example`. `.example` is reserved (RFC 2606) and never resolves.
>   - Accounts: `DEMO-0000-1234` style.
>   - Enforced by `backend/tests/test_knowledge.py`.

### POC decisions (Phase 0)

| Decision | Choice | Why |
|---|---|---|
| Runs without keys | Every external dependency has an **offline path**: a heuristic detector instead of Gemini, browser `SpeechRecognition` instead of Live transcription, an in-app "contact phone" (`/contact`) instead of Telegram, and a template report instead of ReportAgent. Gemini and Telegram switch on when their keys are in `backend/.env`. | The demo can't depend on venue Wi-Fi or a quota. It also gives us the **baseline**: Gemini has to beat the heuristic on `eval/` before it becomes the default. |
| Upload analysis | Gemini **structured output** (transcript + assessment + payee). The backend then runs `lookup_official_contact` / `check_bank_policy` itself to build `verified_facts`. | Verified facts never come from the model. Running the tools ourselves is simpler and more reliable than a tool-call loop on `generateContent`. |
| Live listen | `/ws/listen` takes either PCM16 audio (Gemini Live) or caption text (the browser's speech engine or a staged-call script). Both feed the same transcript buffer, heuristic backstop and AlertBus. | One pipeline, three input sources. The staged-call text mode is deterministic, so it's the demo we can rehearse reliably. |
| "Call Mum" button | The user's phone number goes in the message body as plain text, which both Telegram and WhatsApp turn into a tappable link. No call button. | Telegram inline URL buttons don't accept `tel:`, and WhatsApp template call buttons can only dial a number fixed when the template is approved (both assumptions, checked by the spike). |
| Contact opt-in | `LinkFlow` for each channel returns `{deep_link, code}`: `t.me/<bot>?start=<code>` for Telegram, `wa.me/<number>?text=HOLDON-<code>` for WhatsApp | Both apps need the contact to message first. Telegram needs a `chat_id`. On WhatsApp the contact's message also opens the 24-hour window for free-form follow-ups. |
| State | A single-user JSON file store in `backend/.data/`, gitignored | The POC has one user (Mdm Tan) and one contact (Priya). No database needed. |
| Contracts | Pydantic models in `backend/app/tools/schemas.py` are the source of truth. `web/lib/types.ts` is generated from their JSON Schema by `scripts/gen_types.sh`. | The backend and web types can't drift apart. |

---

## 3. Gemini usage in detail

### 3.1 Live session config (listen mode)
- Input: 16 kHz mono PCM16 in roughly 100 ms chunks. Output audio at 24 kHz is ignored or muted
  unless an alert is active.
- `input_audio_transcription` is on. It feeds the transcript buffer, the backstop
  classifier, the report and on-screen captions.
- Automatic voice activity detection is on. Both voices on speakerphone count as "user"
  audio, which is fine because the model judges the conversation as a whole.
- Turn on **context window compression and session resumption**. Audio-only Live sessions
  have a hard length cap, and scam calls run long ("digital arrest" calls can last hours).
- Screenshots can be sent **mid-session** as image realtime input ("I just got this
  WhatsApp, is it real?").
- `speech_config.language_code` comes from the user's chosen warning language. The input
  language is detected automatically.

### 3.2 System prompt (outline, in `backend/app/prompts/live_guardian.md`)
- Role: a silent guardian listening to a phone call on behalf of the user.
- Do **not** speak or answer the caller. Only call tools.
- Known scripts and their tell-tale moves (loaded from `knowledge/scam_scripts.json`):
  - *Government/police impersonation*: a case against you, money laundering, a parcel with
    drugs, "transfer to a safe account for investigation", "don't tell anyone", a video
    call with a fake uniform or office.
  - *"Digital arrest"* (India): CBI/ED/customs/TRAI, Aadhaar misuse, the victim kept on a
    video call, "you are under digital arrest", demands for "verification" deposits.
  - *Investment*: guaranteed returns, a WhatsApp/Telegram "mentor" group, small early
    payouts, a fee to withdraw.
  - *Bank impersonation, phishing, OTP harvesting, job scams, fake friend/relative.*
- Escalation ladder: note red flags → `lookup_official_contact` / `check_bank_policy` /
  search advisories to verify the claim → call `raise_scam_alert` when confidence ≥ 0.7
  **or** immediately on a hard trigger (asked to transfer money, share an OTP, install an
  app, or keep the call secret).
- `warning_text`: one or two short sentences at about a 10-year-old's reading level, in the
  user's language, naming the single strongest reason.
- Bias: a false alarm costs a minute, while a miss can cost someone's savings. Still never
  alert on a legitimate-sounding bank or police call that makes no money, OTP or secrecy
  ask.

### 3.3 Tools

| Tool | Executor | Purpose |
|---|---|---|
| `raise_scam_alert(scam_type, confidence, red_flags[], warning_text, language, verified_facts[])` | backend → AlertBus | The trigger for the overlay, trusted contact and report |
| `lookup_official_contact(entity, country)` | `knowledge/hotlines.json` | Official numbers for SPF, ScamShield, CBI/1930, banks, OJK and others, so the overlay can say "call the real number" |
| `check_bank_policy(bank, claim)` | `knowledge/bank_policies.json` | Lumen Bank's (fictional) policy for the demo, e.g. "Lumen Bank will never ask you to transfer funds to a 'safe account'", plus real banks' cited public statements as text |
| `google_search` (built-in grounding) | Gemini | Current police advisories and scam alerts, with citations for the overlay |
| `update_risk(level, red_flags[])` *(optional)* | backend → phone | A soft amber "Be careful…" banner before the full alert, which builds tension in the demo |

Keep every custom tool under 100 ms, since Live tool calls block the model's turn. Where the
model supports it, mark `update_risk` as non-blocking.

### 3.4 Post-alert agents (non-Live, triggered by AlertBus)
1. **TrustedContactNotifier**: a templated message in the contact's language, e.g. *"⚠️ HoldOn:
   Mum may be on a scam call now (police impersonation). Call her: [tap]."* It also includes
   the top two red flags and a link to a read-only live status page. Rate limit: one alert
   per session plus escalations.
2. **ReportAgent**: a Gemini call with structured output (Pydantic schema). Inputs are the
   transcript buffer, alert args and any screenshots. Outputs:
   `{incident_time, channel, caller_number, claimed_identity, scam_type, timeline[],
   key_quotes[], payee: {name, bank, account, amount}, money_moved: bool,
   recommended_actions[], bank_hotline, police_report_url}`.
   We render it to a Markdown/PDF report and a "copy for Lumen Bank" text block. Screenshot OCR and
   field extraction (account number, amount, fake case number) use Gemini vision in the same
   call.

### 3.5 Fallback mode (voice note + screenshot)
`POST /analyze` takes multipart audio (ogg/m4a/mp3/wav) and/or images. It makes one
`generateContent` call with the same scam-script knowledge, function declarations and
search grounding, then sends the result through the **same AlertBus**. This keeps the
contact notification and report identical across modes and gives the "keeps most of the
demo" guarantee. **We build it first.**

---

## 4. Repository layout

```
HoldOn - BFSI/
├── PLAN.md                     ← this file
├── README.md                   # setup, run, demo instructions
├── backend/
│   ├── pyproject.toml          # uv; fastapi, uvicorn, google-genai, pydantic, httpx, weasyprint|markdown-pdf
│   ├── .env.example            # GEMINI_API_KEY, model IDs, TELEGRAM_BOT_TOKEN, ...
│   ├── app/
│   │   ├── main.py             # FastAPI app: /ws/listen, /analyze, /contacts, /reports/{id}
│   │   ├── live/proxy.py       # browser WS ⇄ Gemini Live session, resumption, tool dispatch
│   │   ├── live/transcript.py  # rolling timestamped buffer
│   │   ├── detect/backstop.py  # periodic Flash classifier over transcript window
│   │   ├── detect/analyze.py   # fallback-mode generateContent pipeline
│   │   ├── tools/              # declarations + executors (alert, contacts, bank policy)
│   │   ├── alerts/bus.py       # dedupe, thresholds, fan-out
│   │   ├── alerts/notify.py    # Notifier interface + scam_alert_v1 template ×4 languages
│   │   ├── alerts/telegram.py  # Telegram notifier + /start link polling
│   │   ├── alerts/whatsapp.py  # WhatsApp Cloud API notifier (stub until Meta approval)
│   │   ├── report/agent.py     # structured-output report generation
│   │   ├── report/render.py    # Markdown/PDF
│   │   ├── prompts/            # live_guardian.md, analyze.md, report.md, backstop.md
│   │   └── i18n/               # warning + contact message templates ×4 languages
│   └── tests/                  # tool executors, alert bus dedupe, report schema, KB lookups
├── web/
│   ├── package.json            # next, react, tailwind, next-pwa
│   ├── app/
│   │   ├── page.tsx            # home: Listen / Check a voice note / Check a screenshot
│   │   ├── listen/page.tsx     # live captions, risk meter, AlertOverlay
│   │   ├── check/page.tsx      # upload flow
│   │   ├── report/[id]/page.tsx
│   │   └── setup/page.tsx      # language + trusted contact (Telegram deep link)
│   ├── components/AlertOverlay.tsx, RiskBanner.tsx, Captions.tsx
│   ├── lib/audio/              # pcm-worklet.js (downsample to 16 kHz), player.ts, ws.ts
│   └── public/brand/lumen/     # Lumen Bank logo + palette (original design, not derived from any real bank)
├── knowledge/
│   ├── hotlines.json           # verified official numbers per country/entity, with source URL; Lumen Bank entry fictional: true
│   ├── bank_policies.json      # Lumen Bank (fictional: true) + real banks' cited "we will never…" quotes, with source URL
│   └── scam_scripts.json       # typology → stages, phrases (4 langs), hard triggers
├── demo/
│   ├── scripts/                # staged call scripts: police (EN, HI), digital arrest (HI), investment (ZH), Lumen Bank impersonation (MS/ID)
│   ├── audio/                  # generated two-voice call recordings (Gemini TTS multi-speaker)
│   ├── screenshots/            # mock "warrant", Lumen Bank payment request — watermarked DEMO
│   └── RUNBOOK.md              # minute-by-minute stage script + recovery steps
└── eval/
    ├── clips.csv               # clip, language, label (scam/benign), scam_type, trigger_time
    └── run_eval.py             # detection rate, false-positive rate, time-to-alert
```

---

## 5. Phased build

Each phase ends with something demoable. **The fallback path comes first** so a working demo
exists from Phase 1 onward.

### Phase 0: Spikes and scaffolding (½ day)
- [ ] Scaffold `backend/` (uv + FastAPI) and `web/` (Next.js + Tailwind), with a `.env.example`.
- [ ] Shared contracts: `schemas.py` → JSON Schema → `web/lib/types.ts`, plus `CONTRACTS.md` (REST + WS).
- [ ] **Live API spike** (a Python script, no UI): stream a WAV file into a Live session with
      input transcription, one function declaration and `google_search`. Measure the time
      from the trigger phrase to the function call. **Go/no-go on combining tools.** If the
      chosen model can't combine search and function calls, move search into the backstop
      or report path and keep the Live session on function calls only.
- [ ] Check native-audio behaviour: can the model stay silent? Does proactive audio help?
- [ ] Create the Telegram bot, then confirm a send reaches a real phone. Check whether a
      `tel:` inline button is rejected. If it is, put the number in the message text.
- [ ] Draft the knowledge JSON files and verify every hotline and policy against an official
      source URL.

### Phase 1: Fallback mode end-to-end (1 day)
- [ ] `knowledge/` loaders and the `lookup_official_contact` / `check_bank_policy` executors, with tests.
- [ ] `POST /analyze` (voice note and/or screenshot) → `raise_scam_alert` → AlertBus.
- [ ] AlertBus → Notifier (in-app + Telegram) → ReportAgent → `/reports/{id}`.
- [ ] Web: setup page (language, trusted contact via Telegram deep link `t.me/<bot>?start=<code>`),
      upload page, AlertOverlay, report page.
- [ ] Generate demo assets: a police-impersonation voice note (EN), a fake warrant
      screenshot, and a Lumen Bank payment-request screenshot. Create the Lumen Bank logo
      and palette first, so every asset uses them.
- **Exit:** upload a voice note → overlay + daughter's phone buzzes + report renders.

### Phase 2: Live listening (1–1.5 days)
- [ ] `web/lib/audio/pcm-worklet.js`: mic → 16 kHz PCM16 → WS. Wake lock so the screen stays on.
- [ ] `backend/app/live/proxy.py`: WS bridge, tool dispatch, transcript buffer,
      session resumption and compression, reconnects.
- [ ] Live captions and the amber RiskBanner (`update_risk`), escalating to the full AlertOverlay.
- [ ] Spoken warning (TTS of `warning_text` in the user's language) + `navigator.vibrate`
      pattern + a "Hang up and call the real police: 1800-…" CTA.
- [ ] Backstop classifier and deduplication in AlertBus.
- [ ] Mid-call screenshot share from the listen screen.
- **Exit:** play the staged call from a laptop speaker and the alert fires within 5 s of the ask, 5 times in a row.

### Phase 3: Multilingual, eval and hardening (1 day)
- [ ] Script and generate staged calls: Hindi digital arrest, Mandarin investment, Bahasa
      bank impersonation, plus **benign controls** (a genuine Lumen Bank callback with no money ask,
      a delivery call, a family call).
- [ ] `eval/run_eval.py`: report detection rate, false positives and median and p90 time-to-alert
      per language. Iterate on the prompt and scam scripts until the numbers are stable.
- [ ] Localise all overlay and contact strings ×4.
- [ ] Privacy pass: no audio stored by default, transcripts kept only for the session unless the
      user saves the report, and consent screen copy.
- [ ] Deploy: Cloud Run with min instances = 1 to avoid a cold start on stage, plus HTTPS web.

### Phase 4: Demo and pitch polish (½ day)
- [ ] `demo/RUNBOOK.md`: devices, volumes, network (phone hotspot as a backup), reset steps.
- [ ] A pre-recorded backup video of the full flow.
- [ ] Pitch slides: the S$1.1B problem (verify the latest SPF figure and cite it), "nobody is
      in the room", live demo, eval numbers, privacy, roadmap (Android call-screening
      integration, bank partnership API, elderly-care deployments).

---

## 6. Demo script (target 90 seconds)

1. **Setup (10 s):** "This is Mdm Tan's phone. Her daughter Priya is her trusted contact." Priya's phone is on the table.
2. **The call (40 s):** a laptop plays a staged call on "speaker": *"This is Inspector Lim
   from SPF. Your Lumen Bank account is linked to a money-laundering case…"* Captions stream and
   the amber banner rises: *"Caller claims to be police. Stay alert."*
3. **The ask (5 s):** *"…to protect your savings, transfer them to this safe account."*
   → red overlay plus spoken warning plus vibration: **"Scam pattern: police never ask you to
   move money to a safe account."** The overlay cites the SPF advisory and lists the
   ScamShield Helpline from the verified list.
4. **Buzz (5 s):** Priya's phone lights up with the Telegram alert, and she taps "Call Mum".
5. **Report (15 s):** the pre-filled report shows the timeline, quotes, and the payee's Lumen Bank account
   read from the screenshot the "officer" sent.
6. **Language flip (15 s):** a 10-second Hindi "digital arrest" clip triggers the same overlay
   in Hindi.

Recovery: if live audio misbehaves, switch to the **Check a voice note** button with the
same recording. The overlay, buzz and report are identical.

---

## 7. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Live API latency or a missed function call | Backstop classifier in parallel. Hard-trigger phrases in the prompt. Rehearse 5× before demo. Fall back to voice-note mode. |
| Model speaks over the call | Mute model audio client-side and speak only our own `warning_text` |
| Venue noise or speaker distortion | Run the laptop speaker at a fixed volume close to the phone, test at the venue, and keep a line-in or wired option. Generated audio is clean. |
| Venue Wi-Fi fails | Phone hotspot, Cloud Run min instances, and the backup video |
| Session length cap on long calls | Context compression + session resumption, as in Phase 2 |
| Hallucinated or wrong hotline | Numbers come only from curated JSON with source URLs. Search results are shown as cited advisories only. |
| False positives on legitimate calls | Benign controls in eval, and a hard rule against alerting without a money, OTP, app-install or secrecy ask unless confidence is high |
| Multilingual quality (code-mixed Hinglish, Singlish) | Include code-mixed clips in eval and add phrase variants to `scam_scripts.json` |
| Telegram blocked or delayed | The in-app contact phone (`/contact`) always gets the same alert. Twilio SMS can be added as another `Notifier`. |
| Moving the contact channel to WhatsApp later | Alerts are already template + parameters within WhatsApp's limits. Submit `scam_alert_v1` in 4 languages for Meta approval early, because each change to the wording means another review. |
| Real-brand misuse (implied endorsement, phishing-grade mocks) | Brand policy in §2: Lumen Bank for all visuals, real names only as cited text, a DEMO watermark on every mock |
| Privacy and consent concerns from judges | On-device consent, nothing stored by default, the user controls the report, and the call stays on the user's speaker (no call interception) |

---

## 8. Workstreams (if splitting across people)

- **A: Live and detection:** Live proxy, prompts, tools, backstop, eval.
- **B: App and UX:** PWA, audio worklet, overlay, captions, setup, report page, i18n.
- **C: Agents and integrations:** AlertBus, Telegram/Twilio, ReportAgent, PDF.
- **D: Content and demo:** knowledge JSON (verified), staged call scripts and audio in 4
  languages, screenshots, runbook, pitch deck.

Integration contract (agree on Day 1): the `raise_scam_alert` argument schema, the WS message
types (`caption`, `risk`, `alert`, `contact_notified`, `report_ready`), and the report JSON
schema. These live in `backend/app/tools/schemas.py` and are mirrored in `web/lib/types.ts`.

---

## 9. Open questions
- Hackathon duration and team size. The phases above assume about 4 working days. Compress
  Phases 3 and 4 if there is less time.
