package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.PushPlanner
import org.relay.core.gossip.StoredPacket
import org.relay.core.ids.NodeId
import org.relay.core.payload.Announcement
import org.relay.core.payload.AnnouncementCodec
import org.relay.core.payload.AnnouncementDistricts
import org.relay.core.payload.AnnouncementStatus
import org.relay.core.payload.Lang

/** ANNOUNCEMENT (SPEC 5.7): codec, then gossip. Mirrored by Swift `AnnouncementTests`; the golden vectors are in VectorsTest. */
class AnnouncementTest {
    private val hour = 3600_000L
    private val id = byteArrayOf(0x5a, 0x5b, 0x5c, 0x5d, 0x5e, 0x5f, 0x60, 0x61)

    private fun ann(
        lang: Lang = Lang.EN, status: AnnouncementStatus = AnnouncementStatus.ACTIVE, district: Int = 0,
        approvedAtMs: Long = T0, expiresAt: Long = 0, text: String = "Shelters are open", announcementId: ByteArray = id,
    ) = Announcement(announcementId, lang, district, status, approvedAtMs / 1000, expiresAt, text)

    private fun frame(a: Announcement, ttl: Int = 7, ts: Long = T0): ByteArray =
        FrameCodec.encode(Frame(type = FrameTypes.ANNOUNCEMENT, ttl = ttl, timestampMs = ts, senderId = NodeId.random(), payload = AnnouncementCodec.encode(a)))

    // ------------------------------------------------------------ codec
    @Test fun codecRoundTripsAndTheLargestFrameIsOneWrite() {
        for (l in Lang.entries) for (st in AnnouncementStatus.entries) {
            val a = ann(lang = l, status = st, district = 1, expiresAt = 1_790_086_400L, text = "पुरी में शरण स्थल खुले हैं।")
            assertEquals(a, AnnouncementCodec.decode(AnnouncementCodec.encode(a)))
        }
        val biggest = ann(text = "a".repeat(139), approvedAtMs = 0xFFFFFFFFL * 1000, expiresAt = 0xFFFFFFFFL)
        val payload = AnnouncementCodec.encode(biggest)
        assertEquals(160, payload.size)
        assertEquals(160, AnnouncementCodec.MAX_PAYLOAD)
        assertEquals(182, 22 + payload.size) // exactly SPEC 2's single-write limit (MTU 185 - 3)
        assertEquals(biggest, AnnouncementCodec.decode(payload))
        // an empty text is allowed (a WITHDRAWN frame may carry none)
        assertEquals(ann(text = ""), AnnouncementCodec.decode(AnnouncementCodec.encode(ann(text = ""))))
    }

    @Test fun encoderCutsALongTextOnACharacterBoundary() {
        val p = AnnouncementCodec.encode(ann(lang = Lang.HI, text = "अ".repeat(100)))
        assertEquals(20 + 1 + 138, p.size)
        assertEquals("अ".repeat(46), AnnouncementCodec.decode(p)!!.text)
    }

    @Test fun decoderRejectsWhatTheSpecSaysToDrop() {
        val good = AnnouncementCodec.encode(ann())
        assertNotNull(AnnouncementCodec.decode(good))
        assertNull(AnnouncementCodec.decode(good.copyOf().also { it[0] = 2 }))                 // version
        assertNull(AnnouncementCodec.decode(good.copyOf(20)))                                    // no text length byte
        assertNull(AnnouncementCodec.decode(good + byteArrayOf(0)))                              // trailing byte
        assertNull(AnnouncementCodec.decode(good.copyOf(good.size - 1)))                         // text runs past the end
        assertNull(AnnouncementCodec.decode(good.copyOf().also { it[9] = 6 }))                   // lang 6
        assertNull(AnnouncementCodec.decode(good.copyOf().also { it[11] = 2 }))                  // status 2
        assertNull(AnnouncementCodec.decode(good.copyOf(20) + byteArrayOf(140.toByte()) + ByteArray(140) { 'a'.code.toByte() })) // text over 139
        assertNull(AnnouncementCodec.decode(good.copyOf(20) + byteArrayOf(2, 0xC3.toByte(), 0x28)))                            // invalid UTF-8
    }

