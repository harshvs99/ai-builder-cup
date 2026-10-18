package org.relay.app.ui.home

import org.relay.app.store.MyNeed

/** Pure list logic for Home and the Requests tab. */
object RequestLists {
    /** Newest first. */
    fun newestFirst(needs: List<MyNeed>): List<MyNeed> = needs.sortedByDescending { it.createdAtMs }

    /** The most recent [n] requests, open or closed (Home). */
    fun recent(needs: List<MyNeed>, n: Int = 2): List<MyNeed> = newestFirst(needs).take(n)

    /** Active (not resolved or cancelled) and Closed, each newest first. */
    fun split(needs: List<MyNeed>): Pair<List<MyNeed>, List<MyNeed>> =
        newestFirst(needs).partition { !it.isClosed }
}
