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
└──────────────────────────────────────────────▲───────────┼──┼──┐
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
└────────────────────────────────────────────────────────────────────┘
```

### Key decisions

| Decision | Choice | Why |
|---|---|---|
| Client ↔ Gemini | **Backend WebSocket proxy** rather than the browser connecting directly with ephemeral tokens | Keeps tools, alert fan-out, transcript buffer and API keys in one place. The browser never sees API secrets. |
| How detection surfaces | The model **calls `raise_scam_alert(...)`** with structured args. We never parse its speech. | Gives a reliable UI trigger, structured red flags, and a confidence score for the overlay. |
| Keeping the model quiet during the call | The system prompt says to stay silent and only call tools. **Model audio is muted client-side unless an alert is active.** The warning is spoken from `web` code, not the model. | Prevents the model from talking over the scam call. |
| Backstop detection | Every 3–5 s, a Gemini Flash call over the rolling transcript returns a JSON classification | Belt and braces in case the Live model misses or delays the function call. |
| Trusted-contact channel | **Telegram Bot API** first, Twilio SMS/WhatsApp optional | Free, instant and reliable for a live demo, with no sender-ID or number verification. |
| Hotline source | **Curated, verified JSON**. Search grounding is used only for advisories and context. | The core safety rule is never to trust a number the caller supplies or a number from an unverified source. |
| Stack | Python 3.12 + FastAPI + `google-genai` SDK (uv); Next.js + TypeScript + Tailwind PWA | The Python SDK has the most complete Live API support, and Next.js PWAs are quick to ship. |
| Hosting | Backend on **Cloud Run** (WebSockets, session affinity); web on Firebase Hosting or Vercel | Microphone access requires HTTPS. Cloud Run is the natural fit for the Google stack. |

> **Model IDs are config, not code.** Pin them in `backend/.env`: a Live native-audio model
> for listen mode, a Flash model for the backstop and uploads, and a Pro/Flash model for the
> report.

---

## 3. Gemini usage in detail

### 3.1 Live session config (listen mode)
- Input: 16 kHz mono PCM16 in roughly 100 ms chunks.
- `input_audio_transcription` is on.
- Automatic voice activity detection is on.
- `speech_config.language_code` comes from the user's chosen warning language.

### 3.2 System prompt (outline, in `backend/app/prompts/live_guardian.md`)
- Role: a silent guardian listening to a phone call on behalf of the user.
- Do **not** speak or answer the caller. Only call tools.
- Known scam scripts and escalation ladder.
- `warning_text`: one or two short sentences at about a 10-year-old's reading level.

### 3.3 Tools

| Tool | Executor | Purpose |
|---|---|---|
| `raise_scam_alert(scam_type, confidence, red_flags[], warning_text, language, verified_facts[])` | backend → AlertBus | Trigger overlay, trusted contact, report |
| `lookup_official_contact(entity, country)` | `knowledge/hotlines.json` | Find official numbers |
| `check_bank_policy(bank, claim)` | `knowledge/bank_policies.json` | Verify bank policy |
| `google_search` (built-in grounding) | Gemini | Current advisories |

### 3.4 Post-alert agents (non-Live, triggered by AlertBus)
1. **TrustedContactNotifier**: a templated message in the contact's language.
2. **ReportAgent**: a Gemini call with structured output. Outputs a report JSON and a rendered Markdown/PDF.

### 3.5 Fallback mode (voice note + screenshot)
`POST /analyze` takes multipart audio and/or images. It makes one `generateContent` call with the same scam-script knowledge and tools.

---

## 4. Repository layout

```
HoldOn - BFSI/
├── PLAN.md                     ← this file
├── README.md                   # setup, run, demo instructions
├── backend/
│   ├── pyproject.toml
│   ├── .env.example
│   └── app/
├── web/
├── knowledge/
├── demo/
└── eval/
```

---

## 5. Phased build

### Phase 0: Spikes and scaffolding (½ day)
- [ ] Scaffold `backend/` and `web/`.
- [ ] Live API spike.
- [ ] Create the Telegram bot.
- [ ] Draft the knowledge JSON files.

### Phase 1: Fallback mode end-to-end (1 day)
- [ ] `knowledge/` loaders and executors.
- [ ] `POST /analyze` → `raise_scam_alert` → AlertBus.
- [ ] AlertBus → Telegram notifier → ReportAgent.

### Phase 2: Live listening (1–1.5 days)
- [ ] Mic capture and transcript.
- [ ] WebSocket bridge to Live API.
- [ ] Spoken warning and alert overlay.

### Phase 3: Multilingual, eval and hardening (1 day)
- [ ] Multi-language scripts and eval.
- [ ] Localisation.
- [ ] Privacy and deploy.

### Phase 4: Demo and pitch polish (½ day)
- [ ] Runbook and pitch deck.

---

## 6. Demo script (target 90 seconds)

1. **Setup:** "This is Mdm Tan's phone. Her daughter Priya is her trusted contact."
2. **The call:** a staged call on speaker triggers an amber banner.
3. **The ask:** a money transfer prompt triggers a red alert and spoken warning.
4. **Buzz:** Priya's phone lights up with a Telegram alert.
5. **Report:** the pre-filled report appears.
6. **Language flip:** a Hindi version triggers the same alert.

---

## 7. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Live API latency or a missed function call | Backstop classifier in parallel |
| Model speaks over the call | Mute model audio; speak only our warning |
| Hallucinated hotline | Use curated JSON with sources |
| False positives | Hard triggers and eval controls |

---

## 8. Workstreams

- **A: Live and detection**
- **B: App and UX**
- **C: Agents and integrations**
- **D: Content and demo**

---

## 9. Open questions
- Hackathon duration and team size.
- Which phones are available.
- Which languages to prioritise.