    @Test fun anUnknownDistrictStillDecodesSoThePhoneCanRelayIt() {
        val a = ann(district = 200)
        assertEquals(a, AnnouncementCodec.decode(AnnouncementCodec.encode(a)))
        assertNull(AnnouncementDistricts.idOf(200))
        assertEquals("delhi", AnnouncementDistricts.idOf(1))
        assertEquals(0, AnnouncementDistricts.indexOf(" Puri "))
        assertNull(AnnouncementDistricts.indexOf("nowhere"))
    }

    @Test fun encoderRefusesInvalidFields() {
        for (bad in listOf(ann(announcementId = ByteArray(7)), ann(district = 256), ann(district = -1), ann().copy(approvedAt = -1), ann().copy(expiresAt = 1L shl 32))) {
            try { AnnouncementCodec.encode(bad); error("expected IllegalArgumentException for $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun theDedupeKeyIsDerivedSoAWithdrawalIsANewFrameThatSupersedes() {
        val active = ann(); val withdrawn = ann(status = AnnouncementStatus.WITHDRAWN); val hindi = ann(lang = Lang.HI)
        assertEquals(16, active.keyHex.length)
        assertEquals(3, setOf(active.keyHex, withdrawn.keyHex, hindi.keyHex).size)
        assertEquals(active.keyHex, ann(text = "other words", approvedAtMs = T0 + 5000).keyHex) // the text is not part of the key
        assertNotEquals(active.keyHex, ann(announcementId = ByteArray(8)).keyHex)
    }

    @Test fun lifeIsSeventyTwoHoursAndEndsAtExpiresAt() {
        val nowSec = T0 / 1000
        assertTrue(ann().isLive(nowSec))
        assertTrue(ann().isLive(nowSec + 72 * 3600))
        assertFalse(ann().isLive(nowSec + 72 * 3600 + 1))
        assertTrue(ann(expiresAt = nowSec + 100).isLive(nowSec))
        assertFalse(ann(expiresAt = nowSec + 100).isLive(nowSec + 100))
        assertTrue(ann(approvedAtMs = T0 + 3600_000).isLive(nowSec)) // the phone's clock may be behind
    }

    // ------------------------------------------------------------ gossip
    @Test fun aGatewayOriginatesAndEveryPhoneStoresAndRelaysIt() {
        val net = TestNet()
        net.chain(listOf("gw", "a", "b", "c"))
        val en = ann(); val hi = ann(lang = Lang.HI, text = "शरण स्थल खुले हैं")
        assertNotNull(net.node("gw").node.originateAnnouncement(en))
        assertNotNull(net.node("gw").node.originateAnnouncement(hi))
        net.runUntilIdle()
        for (n in listOf("gw", "a", "b", "c")) {
            assertEquals(n, setOf(en, hi), net.node(n).announcements.toSet())
            assertEquals(n, 2, net.node(n).node.storedAnnouncements().size)
        }
        assertEquals(2, net.node("c").node.stats().announcementsDelivered)
    }

    @Test fun originatingTheSamePayloadAgainIsFreeAndAWithdrawalIsNew() {
        val net = TestNet()
        net.chain(listOf("g1", "a", "g2"))
        net.node("g1").node.originateAnnouncement(ann())
        net.runUntilIdle()
        assertNull(net.node("g1").node.originateAnnouncement(ann()))
        assertNull(net.node("g2").node.originateAnnouncement(ann())) // already holds it from the mesh
        val w = ann(status = AnnouncementStatus.WITHDRAWN)
        assertNotNull(net.node("g2").node.originateAnnouncement(w))
        net.runUntilIdle()
        assertEquals(listOf(AnnouncementStatus.ACTIVE, AnnouncementStatus.WITHDRAWN), net.node("a").announcements.map { it.status })
        assertEquals(1, net.node("g1").announcements.count { it.status == AnnouncementStatus.ACTIVE })
    }

    @Test fun anOfflinePhoneThatLaterMeetsACarrierGetsIt() {
        val net = TestNet()
        net.chain(listOf("gw", "carrier"))
        net.node("gw").node.originateAnnouncement(ann())
        net.runUntilIdle()
        net.disconnect("gw", "carrier")
        net.node("far")
        net.connect("carrier", "far")
        net.runUntilIdle()
        assertEquals(1, net.node("far").announcements.size)
    }

    @Test fun everyPhoneRelaysAnAnnouncementOfAnotherDistrictAndLanguage() {
        val net = TestNet()
        net.chain(listOf("gw", "a", "b"))
        net.node("gw").node.originateAnnouncement(ann(lang = Lang.OR, district = 1))
        net.runUntilIdle()
        assertEquals(1, net.node("b").announcements.size) // stored and relayed; whether it is shown is the board's rule
    }

    @Test fun anUnknownDistrictIsStoredAndRelayed() {
        val net = TestNet()
        net.chain(listOf("a", "b", "c"))
        net.node("a").node.onFrame("b", frame(ann(district = 77)))
        net.runUntilIdle()
        assertEquals(1, net.node("a").announcements.size)
        assertEquals(1, net.node("c").announcements.size)
    }

    @Test fun staleOrEndedAnnouncementsAreNeitherStoredNorRelayed() {
        val net = TestNet()
        net.chain(listOf("a", "b", "c"))
        val old = ann(approvedAtMs = T0 - 73 * hour)
        val ended = ann(lang = Lang.HI, expiresAt = T0 / 1000 - 5)
        net.node("a").node.onFrame("b", frame(old))
        net.node("a").node.onFrame("b", frame(ended))
        net.runUntilIdle()
        assertTrue(net.node("a").announcements.isEmpty())
        assertTrue(net.node("a").node.storedAnnouncements().isEmpty())
        assertTrue(net.node("c").announcements.isEmpty())
        assertNull(net.node("a").node.originateAnnouncement(old))
    }

    @Test fun aStoredAnnouncementLeavesTheStoreSeventyTwoHoursAfterFirstStorage() {
        val net = TestNet()
        net.node("a").node.originateAnnouncement(ann(approvedAtMs = T0 - 20 * hour))
        net.runUntilIdle()
        assertEquals(1, net.node("a").node.storedAnnouncements().size)
        assertEquals(T0, net.node("a").node.storedAnnouncements().single().receivedAtMs)
        net.runFor(60 * hour) // 80 h after approval: past its life even though 72 h have not passed since storage
        assertEquals(0, net.node("a").node.storedAnnouncements().size)
        net.runFor(13 * hour)
        net.node("a").node.expire()
        assertEquals(0, net.node("a").node.storedAnnouncements().size)
    }

    @Test fun aPhoneThatMissedItOnTheFloodGetsItFromTheInventoryDiff() {
        val net = TestNet()
        net.node("gw"); net.node("late")
        net.node("gw").node.originateAnnouncement(ann())
        net.runUntilIdle()
        net.connect("gw", "late")
        net.runUntilIdle()
        assertEquals(1, net.node("late").announcements.size)
    }

    @Test fun anOlderBuildRelaysAnUnknownVersionOnceAndNeverStoresIt() {
        val net = TestNet()
        net.chain(listOf("a", "b"))
        val v2 = AnnouncementCodec.encode(ann()).also { it[0] = 2 }
        val raw = FrameCodec.encode(Frame(type = FrameTypes.ANNOUNCEMENT, ttl = 7, timestampMs = T0, senderId = NodeId.random(), payload = v2))
        net.node("a").node.onFrame("b", raw)
        net.runUntilIdle()
        assertTrue(net.node("a").announcements.isEmpty())
        assertTrue(net.node("a").node.storedAnnouncements().isEmpty())
    }

    @Test fun anInvalidAnnouncementIsRejectedNotRelayed() {
        val net = TestNet()
        net.chain(listOf("a", "b"))
        val bad = AnnouncementCodec.encode(ann()).also { it[9] = 6 }
        net.node("a").node.onFrame("b", FrameCodec.encode(Frame(type = FrameTypes.ANNOUNCEMENT, ttl = 7, timestampMs = T0, senderId = NodeId.random(), payload = bad)))
        net.runUntilIdle()
        assertEquals(1, net.node("a").node.stats().framesRejected)
        assertTrue(net.node("b").announcements.isEmpty())
    }

    @Test fun aRescueRequestIsPushedBeforeAnAnnouncementAndHintsAfterIt() {
        val need = sampleNeed(NodeId.random())
        fun pkt(type: Int, key: String, basis: Long) = StoredPacket(key, type, ByteArray(10), basis)
        val order = PushPlanner.order(
            listOf(pkt(FrameTypes.LOC_HINT, "h", 5), pkt(FrameTypes.ANNOUNCEMENT, "a", 9), pkt(FrameTypes.NEED, need.msgIdHex, 1), pkt(FrameTypes.BROADCAST, "b", 1)),
            null,
        )
        assertEquals(listOf("b", need.msgIdHex, "a", "h"), order.map { it.key })
    }
}
