"""Integration contract. Source of truth for backend <-> web types.

`web/lib/types.ts` is GENERATED from these models (`scripts/gen_types.sh`). Change them here,
then regenerate. Never hand-edit types.ts.
"""

from typing import Annotated, Literal

from pydantic import BaseModel, Field

Language = Literal["en", "hi", "ms", "zh"]  # ms = Bahasa Melayu/Indonesia for the POC
ScamType = Literal[
    "police_impersonation",
    "digital_arrest",
    "investment",
    "bank_impersonation",
    "phishing_otp",
    "job",
    "fake_relative",
    "other",
]
RiskLevel = Literal["low", "elevated", "high"]
AlertSource = Literal["live", "backstop", "heuristic", "upload"]
Channel = Literal["call", "voice_note", "screenshot", "text"]


# ---------- knowledge-backed facts ----------


class OfficialContact(BaseModel):
    entity: str = Field(description="Normalised key, e.g. 'police', 'scamshield', 'lumen_bank'")
    country: str = Field(description="ISO 3166-1 alpha-2, e.g. 'SG', 'IN'")
    name: str
    phone: str | None = None
    url: str | None = None
    source_url: str | None = None
    verified_at: str | None = Field(default=None, description="YYYY-MM-DD the entry was checked")
    fictional: bool = False


class VerifiedFact(BaseModel):
    kind: Literal["official_contact", "bank_policy", "advisory"]
    text: str
    source_url: str | None = None
    fictional: bool = False


# ---------- tool arguments (also the Gemini function declarations) ----------


class RaiseScamAlertArgs(BaseModel):
    scam_type: ScamType
    confidence: float = Field(ge=0, le=1)
    red_flags: list[str] = Field(description="Short phrases, strongest first")
    warning_text: str = Field(description="1-2 short sentences, ~10-year-old reading level")
    language: Language
    verified_facts: list[VerifiedFact] = Field(default_factory=list)


class UpdateRiskArgs(BaseModel):
    level: RiskLevel
    red_flags: list[str] = Field(default_factory=list)


# ---------- core records ----------


class TranscriptLine(BaseModel):
    t: float = Field(description="Seconds since session start")
    speaker: str | None = None
    text: str


class Alert(BaseModel):
    id: str
    session_id: str
    created_at: str = Field(description="ISO 8601 UTC")
    source: AlertSource
    args: RaiseScamAlertArgs
    hotline: OfficialContact | None = Field(
        default=None, description="The number to call instead, from the curated list"
    )


class Payee(BaseModel):
    name: str | None = None
    bank: str | None = None
    account: str | None = None
    amount: float | None = None
    currency: str | None = None


class TimelineEvent(BaseModel):
    t: str = Field(description="Human-readable time or offset, e.g. '00:42'")
    event: str


class Report(BaseModel):
    id: str
    alert_id: str
    session_id: str
    incident_time: str
    channel: Channel
    caller_number: str | None = None
    claimed_identity: str | None = None
    scam_type: ScamType
    timeline: list[TimelineEvent] = Field(default_factory=list)
    key_quotes: list[str] = Field(default_factory=list)
    payee: Payee | None = None
    money_moved: bool = False
    recommended_actions: list[str] = Field(default_factory=list)
    bank_hotline: OfficialContact | None = None
    police_report_url: str | None = None
    verified_facts: list[VerifiedFact] = Field(default_factory=list)
    language: Language = "en"
    generated_by: Literal["gemini", "template"]
    disclaimer: str = "DEMO. Draft for the user to review. HoldOn never submits reports."


ContactChannel = Literal["in_app", "telegram", "whatsapp"]


class ContactAlertParams(BaseModel):
    """Parameters of the channel-agnostic `scam_alert_v1` template.

    Written to WhatsApp template rules so every channel can render it: plain text only,
    no newlines or tabs inside a value, short values, number in the body (no dynamic
    call button). Telegram and in-app render the same template; WhatsApp sends it as an
    approved template with these values as {{1}}..{{5}} in this order.
    """

    user_name: str = Field(max_length=60)
    scam_type_label: str = Field(max_length=60)
    top_red_flag: str = Field(max_length=120)
    user_phone: str = Field(max_length=40)
    status_url: str = Field(max_length=200)


