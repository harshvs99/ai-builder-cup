package org.relay.core.ready

/** How loud a warning is on Home and on the Ready screen. A severity this build does not know is treated as a warning. */
enum class WarningLevel { WATCH, WARNING, ALERT }

/** The rules that decide what the Ready screen and the Home banner show from storage (contract section 3.4). Mirrored in Swift. */
object ReadyView {
    fun level(w: ReadyWarning): WarningLevel = when (w.severity.lowercase()) {
        "watch" -> WarningLevel.WATCH
        "alert" -> WarningLevel.ALERT
        else -> WarningLevel.WARNING
    }

    /** The stored pack's warning, or null when there is none or its `expires_at` has passed (an old pack must not raise a banner). */
    fun activeWarning(pack: ReadyPack?, nowSec: Long): ReadyWarning? {
        val w = pack?.warning ?: return null
        return if (w.expiresAt != null && nowSec >= w.expiresAt) null else w
    }

    /** "Landfall in about N h": whole hours, rounded up, at least 1; null when no landfall time is known or it has passed. */
    fun landfallHours(w: ReadyWarning, nowSec: Long): Int? {
        val at = w.expectedLandfallAt ?: return null
        if (at <= nowSec) return null
        return (((at - nowSec) + 3599) / 3600).toInt().coerceAtLeast(1)
    }

    /** "Shown in English" appears when the plan's steps are not in the language the person uses. */
    fun stepsInOtherLanguage(plan: ReadyPlan, uiLang: String): Boolean = plan.stepsLang != uiLang

    /** The checklist is in `checklist_lang`; the same rule applies to it. */
    fun checklistInOtherLanguage(pack: ReadyPack, uiLang: String): Boolean = pack.checklistLang != uiLang

    /** The footer line kind: published lists say who verified them, demo lists (demo builds only) say they are not verified. */
    enum class ShelterFooter { VERIFIED, DEMO, NONE }

    fun shelterFooter(pack: ReadyPack): ShelterFooter = when {
        pack.shelters.isEmpty() -> ShelterFooter.NONE
        pack.isPublished -> ShelterFooter.VERIFIED
        else -> ShelterFooter.DEMO
    }
}
