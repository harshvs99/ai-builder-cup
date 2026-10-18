package org.relay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.store.MyNeed
import org.relay.app.store.MyNeedsStore
import org.relay.app.store.NeedStatus
import org.relay.app.store.NeedStatusLogic
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import java.io.File

class MyNeedJsonTest {
    /** A my_needs.json entry exactly as written before the redesign. */
    private val legacy = """{"id":"aa11bb22","at":1700000000000,"cat":2,"urg":3,"ppl":6,"item":"water","lm":"school",
        "sum":"pump broken","lang":1,"need":"QUJD","sent":true,"ack":1,"ticket":42,"atext":"Help dispatched","eta":45,"rtext":"Received"}"""

    @Test fun legacyFileLoadsWithDefaults() {
        val n = MyNeed.fromJson(JSONObject(legacy))
        assertNotNull(n)
        n!!
        assertEquals("aa11bb22", n.msgIdHex)
        assertEquals(Category.WATER, n.category)
        assertEquals(Lang.HI, n.lang)
        assertTrue(n.sentToMesh)
        assertEquals(1, n.ackStatus)
        assertEquals(MyNeed.Stage.DISPATCHED, n.stage)
        assertEquals(0, n.vulnFlags)
        assertTrue(n.answers.isEmpty())
        assertEquals(1700000000000L, n.savedAtMs) // defaults to the created time
        assertEquals(0L, n.sentToMeshAtMs)
        assertEquals(0L, n.uploadedAtMs)
        assertEquals(0L, n.resolvedAtMs)
        assertFalse(n.cancelled)
        assertFalse(n.isClosed)
    }

    @Test fun newFieldsRoundTrip() {
        val n = MyNeed(
            msgIdHex = "cc", createdAtMs = 5, category = Category.RESCUE, urgency = 3, people = 2, item = "rescue",
            landmark = "temple", summary = "roof", lang = Lang.OR, needPayloadB64 = "x", vulnFlags = 18,
            answers = mapOf(3 to 1, 4 to 2), sentToMeshAtMs = 6, uploadedAtMs = 7, receivedAtMs = 8, dispatchedAtMs = 9,
            onTheWayAtMs = 10, resolvedAtMs = 11, etaAtMs = 12, cancelled = true, cancelledAtMs = 13, ackStatus = 3,
        )
        val back = MyNeed.fromJson(JSONObject(n.toJson().toString()))
        assertEquals(n, back)
        assertEquals(mapOf(3 to 1, 4 to 2), back!!.answers)
    }

    @Test fun ackStatusesOnTheWayAndResolvedAreStages() {
        val base = MyNeed.fromJson(JSONObject(legacy))!!
        val otw = base.withAck(AckStatus.ON_THE_WAY, 42, "on the way", 10, nowMs = 1_000)
        assertEquals(MyNeed.Stage.ON_THE_WAY, otw.stage)
        assertEquals(1_000L, otw.onTheWayAtMs)
        assertEquals(NeedStatus.ON_THE_WAY, NeedStatusLogic.derive(otw, false, true, true))
        val done = otw.withAck(AckStatus.RESOLVED, 42, "delivered", 0xFFFF, nowMs = 2_000)
        assertEquals(MyNeed.Stage.RESOLVED, done.stage)
        assertEquals(NeedStatus.RESOLVED, NeedStatusLogic.derive(done, false, true, true))
        // A late lower ACK never moves it back.
        val late = done.withAck(AckStatus.DISPATCHED, 42, "old", 5, nowMs = 3_000)
        assertEquals(MyNeed.Stage.RESOLVED, late.stage)
        assertEquals("delivered", late.ackText)
    }

    @Test fun storeLoadsLegacyFileAndPersistsCancel() {
        val f = File.createTempFile("my_needs", ".json").apply { deleteOnExit() }
        f.writeText("[$legacy]")
        val s = MyNeedsStore(f)
        assertEquals(1, s.list().size)
        s.update("aa11bb22") { it.cancel(99) }
        val again = MyNeedsStore(f).list().single()
        assertTrue(again.cancelled)
        assertEquals(99L, again.cancelledAtMs)
        assertEquals(1, again.ackStatus) // everything else survives
    }
}
