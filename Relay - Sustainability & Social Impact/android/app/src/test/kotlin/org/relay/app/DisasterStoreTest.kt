package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.triage.DisasterContext
import org.relay.core.triage.DisasterType

class DisasterStoreTest {
    private val now = 1_800_000_000L
    private fun d(t: DisasterType, ageH: Long, area: DisasterContext.Area? = null) = DisasterContext(t, now - ageH * 3600, area)

    @Test fun theNewestDeclarationPerTypeWins() {
        val old = d(DisasterType.FLOOD, 5)
        val newer = d(DisasterType.FLOOD, 1)
        assertEquals(listOf(newer), DisasterStore.withDeclaration(listOf(old), newer, now))
        assertEquals(listOf(newer), DisasterStore.withDeclaration(listOf(newer), old, now)) // a late-arriving older one changes nothing
    }

    @Test fun differentDisastersCanBeActiveTogether() {
        val l = DisasterStore.withDeclaration(listOf(d(DisasterType.FLOOD, 2)), d(DisasterType.LANDSLIDE, 1), now)
        assertEquals(setOf(DisasterType.FLOOD, DisasterType.LANDSLIDE), l.map { it.type }.toSet())
    }

    @Test fun anythingPastTwentyFourHoursIsDroppedOnWrite() {
        val l = DisasterStore.withDeclaration(listOf(d(DisasterType.CYCLONE, 23)), d(DisasterType.FLOOD, 1), now + 3 * 3600)
        assertEquals(listOf(DisasterType.FLOOD), l.map { it.type })
    }

    @Test fun clearedEndsEarlierDeclarationsOfThatTypeOnly() {
        val l = listOf(d(DisasterType.FLOOD, 6), d(DisasterType.CYCLONE, 6))
        assertEquals(listOf(DisasterType.CYCLONE), DisasterStore.afterCleared(l, DisasterType.FLOOD, now - 3600).map { it.type })
        // a declaration issued after the "cleared" survives it
        assertEquals(1, DisasterStore.afterCleared(listOf(d(DisasterType.FLOOD, 0)), DisasterType.FLOOD, now - 3600).size)
    }

    @Test fun encodingKeepsTheArea() {
        val l = listOf(d(DisasterType.FLOOD, 1, DisasterContext.Area(2029612, 8582451, 40)), d(DisasterType.CYCLONE, 2))
        assertEquals(l, DisasterStore.decode(DisasterStore.encode(l)))
        assertTrue(DisasterStore.decode("flood:1:2:3").isEmpty()) // wrong field count
    }

    @Test fun anAllClearSwitchesOffWhateverOrderTheBroadcastsArriveIn() {
        val flood = d(DisasterType.FLOOD, 2)
        val cleared = DisasterStore.withCleared(emptyMap(), DisasterType.FLOOD, now - 3600, now)
        // the declaration reaches this phone after its all-clear (another neighbour, the start-up replay): ignored
        assertEquals(emptyList<DisasterContext>(), DisasterStore.withDeclaration(emptyList(), flood, now, cleared))
        // declared again after the all-clear: on again
        val again = d(DisasterType.FLOOD, 0)
        assertEquals(listOf(again), DisasterStore.withDeclaration(emptyList(), again, now, cleared))
        // other disasters are not touched
        assertEquals(1, DisasterStore.withDeclaration(emptyList(), d(DisasterType.CYCLONE, 2), now, cleared).size)
    }

    @Test fun allClearsKeepTheLatestPerTypeAndAreForgottenAfterADay() {
        var m = DisasterStore.withCleared(emptyMap(), DisasterType.FLOOD, now - 100, now)
        m = DisasterStore.withCleared(m, DisasterType.FLOOD, now - 500, now)
        assertEquals(now - 100, m[DisasterType.FLOOD])
        assertEquals(m, DisasterStore.decodeCleared(DisasterStore.encodeCleared(m)))
        assertTrue(DisasterStore.withCleared(m, DisasterType.CYCLONE, now + 25 * 3600, now + 25 * 3600).keys == setOf(DisasterType.CYCLONE))
    }
}
