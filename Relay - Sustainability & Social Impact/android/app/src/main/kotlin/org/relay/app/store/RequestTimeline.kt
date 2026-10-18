package org.relay.app.store

import org.relay.core.payload.AckStatus

/**
 * The vertical timeline of one request (design spec section 5): every step reached so far, the latest one marked
 * current, future steps left out. Pure; the UI turns [Step.key] into words per language.
 */
object RequestTimeline {
    enum class Key { SAVED, PASSED_TO_NEIGHBOUR, REACHED_GATEWAY, SEEN_BY_CONTROL_ROOM, HELP_COMING, TEAM_ON_THE_WAY, DELIVERED }

    enum class State { DONE, CURRENT }

    data class Step(
        val key: Key,
        val state: State,
        /** When the phone first saw this step reached (epoch ms); 0 = reached but the time was not recorded. */
        val atMs: Long,
        /** Expected arrival (epoch ms) for HELP_COMING / TEAM_ON_THE_WAY when the ACK carried an ETA, else 0. */
        val etaAtMs: Long = 0,
        /** Urgency 0..3 of the request, on SEEN_BY_CONTROL_ROOM ("marked urgent"); null elsewhere. */
        val urgency: Int? = null,
        /** LIVE tag: this is the in-progress "team on the way" step. */
        val live: Boolean = false,
        /** The ETA has passed and the team has not arrived. */
        val etaOverdue: Boolean = false,
    )

    /**
     * Steps in order. The latest reached step is CURRENT unless the request is delivered or cancelled (then all
     * are DONE). Steps implied by a later ACK count as reached even if no timestamp was recorded for them.
     */
    fun steps(need: MyNeed, now: Long): List<Step> {
        val ack = need.ackStatus
        val reached = listOf(
            Key.SAVED to true,
            Key.PASSED_TO_NEIGHBOUR to (need.sentToMesh || ack >= 0 || need.uploadedAtMs > 0),
            Key.REACHED_GATEWAY to (need.uploadedAtMs > 0 || ack >= AckStatus.RECEIVED.code),
            Key.SEEN_BY_CONTROL_ROOM to (ack >= AckStatus.RECEIVED.code),
            Key.HELP_COMING to (ack >= AckStatus.DISPATCHED.code),
            Key.TEAM_ON_THE_WAY to (ack >= AckStatus.ON_THE_WAY.code),
            Key.DELIVERED to (ack >= AckStatus.RESOLVED.code),
        ).filter { it.second }.map { it.first }
        val delivered = Key.DELIVERED in reached
        val lastIsCurrent = !delivered && !need.cancelled
        return reached.mapIndexed { i, key ->
            val current = lastIsCurrent && i == reached.lastIndex
            val at = when (key) {
                Key.SAVED -> need.savedAtMs
                Key.PASSED_TO_NEIGHBOUR -> need.sentToMeshAtMs
                Key.REACHED_GATEWAY -> need.uploadedAtMs.takeIf { it > 0 } ?: need.receivedAtMs
                Key.SEEN_BY_CONTROL_ROOM -> need.receivedAtMs
                Key.HELP_COMING -> need.dispatchedAtMs
                Key.TEAM_ON_THE_WAY -> need.onTheWayAtMs
                Key.DELIVERED -> need.resolvedAtMs
            }
            val hasEta = (key == Key.HELP_COMING || key == Key.TEAM_ON_THE_WAY) && need.etaAtMs > 0
            Step(
                key = key,
                state = if (current) State.CURRENT else State.DONE,
                atMs = at,
                etaAtMs = if (hasEta) need.etaAtMs else 0,
                urgency = if (key == Key.SEEN_BY_CONTROL_ROOM) need.urgency else null,
                live = key == Key.TEAM_ON_THE_WAY && current,
                etaOverdue = hasEta && current && now > need.etaAtMs,
            )
        }
    }

    /** The latest step, to describe a request in a list row ("Reached a gateway phone · 2 min ago"). */
    fun latest(need: MyNeed, now: Long): Step = steps(need, now).last()
}
