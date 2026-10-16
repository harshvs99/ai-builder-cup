package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.relay.app.gateway.GatewayState
import org.relay.app.store.MyNeed
import org.relay.app.store.MyNeedsStore
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import java.io.File

class StateFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun mine(id: String) = MyNeed(id, 1_000, Category.WATER, 2, 4, "water", "bridge", "need water", Lang.HI, "AAAA")

    @Test fun gatewayStateRoundTripAndCorruptFile() {
        val f = File(tmp.root, "g.json")
        val a = GatewayState(f)
        a.markUploaded(listOf("aa", "bb")); a.setAckCursor(42)
        val b = GatewayState(f)
        assertTrue(b.isUploaded("aa")); assertTrue(b.isUploaded("bb")); assertFalse(b.isUploaded("cc"))
        assertEquals(42L, b.ackCursor())

        f.writeText("{ definitely not json")
        val c = GatewayState(f)
        assertEquals(0L, c.ackCursor()); assertEquals(0, c.uploadedCount())
    }

    @Test fun gatewayStateBoundsRememberedIds() {
        val s = GatewayState(null, maxRemembered = 3)
        s.markUploaded(listOf("1", "2", "3", "4"))
        assertFalse(s.isUploaded("1")); assertTrue(s.isUploaded("4")); assertEquals(3, s.uploadedCount())
    }

    @Test fun myNeedsPersistWithAckProgressAndSurviveCorruption() {
        val f = File(tmp.root, "n.json")
        val s = MyNeedsStore(f)
        s.add(mine("m1")); s.add(mine("m2"))
        assertEquals(listOf("m2", "m1"), s.list().map { it.msgIdHex })
        assertEquals(MyNeed.Stage.QUEUED, s.list().first { it.msgIdHex == "m1" }.stage)

        s.update("m1") { it.copy(sentToMesh = true) }
        assertEquals(MyNeed.Stage.SENT, s.list().first { it.msgIdHex == "m1" }.stage)
        s.update("m1") { it.withAck(AckStatus.RECEIVED, 142, "Received", 0xFFFF) }
        assertEquals(MyNeed.Stage.RECEIVED, s.list().first { it.msgIdHex == "m1" }.stage)
        s.update("m1") { it.withAck(AckStatus.DISPATCHED, 142, "Help dispatched", 20) }

        val again = MyNeedsStore(f).list().first { it.msgIdHex == "m1" }
        assertEquals(MyNeed.Stage.DISPATCHED, again.stage)
        assertEquals(142L, again.ticketNo); assertEquals("Help dispatched", again.ackText); assertEquals("Received", again.receivedText)

        // A late RECEIVED ack must not downgrade a DISPATCHED need.
        val down = again.withAck(AckStatus.RECEIVED, 142, "Received", 0xFFFF)
        assertEquals(MyNeed.Stage.DISPATCHED, down.stage); assertEquals("Help dispatched", down.ackText)

        f.writeText("garbage")
        assertTrue(MyNeedsStore(f).list().isEmpty())
    }
}
