package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.ready.ReadyJson
import org.relay.core.ready.ReadyPack
import org.relay.core.ready.ReadyView
import org.relay.core.ready.ReadyWarning
import org.relay.core.ready.WarningLevel

/** Banner and screen rules. Mirrored by Swift `ReadyViewTests`. */
class ReadyViewTest {
    private fun w(sev: String = "warning", landfall: Long? = 1000 + 5 * 3600 + 1, expires: Long? = null) =
        ReadyWarning(4, "IMD", sev, "Cyclone", 900, landfall, expires)
    private fun pack(warning: ReadyWarning?, source: String = "published", shelters: Boolean = true, checklistLang: String = "en") = ReadyPack(
        "puri", "hi", 1, "v1", warning,
        if (shelters) ReadyJson.parsePack("""{"district":"puri","version":"v","shelters":[{"id":"a","name":"A","lat":1.0,"lon":2.0}]}""")!!.shelters else emptyList(),
        source, null, emptyList(), checklistLang,
    )

    @Test fun severityMapsToTheThreeBannerColoursAndUnknownIsAWarning() {
        assertEquals(WarningLevel.WATCH, ReadyView.level(w("watch")))
        assertEquals(WarningLevel.WARNING, ReadyView.level(w("warning")))
        assertEquals(WarningLevel.ALERT, ReadyView.level(w("ALERT")))
        assertEquals(WarningLevel.WARNING, ReadyView.level(w("something-new")))
    }

    @Test fun anExpiredOrMissingWarningRaisesNoBanner() {
        assertNull(ReadyView.activeWarning(null, 1000))
        assertNull(ReadyView.activeWarning(pack(null), 1000))
        assertEquals(4L, ReadyView.activeWarning(pack(w()), 1000)!!.id)
        assertEquals(4L, ReadyView.activeWarning(pack(w(expires = 2000)), 1999)!!.id)
        assertNull(ReadyView.activeWarning(pack(w(expires = 2000)), 2000))
    }

    @Test fun landfallIsWholeHoursRoundedUpAndHiddenWhenUnknownOrPast() {
        assertEquals(6, ReadyView.landfallHours(w(), 1000))
        assertEquals(1, ReadyView.landfallHours(w(landfall = 1000 + 60), 1000))
        assertEquals(5, ReadyView.landfallHours(w(landfall = 1000 + 5 * 3600), 1000))
        assertNull(ReadyView.landfallHours(w(landfall = null), 1000))
        assertNull(ReadyView.landfallHours(w(landfall = 1000), 1000))
        assertNull(ReadyView.landfallHours(w(landfall = 500), 1000))
    }

    @Test fun shownInAnotherLanguageWhenTheStepsAreNotInTheUiLanguage() {
        val plan = ReadyJson.parsePlan("""{"plan_id":"p","lang":"hi","steps_lang":"en","steps":["a"]}""")!!
        assertTrue(ReadyView.stepsInOtherLanguage(plan, "hi"))
        assertFalse(ReadyView.stepsInOtherLanguage(plan, "en"))
        assertTrue(ReadyView.checklistInOtherLanguage(pack(w(), checklistLang = "en"), "hi"))
        assertFalse(ReadyView.checklistInOtherLanguage(pack(w(), checklistLang = "hi"), "hi"))
    }

    @Test fun theFooterSaysVerifiedForPublishedListsAndDemoOtherwise() {
        assertEquals(ReadyView.ShelterFooter.VERIFIED, ReadyView.shelterFooter(pack(w(), "published")))
        assertEquals(ReadyView.ShelterFooter.DEMO, ReadyView.shelterFooter(pack(w(), "demo")))
        assertEquals(ReadyView.ShelterFooter.NONE, ReadyView.shelterFooter(pack(w(), "published", shelters = false)))
    }
}