class ContactMessage(BaseModel):
    """A message delivered (or simulated) to the trusted contact."""

    id: str
    created_at: str
    alert_id: str
    channel: ContactChannel
    delivered: bool
    template: Literal["scam_alert_v1"] = "scam_alert_v1"
    language: Language = "en"
    params: ContactAlertParams
    text: str = Field(description="The rendered template, exactly as the contact sees it")
    detail: str | None = None


class ContactLink(BaseModel):
    """How the trusted contact opts in on a channel (Telegram /start code, wa.me prefill)."""

    channel: ContactChannel
    code: str
    deep_link: str | None = Field(default=None, description="None for in_app")
    linked: bool = False


class Profile(BaseModel):
    user_name: str = "Mdm Tan"
    user_phone: str = "+65 8000 0000 (DEMO)"
    country: str = "SG"
    language: Language = "en"
    bank: str = "lumen_bank"
    contact_name: str = "Priya"
    contact_language: Language = "en"
    contact_channel: ContactChannel = "in_app"
    contact_linked: bool = Field(default=True, description="in_app is always linked")


class AnalyzeResult(BaseModel):
    session_id: str
    transcript: list[TranscriptLine] = Field(default_factory=list)
    alert: Alert | None = None
    report_id: str | None = None
    detector: Literal["gemini", "heuristic"]
    risk: RiskLevel = "low"
    red_flags: list[str] = Field(default_factory=list)


class DemoScenario(BaseModel):
    id: str
    title: str
    language: Language
    scam_type: ScamType | None = Field(default=None, description="None for benign controls")
    is_scam: bool
    lines: list[TranscriptLine]
    trigger_t: float | None = Field(default=None, description="t of the money/OTP/secrecy ask")
    audio_url: str | None = None


class Health(BaseModel):
    ok: bool = True
    gemini: bool
    telegram: bool
    whatsapp: bool = False
    detector: Literal["gemini", "heuristic"]
    models: dict[str, str]


# ---------- WebSocket /ws/listen ----------
# Client -> server: first a JSON `WsStart`, then binary PCM16 16 kHz mono frames (mode=audio)
# or JSON `WsText` frames (mode=text). Optional `WsImage`. Finally `WsStop`.


class WsStart(BaseModel):
    type: Literal["start"] = "start"
    mode: Literal["audio", "text"]
    language: Language = "en"
    channel: Channel = "call"


class WsText(BaseModel):
    type: Literal["text"] = "text"
    text: str
    speaker: str | None = None
    final: bool = True


class WsImage(BaseModel):
    type: Literal["image"] = "image"
    mime: str
    data_b64: str


class WsStop(BaseModel):
    type: Literal["stop"] = "stop"


ClientMessage = Annotated[WsStart | WsText | WsImage | WsStop, Field(discriminator="type")]


# Server -> client


class WsSession(BaseModel):
    type: Literal["session"] = "session"
    session_id: str
    mode: Literal["gemini_live", "text"]
    detector: Literal["gemini", "heuristic"]


class WsCaption(BaseModel):
    type: Literal["caption"] = "caption"
    line: TranscriptLine


class WsRisk(BaseModel):
    type: Literal["risk"] = "risk"
    level: RiskLevel
    red_flags: list[str] = Field(default_factory=list)


class WsAlert(BaseModel):
    type: Literal["alert"] = "alert"
    alert: Alert


class WsContactNotified(BaseModel):
    type: Literal["contact_notified"] = "contact_notified"
    message: ContactMessage


class WsReportReady(BaseModel):
    type: Literal["report_ready"] = "report_ready"
    report_id: str


class WsError(BaseModel):
    type: Literal["error"] = "error"
    message: str


ServerMessage = Annotated[
    WsSession | WsCaption | WsRisk | WsAlert | WsContactNotified | WsReportReady | WsError,
    Field(discriminator="type"),
]


# Everything exported to TypeScript.
EXPORTED_MODELS: list[type[BaseModel]] = [
    OfficialContact,
    VerifiedFact,
    RaiseScamAlertArgs,
    UpdateRiskArgs,
    TranscriptLine,
    Alert,
    Payee,
    TimelineEvent,
    Report,
    Profile,
    ContactAlertParams,
    ContactMessage,
    ContactLink,
    AnalyzeResult,
    DemoScenario,
    Health,
    WsStart,
    WsText,
    WsImage,
    WsStop,
    WsSession,
    WsCaption,
    WsRisk,
    WsAlert,
    WsContactNotified,
    WsReportReady,
    WsError,
]
