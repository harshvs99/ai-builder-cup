package org.relay.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.relay.app.store.MyNeed
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.request.RequestLogic
import org.relay.app.ui.request.timelineFor
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import java.util.TimeZone

class RequestDetailLogicTest {
    private val savedZone: TimeZone = TimeZone.getDefault()
    @Before fun pinZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restoreZone() { TimeZone.setDefault(savedZone) }

    private val t0 = 1_700_000_000_000L
    private fun need(id: String = "7f3a1c20") = MyNeed(
        msgIdHex = id, createdAtMs = t0, category = Category.WATER, urgency = 2, people = 6, item = "Drinking water",
        landmark = "school", summary = "pump", lang = Lang.EN, needPayloadB64 = "",
    )

    private val received = need().withAck(AckStatus.RECEIVED, 42, "We have it.", 0xFFFF, t0 + 1_000)
    private val dispatched = received.withAck(AckStatus.DISPATCHED, 42, "Help dispatched, ETA 45 min", 45, t0 + 2_000)

    @Test fun referenceUsesMsgIdUntilTicketKnown() {
        assertEquals("R-7F3A", RequestLogic.reference(need()))
        assertEquals("#42", RequestLogic.reference(received))
        assertEquals("R-AB", RequestLogic.reference(need("ab")))
    }

    @Test fun titleUsesItemElseCategory() {
        val c = stringsFor(Lang.EN).common
        assertEquals("Drinking water · 6 people", RequestLogic.title(need(), c))
        assertEquals("Water · 1 person", RequestLogic.title(need().copy(item = "", people = 1), c))
    }

    @Test fun shareTextEnglish() {
        val text = RequestLogic.shareText(dispatched, stringsFor(Lang.EN), "Ward 4", t0 + 3_000)
        val lines = text.lines()
        assertEquals("Relay request #42", lines[0])
        assertEquals("Drinking water · 6 people (Urgent)", lines[1])
        assertTrue(lines[2].startsWith("#42 · sent "))
        assertTrue(lines[2].endsWith(" · Ward 4"))
        assertTrue("Near school" in lines)
        assertTrue(lines.any { it.startsWith("Status: Help is coming · Expected by ") })
        assertEquals("Control room: Help dispatched, ETA 45 min", lines.last())
    }

    @Test fun shareTextFollowsLanguageAndOmitsEmptyParts() {
        val text = RequestLogic.shareText(need().copy(landmark = ""), stringsFor(Lang.HI), "", t0)
        val lines = text.lines()
        assertTrue(lines[0].startsWith("Relay अनुरोध R-7F3A"))
        assertFalse(lines[2].endsWith("·"))
        assertFalse(lines.any { it.contains("के पास") })
        assertFalse(lines.any { it.startsWith("कंट्रोल रूम:") })
    }

    @Test fun shareTextCancelled() {
        val text = RequestLogic.shareText(need().cancel(t0 + 5), stringsFor(Lang.EN), "", t0 + 10)
        assertTrue("Status: Cancelled" in text.lines())
    }

    @Test fun autoReadOncePerNewMessage() {
        assertFalse("no message yet", RequestLogic.shouldAutoRead(true, need(), emptySet()))
        assertTrue(RequestLogic.shouldAutoRead(true, received, emptySet()))
        assertFalse("setting off", RequestLogic.shouldAutoRead(false, received, emptySet()))
        val read = setOf(RequestLogic.messageKey(received))
        assertFalse("already read", RequestLogic.shouldAutoRead(true, received, read))
        assertTrue("newer ack is a new message", RequestLogic.shouldAutoRead(true, dispatched, read))
        assertFalse("blank text", RequestLogic.shouldAutoRead(true, received.copy(ackText = ""), emptySet()))
    }

    @Test fun messageKeyDiffersPerRequest() {
        assertFalse(RequestLogic.messageKey(received) == RequestLogic.messageKey(received.copy(msgIdHex = "0000ffff")))
    }

    @Test fun cancelledTimelineEndsWithCancelledRow() {
        val s = stringsFor(Lang.EN)
        val rows = timelineFor(received.cancel(t0 + 5_000), t0 + 6_000, s)
        assertEquals("Cancelled", rows.last().title)
        assertEquals(rows.size, rows.count { it.state == org.relay.app.ui.components.TimelineState.Done })
        assertEquals("Seen by the control room", rows[rows.size - 2].title)
    }

    @Test fun liveTimelineShowsLiveTagAndEta() {
        val s = stringsFor(Lang.EN)
        val n = dispatched.withAck(AckStatus.ON_THE_WAY, 42, "On the way", 30, t0 + 3_000)
        val last = timelineFor(n, t0 + 4_000, s).last()
        assertEquals("Team on the way", last.title)
        assertEquals("LIVE", last.tag)
        assertTrue(last.subtitle!!.contains("Expected by"))
    }

    @Test fun expectedByOnlyOnCurrentStep() {
        val s = stringsFor(Lang.EN)
        val n = dispatched.withAck(AckStatus.ON_THE_WAY, 42, "On the way", 30, t0 + 3_000)
        val rows = timelineFor(n, t0 + 4_000, s)
        assertEquals(1, rows.count { it.subtitle?.contains("Expected by") == true })
        assertTrue(rows.last().subtitle!!.contains("Expected by"))
    }
}
