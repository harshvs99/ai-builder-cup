package org.relay.sim

import org.relay.core.announce.AnnouncementBoard
import org.relay.core.announce.AnnouncementCard
import org.relay.core.announce.HeldFrame
import org.relay.core.ids.Hex
import org.relay.core.payload.Announcement
import org.relay.core.payload.AnnouncementStatus
import org.relay.core.payload.Lang

/** One simulated phone's view of the announcements: where it is and which language it uses. */
data class PhoneView(val name: String, val district: String, val lang: Lang, val hops: Int)

data class AnnouncementOutcome(
    val phones: List<PhoneView>,
    /** What each phone shows right after the flood (UI-language frames only; the 10-minute English fallback has not run yet). */
    val afterFlood: Map<String, List<AnnouncementCard>>,
    /** After 10 virtual minutes: a phone whose language has no frame falls back to English. */
    val afterFallback: Map<String, List<AnnouncementCard>>,
    /** After the gateway injects WITHDRAWN frames. */
    val afterWithdraw: Map<String, List<AnnouncementCard>>,
    /** How many phones hold the ACTIVE frames (every phone relays, whatever its district or language). */
    val holders: Int,
    val transmissions: Long,
    val virtualMs: Long,
) {
    private fun shows(m: Map<String, List<AnnouncementCard>>, name: String) = m[name].orEmpty().isNotEmpty()

    /** Phones in the announcement's district, in a language with a frame, see it at once; the rest wait or never show; a withdrawal clears all. */
    val pass: Boolean get() {
        val inDistrict = phones.filter { it.district == "puri" }
        val immediate = inDistrict.filter { it.lang == Lang.EN || it.lang == Lang.HI }
        return holders == phones.size &&
            immediate.all { shows(afterFlood, it.name) } &&
            inDistrict.filter { it.lang !in setOf(Lang.EN, Lang.HI) }.all { !shows(afterFlood, it.name) && shows(afterFallback, it.name) } &&
            phones.filter { it.district != "puri" }.all { !shows(afterFlood, it.name) && !shows(afterFallback, it.name) } &&
            phones.all { !shows(afterWithdraw, it.name) }
    }

    fun render(): String = buildString {
        fun line(m: Map<String, List<AnnouncementCard>>) = phones.joinToString("  ") { p ->
            val c = m[p.name].orEmpty().firstOrNull()
            "${p.name}(${p.district},${p.lang.tag},${p.hops}h)=" + (c?.let { "\"${it.text}\"[${it.lang.tag}]" } ?: "-")
        }
        appendLine("== scenario announcement: gateway injects an ANNOUNCEMENT (en + hi), ${phones.size} phones up to ${phones.maxOf { it.hops }} hops away, then withdraws it")
        appendLine("   after flood    : ${line(afterFlood)}")
        appendLine("   after 10 min   : ${line(afterFallback)}")
        appendLine("   after withdraw : ${line(afterWithdraw)}")
        appendLine("   every phone holds the frames: $holders/${phones.size}   transmissions: $transmissions   virtual time: ${virtualMs / 1000.0} s")
        append("   result         : ${if (pass) "PASS" else "FAIL"}")
    }
}

/**
 * Contract v2 section 7, simulator: a gateway injects an ANNOUNCEMENT (English and Hindi frames of one announcement id), phones up to
 * 3 hops away show it in their own language, and a WITHDRAWN frame supersedes it. A phone in another district and a phone whose
 * language has no frame still relay it; the first never shows it, the second shows the English one after 10 minutes.
 */
object AnnouncementScenario {
    private const val PURI = 0

    fun run(seed: Long = 42): AnnouncementOutcome {
        val net = SimNet(seed)
        // g - a - b - c, with d and e hanging off a and b: five Puri phones within 3 hops, plus a Delhi phone and an Odia-language phone.
        net.connect("g", "a"); net.connect("a", "b"); net.connect("b", "c"); net.connect("a", "d"); net.connect("b", "e"); net.connect("c", "f")
        val phones = listOf(
            PhoneView("a", "puri", Lang.HI, 1), PhoneView("b", "puri", Lang.HI, 2), PhoneView("c", "puri", Lang.EN, 3),
            PhoneView("d", "puri", Lang.HI, 2), PhoneView("e", "puri", Lang.OR, 3), PhoneView("f", "delhi", Lang.HI, 4),
        )
        val gateway = net.nodes.getValue("g").also { it.node.isGateway = true }
        val start = net.now
        val id = ByteArray(8) { (0x30 + it).toByte() }
        fun frame(lang: Lang, status: AnnouncementStatus, text: String) =
            Announcement(id, lang, PURI, status, net.now / 1000, 0, text)

        gateway.node.originateAnnouncement(frame(Lang.EN, AnnouncementStatus.ACTIVE, "Cyclone shelters in Puri are open."))
        gateway.node.originateAnnouncement(frame(Lang.HI, AnnouncementStatus.ACTIVE, "पुरी में चक्रवात शरण स्थल खुले हैं।"))
        net.runUntilIdle()

        fun view(): Map<String, List<AnnouncementCard>> = phones.associate { p ->
            val held = net.nodes.getValue(p.name).node.storedAnnouncements().map { HeldFrame(it.announcement, it.receivedAtMs) }
            p.name to AnnouncementBoard.visible(held, emptyList(), p.district, p.lang, net.now)
        }
        val holders = phones.count { net.nodes.getValue(it.name).node.storedAnnouncements().count { h -> h.announcement.status == AnnouncementStatus.ACTIVE } == 2 }
        val afterFlood = view()
        net.advance(10 * 60_000L + 1_000)
        val afterFallback = view()

        // the duty officer withdraws it: the backend hands the gateway WITHDRAWN frames for both languages
        gateway.node.originateAnnouncement(frame(Lang.EN, AnnouncementStatus.WITHDRAWN, "Cyclone shelters in Puri are open."))
        gateway.node.originateAnnouncement(frame(Lang.HI, AnnouncementStatus.WITHDRAWN, "पुरी में चक्रवात शरण स्थल खुले हैं।"))
        net.runUntilIdle()
        val afterWithdraw = view()
        check(Hex.encode(id).isNotEmpty())
        return AnnouncementOutcome(phones, afterFlood, afterFallback, afterWithdraw, holders, net.transmissions, net.now - start)
    }
}
