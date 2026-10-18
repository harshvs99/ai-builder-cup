package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.ui.ask.NoLocationLogic
import org.relay.app.ui.ask.NoLocationReason
import org.relay.app.ui.ask.TurnOnAction
import org.relay.app.ui.i18n.stringsFor
import org.relay.core.payload.Lang

class NoLocationLogicTest {
    private fun prompt(perm: Boolean, services: Boolean, fix: Boolean, waiting: Boolean, empty: Boolean) =
        NoLocationLogic.promptOnSend(perm, services, fix, waiting, empty)

    @Test fun reasonFollowsPermissionThenServicesThenFinding() {
        assertNull(NoLocationLogic.reason(true, true, true))
        assertNull(NoLocationLogic.reason(false, false, true))
        assertEquals(NoLocationReason.PERMISSION_OFF, NoLocationLogic.reason(false, true, false))
        assertEquals(NoLocationReason.PERMISSION_OFF, NoLocationLogic.reason(false, false, false))
        assertEquals(NoLocationReason.SERVICES_OFF, NoLocationLogic.reason(true, false, false))
        assertEquals(NoLocationReason.FINDING, NoLocationLogic.reason(true, true, false))
    }

    @Test fun noDialogWithFixOrWhileWaiting() {
        assertNull(prompt(true, true, fix = true, waiting = false, empty = true))
        assertNull(prompt(true, true, fix = false, waiting = true, empty = true))
        assertNull(prompt(false, false, fix = true, waiting = true, empty = false))
    }

    @Test fun permissionMissingRequestsPermission() {
        val p = prompt(false, true, false, false, empty = false)!!
        assertEquals(NoLocationReason.PERMISSION_OFF, p.reason)
        assertEquals(TurnOnAction.REQUEST_PERMISSION, p.turnOn)
    }

    @Test fun servicesOffOpensLocationSettings() {
        assertEquals(TurnOnAction.OPEN_LOCATION_SETTINGS, prompt(true, false, false, false, empty = true)!!.turnOn)
    }

    @Test fun permissionAndServicesButNoFixRetries() {
        assertEquals(TurnOnAction.RETRY_FIX, prompt(true, true, false, false, empty = true)!!.turnOn)
    }

    @Test fun landmarkDecidesBodyLabelAndConfirmation() {
        val withLandmark = prompt(true, false, false, false, empty = false)!!
        assertTrue(withLandmark.bodyWithLandmark)
        assertFalse(withLandmark.addLandmark)
        assertFalse(withLandmark.confirmSendAnyway)
        val without = prompt(true, false, false, false, empty = true)!!
        assertFalse(without.bodyWithLandmark)
        assertTrue(without.addLandmark)
        assertTrue(without.confirmSendAnyway)
    }

    @Test fun everyNoLocationStringExistsInAllFiveLanguages() {
        val en = stringsFor(Lang.EN).ask
        assertEquals("Rescuers can't see where you are", en.noLocationTitle)
        assertEquals("Location permission is off", en.whereReasonPermissionOff)
        assertEquals("Location is turned off on this phone", en.whereReasonServicesOff)
        assertEquals("Still finding your location…", en.whereReasonFinding)
        assertEquals("They will rely on 'school'. Adding your location helps them find you faster.", en.noLocationBodyWithLandmark("school"))
        for (l in listOf(Lang.HI, Lang.OR, Lang.TL, Lang.WAR)) {
            val a = stringsFor(l).ask
            val texts = listOf(
                a.noLocationTitle, a.noLocationBodyNoLandmark, a.noLocationBodyWithLandmark("x"), a.turnOnLocation, a.addLandmark,
                a.changeLandmark, a.sendWithoutLocation, a.sendAnywayConfirm, a.whereReasonPermissionOff, a.whereReasonServicesOff,
                a.whereReasonFinding, a.noLocationWarningA11y,
            )
            texts.forEach { assertTrue(it.isNotBlank()) }
            assertNotEquals(en.noLocationTitle, a.noLocationTitle)
            assertNotNull(a.noLocationBodyWithLandmark("x"))
        }
    }
}
