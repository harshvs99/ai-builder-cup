package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.network.CarryPolicy
import org.relay.app.ui.network.ChainModel
import org.relay.app.ui.network.ChainNode
import org.relay.app.ui.network.ChainNode.Kind
import org.relay.app.ui.network.GatewayChoice
import org.relay.app.ui.network.MeshHoursText
import org.relay.app.ui.network.NetworkStats
import org.relay.app.ui.network.PermissionWord
import org.relay.core.payload.Lang

class NetworkLogicTest {
    private fun vs(kind: VillagerStatus.Kind, hops: Int? = null) = VillagerStatus(kind, VillagerStatus.Dot.GREEN, 2, hops, null)
    private fun peers(n: Int) = List(n) { PeerInfo("l$it", "n$it", false, 1) }

    // ---- chain collapse
    @Test fun chainTwoHopsShowsIntermediate() {
        val c = ChainModel.from(vs(VillagerStatus.Kind.REACHABLE, 2))
        assertEquals(listOf(ChainNode(Kind.YOU), ChainNode(Kind.HOP, 1), ChainNode(Kind.GATEWAY, 2)), c)
    }

    @Test fun chainOneHopCollapsesToYouAndGateway() {
        val c = ChainModel.from(vs(VillagerStatus.Kind.REACHABLE, 1))
        assertEquals(listOf(ChainNode(Kind.YOU), ChainNode(Kind.GATEWAY, 1)), c)
    }

    @Test fun chainFarGatewayUsesOneIntermediate() {
        val c = ChainModel.from(vs(VillagerStatus.Kind.REACHABLE, 4))
        assertEquals(3, c.size)
        assertEquals(ChainNode(Kind.HOP, 3), c[1])
        assertEquals(ChainNode(Kind.GATEWAY, 4), c[2])
    }

    @Test fun chainThisPhoneGatewayIsOnlyYou() {
        assertEquals(listOf(ChainNode(Kind.YOU_GATEWAY)), ChainModel.from(vs(VillagerStatus.Kind.THIS_PHONE_GATEWAY)))
    }

    @Test fun chainNoGatewayShowsQuestionMark() {
        for (k in listOf(VillagerStatus.Kind.WAITING_FOR_GATEWAY, VillagerStatus.Kind.SEARCHING, VillagerStatus.Kind.BLUETOOTH_OFF)) {
            assertEquals(listOf(ChainNode(Kind.YOU), ChainNode(Kind.UNKNOWN)), ChainModel.from(vs(k)))
        }
    }

    @Test fun chainFromRealStatus() {
        val st = MeshStatus(bluetoothOn = true, peers = peers(2), hopsToGateway = 2)
        val c = ChainModel.from(VillagerStatus.from(st, 0))
        assertEquals(listOf(Kind.YOU, Kind.HOP, Kind.GATEWAY), c.map { it.kind })
    }

    // ---- stat derivations
    @Test fun statsCarriedExcludesOwnFrames() {
        val st = MeshStatus(peers = peers(3), storedNeeds = 5, storedAcks = 4)
        val s = NetworkStats.from(st, ownStored = 2, ownDelivered = 0)
        assertEquals(3, s.phonesNearby)
        assertEquals(7, s.carriedForNeighbours)
    }

    @Test fun statsCarriedNeverNegative() {
        val st = MeshStatus(storedNeeds = 1, storedAcks = 0)
        assertEquals(0, NetworkStats.from(st, ownStored = 3, ownDelivered = 0).carriedForNeighbours)
    }

    @Test fun statsWaitingForGatewayIsPendingUploads() {
        val st = MeshStatus(isGateway = true, storedNeeds = 9, pendingUploads = 2)
        assertEquals(2, NetworkStats.from(st, ownStored = 0, ownDelivered = 0).waitingToPassOn)
    }

    @Test fun statsWaitingForOrdinaryPhoneSkipsDeliveredOwn() {
        val st = MeshStatus(isGateway = false, storedNeeds = 5, pendingUploads = 99)
        assertEquals(3, NetworkStats.from(st, ownStored = 2, ownDelivered = 2).waitingToPassOn)
        assertEquals(0, NetworkStats.from(MeshStatus(storedNeeds = 1), ownStored = 1, ownDelivered = 2).waitingToPassOn)
    }

