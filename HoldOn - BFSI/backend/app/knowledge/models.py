"""Schemas for the curated knowledge files in `knowledge/`. Loaded and validated at startup."""

from typing import Literal

from pydantic import BaseModel, Field, model_validator

from app.tools.schemas import Language, ScamType

Langs = dict[Language, list[str]]


class Sourced(BaseModel):
    source_url: str | None = None
    verified_at: str | None = Field(default=None, description="YYYY-MM-DD")
    fictional: bool = False

    @model_validator(mode="after")
    def _sourced_unless_fictional(self):
        if not self.fictional and not (self.source_url and self.verified_at):
            raise ValueError("real entries need source_url and verified_at")
        return self


class HotlineEntry(Sourced):
    entity: str = Field(description="police | scamshield | cybercrime | bank:<id> | regulator")
    country: str
    name: str
    phone: str | None = None
    url: str | None = None
    purpose: str = Field(description="When to call this number, one line")


class HotlinesFile(BaseModel):
    version: int = 1
    entries: list[HotlineEntry]


class PolicyStatement(Sourced):
    claim: str = Field(
        description="Normalised claim key: transfer_safe_account | share_otp | install_app | "
        "keep_secret | share_pin | click_link | remote_access"
    )
    text: str = Field(description="'<Bank> will never ...' as plain text")


class BankEntry(BaseModel):
    id: str = Field(description="e.g. lumen_bank, dbs, hdfc")
    name: str
    country: str
    fictional: bool = False
    policies: list[PolicyStatement]


class BankPoliciesFile(BaseModel):
    version: int = 1
    banks: list[BankEntry]


class ScamStage(BaseModel):
    name: str
    phrases: Langs = Field(description="Indicative phrases per language, lowercase")


class ScamScript(BaseModel):
    scam_type: ScamType
    title: str
    claimed_identities: Langs = Field(description="Who the caller claims to be")
    stages: list[ScamStage]
    hard_triggers: Langs = Field(
        description="Money / OTP / app-install / secrecy asks. Any match + identity => alert"
    )
    red_flag_labels: dict[Language, str] = Field(description="Short label for the overlay")
    warning: dict[Language, str] = Field(description="Spoken warning, 1-2 sentences")
    hotline_entity: str = Field(description="Which hotline entity to show, e.g. 'police'")
    policy_claim: str | None = Field(default=None, description="Bank policy claim to cite")
    advisory: dict[Literal["text", "source_url", "verified_at"], str] | None = None


class ScamScriptsFile(BaseModel):
    version: int = 1
    generic_hard_triggers: Langs
    scripts: list[ScamScript]
