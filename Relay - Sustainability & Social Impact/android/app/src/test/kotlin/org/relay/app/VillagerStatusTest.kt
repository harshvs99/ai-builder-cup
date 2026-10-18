package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.VillagerStatus.Dot
import org.relay.app.VillagerStatus.Kind
import org.relay.app.gateway.SyncHealth

class VillagerStatusTest {
    private val now = 1_000_000L
    private fun peer(i: Int, gw: Boolean = false) = PeerInfo("link$i", "n$i", gw, if (gw) 1 else 0xFF)
    private fun status(
        bt: Boolean = true, peers: Int = 0, gateway: Boolean = false, hops: Int = 0xFF,
        health: SyncHealth = SyncHealth(),
    ) = MeshStatus(
        serviceRunning = true, bluetoothOn = bt, peers = List(peers) { peer(it) }, isGateway = gateway,
        hopsToGateway = hops, syncHealth = health, lastSyncOkAtMs = health.lastOkAtMs,
    )

    @Test fun bluetoothOffWinsOverEverything() {
        val v = VillagerStatus.from(status(bt = false, peers = 3, gateway = true, hops = 1), now)
        assertEquals(Kind.BLUETOOTH_OFF, v.kind)
        assertEquals(Dot.GREY, v.dot)
    }

    @Test fun knownGatewayIsReachableWithHops() {
        val v = VillagerStatus.from(status(peers = 2, hops = 2), now)
        assertEquals(Kind.REACHABLE, v.kind)
        assertEquals(Dot.GREEN, v.dot)
        assertEquals(2, v.hopsToGateway)
        assertEquals(2, v.peers)
        assertTrue(v.isReachable)
    }

    @Test fun thisPhoneGatewayWithGoodSyncIsGreenAndAgeIsTimeSinceSync() {
        val ok = SyncHealth().record(true, null, now - 180_000, "http://x:1")
        val v = VillagerStatus.from(status(gateway = true, health = ok), now)
        assertEquals(Kind.THIS_PHONE_GATEWAY, v.kind)
        assertEquals(Dot.GREEN, v.dot)
        assertEquals(180_000L, v.syncedAgoMs)
        assertNull(v.hopsToGateway)
    }

    @Test fun gatewayThatHasNotSyncedYetIsStillSending() {
        val v = VillagerStatus.from(status(gateway = true), now)
        assertEquals(Kind.THIS_PHONE_GATEWAY, v.kind)
        assertNull(v.syncedAgoMs)
    }

    @Test fun gatewayWhoseBackendFailsIsWaitingAmber() {
        val bad = SyncHealth().record(false, "timeout", now - 1000, "http://x:1")
        val v = VillagerStatus.from(status(peers = 1, gateway = true, health = bad), now)
        assertEquals(Kind.WAITING_FOR_GATEWAY, v.kind)
        assertEquals(Dot.AMBER, v.dot)
    }

    @Test fun peersButNoGatewayIsWaiting() {
        val v = VillagerStatus.from(status(peers = 3), now)
        assertEquals(Kind.WAITING_FOR_GATEWAY, v.kind)
        assertEquals(Dot.AMBER, v.dot)
        assertEquals(3, v.peers)
    }

    @Test fun noPeersIsSearching() {
        val v = VillagerStatus.from(status(), now)
        assertEquals(Kind.SEARCHING, v.kind)
        assertEquals(Dot.GREY, v.dot)
    }
}
