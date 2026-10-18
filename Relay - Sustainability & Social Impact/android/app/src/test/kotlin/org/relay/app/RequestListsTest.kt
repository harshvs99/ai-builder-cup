package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.store.MyNeed
import org.relay.app.ui.components.requestTitle
import org.relay.app.ui.components.requestTrailing
import org.relay.app.ui.home.RequestLists
import org.relay.app.ui.i18n.stringsFor
import org.relay.core.payload.Category
import org.relay.core.payload.Lang

class RequestListsTest {
    private fun need(id: String, at: Long, ack: Int = -1, cancelled: Boolean = false, item: String = "", urgency: Int = 2, people: Int = 6) = MyNeed(
        msgIdHex = id, createdAtMs = at, category = Category.WATER, urgency = urgency, people = people, item = item,
        landmark = "", summary = "", lang = Lang.EN, needPayloadB64 = "", ackStatus = ack, cancelled = cancelled,
    )

    @Test fun splitsActiveAndClosedNewestFirst() {
        val a = need("a", 100)
        val b = need("b", 300, ack = 3) // resolved
        val c = need("c", 200, cancelled = true)
        val d = need("d", 400, ack = 1)
        val (active, closed) = RequestLists.split(listOf(a, b, c, d))
        assertEquals(listOf("d", "a"), active.map { it.msgIdHex })
        assertEquals(listOf("b", "c"), closed.map { it.msgIdHex })
    }

    @Test fun recentTakesTwoNewestOpenOrClosed() {
        val needs = listOf(need("a", 1), need("b", 3, ack = 3), need("c", 2))
        assertEquals(listOf("b", "c"), RequestLists.recent(needs).map { it.msgIdHex })
        assertTrue(RequestLists.recent(emptyList()).isEmpty())
    }

    @Test fun rowTitleAndTrailing() {
        val c = stringsFor(Lang.EN).common
        assertEquals("Drinking water · 6 people", requestTitle(need("a", 1, item = "Drinking water"), c))
        assertEquals("Water · 1 person", requestTitle(need("a", 1, people = 1), c))
        assertEquals("Urgent", requestTrailing(need("a", 1), c))
        assertEquals("Closed", requestTrailing(need("a", 1, ack = 3), c))
        assertEquals("Cancelled", requestTrailing(need("a", 1, cancelled = true), c))
    }

    @Test fun homeCategoriesLine() {
        assertEquals("Water · food · medicine · rescue · shelter", stringsFor(Lang.EN).home.categoriesLine)
    }
}
