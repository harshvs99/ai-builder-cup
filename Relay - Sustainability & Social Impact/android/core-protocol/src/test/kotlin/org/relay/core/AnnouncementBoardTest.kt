package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.relay.core.announce.AnnouncementBoard
import org.relay.core.announce.AnnouncementJson
import org.relay.core.announce.AnnouncementStore
import org.relay.core.announce.HeldFrame
import org.relay.core.announce.HttpAnnouncement
import org.relay.core.payload.Announcement
import org.relay.core.payload.AnnouncementStatus
import org.relay.core.payload.Lang
import java.io.File

/** Show/hide rules of the Home card (SPEC 5.7). Mirrored by Swift `AnnouncementBoardTests`. */
class AnnouncementBoardTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = T0
    private val min = 60_000L
    private fun id(n: Int) = ByteArray(8) { n.toByte() }

    private fun held(
        n: Int, lang: Lang, status: AnnouncementStatus = AnnouncementStatus.ACTIVE, district: Int = 0, text: String = "t$n ${lang.tag}",
        approvedAgoMin: Long = 5, receivedAgoMin: Long = 5,
    ) = HeldFrame(Announcement(id(n), lang, district, status, (now - approvedAgoMin * min) / 1000, 0, text), now - receivedAgoMin * min)

    private fun show(frames: List<HeldFrame>, district: String? = "puri", lang: Lang = Lang.HI, http: List<HttpAnnouncement> = emptyList(), dismissed: Set<String> = emptySet(), at: Long = now) =
        AnnouncementBoard.visible(frames, http, district, lang, at, dismissed)

    @Test fun aFrameInTheUiLanguageAndDistrictIsShown() {
        val cards = show(listOf(held(1, Lang.HI), held(1, Lang.EN)))
        assertEquals(1, cards.size)
        assertEquals(Lang.HI, cards[0].lang); assertEquals("t1 hi", cards[0].text); assertFalse(cards[0].fromHttp)
    }

    @Test fun anotherDistrictIsRelayedButNotShownAndAPhoneWithNoDistrictShowsEverything() {
        val delhi = held(1, Lang.HI, district = 1)
        assertTrue(show(listOf(delhi), district = "puri").isEmpty())
        assertEquals(1, show(listOf(delhi), district = "delhi").size)
        assertEquals(1, show(listOf(delhi), district = null).size)
        assertTrue(show(listOf(held(1, Lang.HI, district = 77)), district = "puri").isEmpty()) // a district this build does not know
    }

    @Test fun englishIsShownOnlyAfterTenMinutesWithoutAFrameInTheUiLanguage() {
        val en = held(1, Lang.EN, receivedAgoMin = 9)
        assertTrue(show(listOf(en)).isEmpty())
        assertEquals(Lang.EN, show(listOf(held(1, Lang.EN, receivedAgoMin = 10))).single().lang)
        assertEquals(Lang.EN, show(listOf(en), at = now + 2 * min).single().lang)
        // the Hindi frame arrives later: it replaces the English card
        assertEquals(Lang.HI, show(listOf(held(1, Lang.EN, receivedAgoMin = 30), held(1, Lang.HI, receivedAgoMin = 1))).single().lang)
        // an English phone never waits
        assertEquals(Lang.EN, show(listOf(held(1, Lang.EN, receivedAgoMin = 0)), lang = Lang.EN).single().lang)
        // other languages are not a fallback
        assertTrue(show(listOf(held(1, Lang.OR, receivedAgoMin = 60))).isEmpty())
    }

    @Test fun theWaitCountsFromTheFirstFrameOfThatAnnouncementNotTheLatest() {
        val frames = listOf(held(1, Lang.OR, receivedAgoMin = 12), held(1, Lang.EN, receivedAgoMin = 1))
        assertEquals(Lang.EN, show(frames).single().lang)
    }

    @Test fun aWithdrawnFrameRemovesTheCardAndShowsNothing() {
        val frames = listOf(held(1, Lang.HI), held(1, Lang.EN, receivedAgoMin = 30), held(1, Lang.HI, AnnouncementStatus.WITHDRAWN))
        assertTrue(show(frames).isEmpty()) // not even the English one
        assertTrue(show(listOf(held(1, Lang.EN, receivedAgoMin = 30), held(1, Lang.EN, AnnouncementStatus.WITHDRAWN))).isEmpty())
        // a withdrawal that arrives before the approval hides it too
        assertTrue(show(listOf(held(1, Lang.HI, AnnouncementStatus.WITHDRAWN), held(1, Lang.HI))).isEmpty())
        // another announcement is not affected
        assertEquals(listOf(id(2).joinToString("") { "%02x".format(it) }), show(frames + held(2, Lang.HI)).map { it.announcementId })
    }

    @Test fun aWithdrawnEnglishFallbackAlsoDisappearsWhenThePhoneHasNoUiLanguageFrame() {
        assertTrue(show(listOf(held(1, Lang.EN, receivedAgoMin = 30), held(1, Lang.EN, AnnouncementStatus.WITHDRAWN, receivedAgoMin = 1))).isEmpty())
    }

    @Test fun newestFirstAtMostThree() {
        val frames = (1..5).map { held(it, Lang.HI, approvedAgoMin = (10 - it).toLong()) }
        val cards = show(frames)
        assertEquals(3, cards.size)
        assertEquals(listOf("t5 hi", "t4 hi", "t3 hi"), cards.map { it.text })
    }

    @Test fun aDismissedAnnouncementStaysGoneInEveryLanguage() {
        val hex = held(1, Lang.HI).announcement.idHex
        assertTrue(show(listOf(held(1, Lang.HI), held(1, Lang.EN)), dismissed = setOf(hex)).isEmpty())
        assertEquals(1, show(listOf(held(1, Lang.HI), held(2, Lang.HI)), dismissed = setOf(hex)).size)
    }

    @Test fun staleAndEndedAnnouncementsAreNotShown() {
        assertTrue(show(listOf(held(1, Lang.HI, approvedAgoMin = 73 * 60))).isEmpty())
        val ended = HeldFrame(Announcement(id(1), Lang.HI, 0, AnnouncementStatus.ACTIVE, (now - 5 * min) / 1000, (now - min) / 1000, "x"), now)
        assertTrue(show(listOf(ended)).isEmpty())
    }

    // ------------------------------------------------------------ http
    private fun httpA(n: Int, texts: Map<Lang, String>, district: String = "puri", fetchedAgoMin: Long = 0) =
        HttpAnnouncement(held(n, Lang.EN).announcement.idHex, district, (now - 5 * min) / 1000, texts, now - fetchedAgoMin * min)

    @Test fun theFullTextFromTheBackendBeatsTheMeshShortForm() {
        val card = show(listOf(held(1, Lang.HI, text = "short…")), http = listOf(httpA(1, mapOf(Lang.HI to "the whole message", Lang.EN to "whole en")))).single()
        assertEquals("the whole message", card.text); assertTrue(card.fromHttp)
    }

    @Test fun aPhoneThatOnlyHasTheBackendListShowsItWithTheSameLanguageRules() {
        val both = httpA(1, mapOf(Lang.HI to "hi text", Lang.EN to "en text"))
        assertEquals("hi text", show(emptyList(), http = listOf(both)).single().text)
        val enOnly = httpA(2, mapOf(Lang.EN to "en text"), fetchedAgoMin = 3)
        assertTrue(show(emptyList(), http = listOf(enOnly)).isEmpty())
        assertEquals("en text", show(emptyList(), http = listOf(httpA(2, mapOf(Lang.EN to "en text"), fetchedAgoMin = 11))).single().text)
        assertTrue(show(emptyList(), http = listOf(httpA(3, mapOf(Lang.HI to "x"), district = "delhi"))).isEmpty())
    }

    @Test fun aMeshWithdrawalHidesTheBackendCopyToo() {
        val frames = listOf(held(1, Lang.HI, AnnouncementStatus.WITHDRAWN))
        assertTrue(show(frames, http = listOf(httpA(1, mapOf(Lang.HI to "x")))).isEmpty())
    }

    @Test fun oneNotificationPerAnnouncementOnFirstReceipt() {
        val cards = show(listOf(held(1, Lang.HI), held(2, Lang.HI)))
        assertEquals(2, AnnouncementBoard.toNotify(cards, emptySet()).size)
        assertEquals(listOf(cards[1].announcementId), AnnouncementBoard.toNotify(cards, setOf(cards[0].announcementId)).map { it.announcementId })
    }

    @Test fun activeListParsingKeepsFullTextsAndGivesOldEntriesAnId() {
        val json = """{"announcements":[
            {"id":7,"announcement_id":"0101010101010101","district":"puri","approved_at":1790000000,"texts":{"en":"Hello","hi":"नमस्ते","xx":"?"},"future":1},
            {"id":8,"district":"puri","approved_at":1790000001,"texts":{"en":"Old row"}},
            {"id":9,"announcement_id":"02","district":"puri","approved_at":1,"texts":{"en":"  "}}]}"""
        val list = AnnouncementJson.parseActive(json, 5)!!
        assertEquals(2, list.size)
        assertEquals("0101010101010101", list[0].announcementId); assertEquals(mapOf(Lang.EN to "Hello", Lang.HI to "नमस्ते"), list[0].texts)
        assertEquals("http-8", list[1].announcementId)
        assertNull(AnnouncementJson.parseActive("garbage", 0)); assertNull(AnnouncementJson.parseActive(null, 0)); assertNull(AnnouncementJson.parseActive("{}", 0))
    }

    @Test fun heldFramesAreReadFromAPacketStoreAndTheOtherTypesAreIgnored() {
        val net = TestNet()
        net.node("a").node.originateAnnouncement(Announcement(id(1), Lang.HI, 0, AnnouncementStatus.ACTIVE, T0 / 1000, 0, "x"))
        net.node("a").node.originateNeed(sampleNeed(net.node("a").id))
        net.runUntilIdle()
        val store = org.relay.core.gossip.InMemoryPacketStore()
        net.node("a").node.storedAnnouncements().forEach { h ->
            store.put(org.relay.core.gossip.StoredPacket(h.announcement.keyHex, org.relay.core.frame.FrameTypes.ANNOUNCEMENT, org.relay.core.frame.FrameCodec.encode(h.frame), h.receivedAtMs))
        }
        store.put(org.relay.core.gossip.StoredPacket("junk", org.relay.core.frame.FrameTypes.ANNOUNCEMENT, byteArrayOf(1, 2), 0))
        val frames = AnnouncementBoard.heldFrom(store)
        assertEquals(1, frames.size); assertEquals("x", frames[0].announcement.text); assertEquals(T0, frames[0].receivedAtMs)
    }

    // ------------------------------------------------------------ store
    @Test fun theStoreKeepsTheBackendListDismissalsAndNotificationsAcrossRestarts() {
        val dir = tmp.newFolder()
        val s = AnnouncementStore(dir)
        assertTrue(s.http().isEmpty()); assertEquals(0L, s.lastHttpFetchMs())
        s.saveHttp("""{"announcements":[{"id":1,"announcement_id":"aa","district":"puri","approved_at":1790000000,"texts":{"hi":"x"}}]}""", 1234)
        s.dismiss("aa"); s.markNotified("aa"); s.markNotified("bb")
        val again = AnnouncementStore(dir)
        assertEquals(listOf("aa"), again.http().map { it.announcementId }); assertEquals(1234L, again.http()[0].fetchedAtMs)
        assertEquals(1234L, again.lastHttpFetchMs())
        assertEquals(setOf("aa"), again.dismissed()); assertEquals(setOf("aa", "bb"), again.notified())
    }

    @Test fun aDamagedStoreReadsAsEmptyAndTheRememberedIdsAreCapped() {
        val dir = tmp.newFolder()
        File(dir, "announcements_http.json").writeText("{ nope"); File(dir, "announcements_state.json").writeText("garbage")
        val s = AnnouncementStore(dir)
        assertTrue(s.http().isEmpty()); assertTrue(s.dismissed().isEmpty())
        for (i in 0 until 250) s.dismiss("id$i")
        assertEquals(200, s.dismissed().size); assertTrue("id249" in s.dismissed()); assertFalse("id0" in s.dismissed())
    }
}
