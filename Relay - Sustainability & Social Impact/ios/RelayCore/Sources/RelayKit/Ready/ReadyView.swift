import Foundation

/// How loud a warning is on Home and on the Ready screen. A severity this build does not know is treated as a warning.
public enum WarningLevel: Sendable, Hashable { case watch, warning, alert }

/// The rules that decide what the Ready screen and the Home banner show from storage (contract section 3.4). Port of Kotlin `ReadyView`.
public enum ReadyView {
    public static func level(_ w: ReadyWarning) -> WarningLevel {
        switch w.severity.lowercased() {
        case "watch": return .watch
        case "alert": return .alert
        default: return .warning
        }
    }

    /// The stored pack's warning, or nil when there is none or its `expires_at` has passed (an old pack must not raise a banner).
    public static func activeWarning(_ pack: ReadyPack?, nowSec: Int64) -> ReadyWarning? {
        guard let w = pack?.warning else { return nil }
        if let e = w.expiresAt, nowSec >= e { return nil }
        return w
    }

    /// "Landfall in about N h": whole hours, rounded up, at least 1; nil when no landfall time is known or it has passed.
    public static func landfallHours(_ w: ReadyWarning, nowSec: Int64) -> Int? {
        guard let at = w.expectedLandfallAt, at > nowSec else { return nil }
        return max(1, Int((at - nowSec + 3599) / 3600))
    }

    /// "Shown in English" appears when the plan's steps are not in the language the person uses.
    public static func stepsInOtherLanguage(_ plan: ReadyPlan, uiLang: String) -> Bool { plan.stepsLang != uiLang }

    /// The checklist is in `checklist_lang`; the same rule applies to it.
    public static func checklistInOtherLanguage(_ pack: ReadyPack, uiLang: String) -> Bool { pack.checklistLang != uiLang }

    /// The footer line kind: published lists say who verified them, demo lists (demo builds only) say they are not verified.
    public enum ShelterFooter: Sendable, Hashable { case verified, demo, none }

    public static func shelterFooter(_ pack: ReadyPack) -> ShelterFooter {
        if pack.shelters.isEmpty { return .none }
        return pack.isPublished ? .verified : .demo
    }
}
