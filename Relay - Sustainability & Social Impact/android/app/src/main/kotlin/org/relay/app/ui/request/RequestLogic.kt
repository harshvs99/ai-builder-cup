package org.relay.app.ui.request

import org.relay.app.store.MyNeed
import org.relay.app.store.RequestTimeline
import org.relay.app.ui.components.requestTitle
import org.relay.app.ui.i18n.CommonStrings
import org.relay.app.ui.i18n.Strings
import org.relay.core.payload.AckStatus

/** Pure text and decisions for Request detail (unit-tested; the screen only displays them). */
object RequestLogic {
    /** "#<ticket>" once the control room has a ticket number, else "R-" + first 4 hex of the msg id, uppercase. */
    fun reference(need: MyNeed): String =
        if (need.ticketNo >= 0) "#${need.ticketNo}" else "R-" + need.msgIdHex.take(4).uppercase()

    /** "Drinking water · 6 people": the item (or category name), then the head count. */
    fun title(need: MyNeed, common: CommonStrings): String = requestTitle(need, common)

    /** Plain-text summary for the share sheet, in the app language of [s]. */
    fun shareText(need: MyNeed, s: Strings, area: String, now: Long): String {
        val c = s.common
        val r = s.request
        val lines = ArrayList<String>()
        lines += r.shareHeader(reference(need))
        lines += title(need, c) + " (" + c.urgencyWord(need.urgency) + ")"
        lines += r.referenceLine(reference(need), c.clock(need.createdAtMs), area)
        if (need.landmark.isNotBlank()) lines += r.shareNear(need.landmark)
        val status = if (need.cancelled) c.statusCancelled else {
            val step = RequestTimeline.latest(need, now)
            c.stepTitle(step.key) + if (step.etaAtMs > 0 && !step.etaOverdue) " · " + c.expectedBy(step.etaAtMs) else ""
        }
        lines += r.shareStatus(status)
        if (hasMessage(need)) lines += r.shareMessage(need.ackText)
        return lines.joinToString("\n")
    }

    /** Identity of the control-room message currently shown: changes when a newer ACK arrives. */
    fun messageKey(need: MyNeed): String = "${need.msgIdHex}:${need.ackStatus}:${need.ackText.hashCode()}"

    /** True when there is a message from the control room (the card is shown). */
    fun hasMessage(need: MyNeed): Boolean = need.ackStatus >= AckStatus.RECEIVED.code &&need.ackText.isNotBlank()

    /** Auto-read once per new message: only with "Read messages aloud" on, a message present, and not read before. */
    fun shouldAutoRead(readAloud: Boolean, need: MyNeed, alreadyRead: Set<String>): Boolean =
        readAloud && hasMessage(need) && messageKey(need) !in alreadyRead
}