    // ---- battery / mesh hours
    @Test fun meshHoursRoundDown() {
        val s = NetworkStats.from(MeshStatus(meshHoursRemaining = 38.9), 0, 0)
        assertEquals(38, s.meshHoursLeft)
        assertEquals(MeshHoursText.Value.Hours(38), MeshHoursText.of(s))
    }

    @Test fun meshHoursStates() {
        assertEquals(MeshHoursText.Value.Unknown, MeshHoursText.of(NetworkStats.from(MeshStatus(meshHoursRemaining = null), 0, 0)))
        assertEquals(MeshHoursText.Value.UnderOne, MeshHoursText.of(NetworkStats.from(MeshStatus(meshHoursRemaining = 0.4), 0, 0)))
        // Charging wins even if an estimate were present.
        assertEquals(
            MeshHoursText.Value.Charging,
            MeshHoursText.of(NetworkStats.from(MeshStatus(charging = true, meshHoursRemaining = null), 0, 0)),
        )
    }

    @Test fun meshHoursFromEnergyModel() {
        // 60 % battery at the NORMAL duty mode: 60 / (1.0 + 0.4 + 2.6 * 0.2) = 31.25 h.
        val h = org.relay.app.ble.MeshEnergy.hoursRemaining(60, false, org.relay.app.ble.DutyCycle.Mode.NORMAL)!!
        assertEquals(31, NetworkStats.from(MeshStatus(meshHoursRemaining = h), 0, 0).meshHoursLeft)
    }

    // ---- permission words
    @Test fun permissionWords() {
        assertEquals(PermissionWord.ALLOWED, PermissionWord.of(granted = true, askedWhenNeeded = true))
        assertEquals(PermissionWord.ALLOWED, PermissionWord.of(granted = true, askedWhenNeeded = false))
        assertEquals(PermissionWord.ASK_EACH_TIME, PermissionWord.of(granted = false, askedWhenNeeded = true))
        assertEquals(PermissionWord.NOT_ALLOWED, PermissionWord.of(granted = false, askedWhenNeeded = false))
    }

    // ---- gateway and carry
    @Test fun gatewayChoiceHidesAlways() {
        assertEquals(listOf(GatewayMode.AUTOMATIC, GatewayMode.NEVER), GatewayChoice.options)
        assertEquals(GatewayMode.AUTOMATIC, GatewayChoice.effective(GatewayMode.ALWAYS))
        assertTrue(GatewayChoice.isOn(GatewayMode.ALWAYS))
        assertFalse(GatewayChoice.isOn(GatewayMode.NEVER))
        assertEquals(GatewayMode.NEVER, GatewayChoice.fromToggle(false))
        assertEquals(GatewayMode.AUTOMATIC, GatewayChoice.fromToggle(true))
    }

    @Test fun carryPolicy() {
        assertFalse(CarryPolicy.stopWhenBackgrounded(Settings(carryInBackground = true), changingConfigurations = false))
        assertTrue(CarryPolicy.stopWhenBackgrounded(Settings(carryInBackground = false), changingConfigurations = false))
        assertFalse(CarryPolicy.stopWhenBackgrounded(Settings(carryInBackground = false), changingConfigurations = true))
    }

    // ---- strings
    @Test fun everyStatusKindHasASentenceInEveryLanguage() {
        for (lang in listOf(Lang.EN, Lang.HI, Lang.OR, Lang.TL, Lang.WAR)) {
            val s = stringsFor(lang).network
            for (k in VillagerStatus.Kind.entries) {
                assertTrue("$lang $k", s.statusSentence(vs(k, 2)).isNotBlank())
            }
            assertTrue(s.chainDescription(listOf("a", "b")).contains("a"))
        }
    }

    @Test fun englishChainCaptionsMatchSpec() {
        val s = stringsFor(Lang.EN).network
        assertEquals("Gateway · 2 hops", s.chainGateway(2))
        assertEquals("1 hop", s.chainHop(1))
    }
}
