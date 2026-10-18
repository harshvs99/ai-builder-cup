package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.relay.app.store.RequestTimeline
import org.relay.app.ui.i18n.Plural
import org.relay.app.ui.i18n.RelativeTime
import org.relay.app.ui.i18n.RelativeTime.Bucket
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.i18n.tr
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.VulnFlag
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

class StringsTest {
    private val appLangs = listOf(Lang.EN, Lang.HI, Lang.OR, Lang.TL, Lang.WAR)
    private val utc: ZoneId = ZoneOffset.UTC
    private val savedZone: TimeZone = TimeZone.getDefault()

    // CommonStrings words relative times in the phone's zone; pin it so "yesterday" is deterministic.
    @Before fun pinZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restoreZone() { TimeZone.setDefault(savedZone) }

    private fun at(day: Int, h: Int, m: Int = 0, s: Int = 0): Long =
        ZonedDateTime.of(2026, 10, day, h, m, s, 0, utc).toInstant().toEpochMilli()

    @Test fun pluralRules() {
        assertTrue(Plural.isOne(Lang.EN, 1))
        assertFalse(Plural.isOne(Lang.EN, 0))
        assertFalse(Plural.isOne(Lang.EN, 2))
        assertTrue(Plural.isOne(Lang.HI, 0))
        assertTrue(Plural.isOne(Lang.HI, 1))
        assertFalse(Plural.isOne(Lang.HI, 2))
        assertEquals("one", Plural.pick(Lang.TL, 1, "one", "other"))
        assertEquals("other", Plural.pick(Lang.TL, 6, "one", "other"))
    }

    @Test fun relativeTimeBuckets() {
        val now = at(18, 14, 0)
        assertEquals(Bucket.JustNow, RelativeTime.bucket(now - 30_000, now, utc))
        assertEquals(Bucket.JustNow, RelativeTime.bucket(now + 5_000, now, utc)) // clock skew
        assertEquals(Bucket.Minutes(1), RelativeTime.bucket(now - 60_000, now, utc))
        assertEquals(Bucket.Minutes(59), RelativeTime.bucket(now - 59 * 60_000L - 59_000, now, utc))
        assertEquals(Bucket.Hours(1), RelativeTime.bucket(now - 3_600_000, now, utc))
        assertEquals(Bucket.Hours(10), RelativeTime.bucket(at(18, 4, 0), now, utc))
        // Crossing midnight is "yesterday" even when under 24 hours ago.
        assertEquals(Bucket.Yesterday, RelativeTime.bucket(at(17, 23, 0), now, utc))
        assertEquals(Bucket.Yesterday, RelativeTime.bucket(at(17, 1, 0), now, utc))
        assertEquals(Bucket.Days(3), RelativeTime.bucket(at(15, 23, 0), now, utc))
    }

    @Test fun trPicksLanguageAndCebuanoFallsBackToEnglish() {
        assertEquals("a", tr(Lang.EN, "a", "b", "c", "d", "e"))
        assertEquals("b", tr(Lang.HI, "a", "b", "c", "d", "e"))
        assertEquals("c", tr(Lang.OR, "a", "b", "c", "d", "e"))
        assertEquals("d", tr(Lang.TL, "a", "b", "c", "d", "e"))
        assertEquals("e", tr(Lang.WAR, "a", "b", "c", "d", "e"))
        assertEquals("a", tr(Lang.CEB, "a", "b", "c", "d", "e"))
    }

    @Test fun englishCommonTexts() {
        val c = stringsFor(Lang.EN).common
        assertEquals("1 person", c.people(1))
        assertEquals("6 people", c.people(6))
        assertEquals("1 phone", c.phones(1))
        assertEquals("3 phones", c.phones(3))
        assertEquals("1 hop", c.hops(1))
        assertEquals("2 hops", c.hops(2))
        val now = at(18, 14, 0)
        assertEquals("just now", c.ago(now - 10_000, now))
        assertEquals("2 min ago", c.ago(now - 120_000, now))
        assertEquals("3 h ago", c.ago(now - 3 * 3_600_000L, now))
        assertEquals("yesterday", c.ago(at(17, 20, 0), now))
        assertEquals("Urgent", c.urgencyWord(2))
        assertEquals("Life at risk", c.urgencyWord(3))
        assertEquals("Life at risk", c.urgencyWord(99)) // clamped
        assertEquals("Soon: needed today.", c.urgencyHelp(1))
        assertEquals("Medicine", c.categoryName(Category.MEDICAL))
        assertEquals("elderly, children", c.vulnWords(VulnFlag.toMask(listOf(VulnFlag.CHILD, VulnFlag.ELDERLY))))
        assertEquals("", c.vulnWords(0))
        assertEquals("Delivered yesterday", c.rowStatusLine(RequestTimeline.Step(RequestTimeline.Key.DELIVERED, RequestTimeline.State.DONE, at(17, 20, 0)), now))
        assertEquals("Reached a gateway phone · 2 min ago", c.rowStatusLine(RequestTimeline.Step(RequestTimeline.Key.REACHED_GATEWAY, RequestTimeline.State.CURRENT, now - 120_000), now))
    }

    @Test fun villagerTextsFollowTheSpecTable() {
        val c = stringsFor(Lang.EN).common
        val now = at(18, 14, 0)
        val reachable = VillagerStatus(VillagerStatus.Kind.REACHABLE, VillagerStatus.Dot.GREEN, 2, 2, null)
        assertEquals("Your messages can reach help", c.villagerTitle(reachable))
        assertEquals("Gateway 2 hops away", c.villagerLine(reachable, now))
        assertEquals("Reachable", c.villagerWord(reachable))
        val mine = VillagerStatus(VillagerStatus.Kind.THIS_PHONE_GATEWAY, VillagerStatus.Dot.GREEN, 0, null, 180_000)
        assertEquals("Connected to the internet · synced 3 min ago", c.villagerLine(mine, now))
        val waiting = VillagerStatus(VillagerStatus.Kind.WAITING_FOR_GATEWAY, VillagerStatus.Dot.AMBER, 3, null, null)
        assertEquals("Connected to 3 phones nearby. Your messages wait safely.", c.villagerLine(waiting, now))
        assertEquals("Waiting", c.villagerWord(waiting))
        val none = VillagerStatus(VillagerStatus.Kind.SEARCHING, VillagerStatus.Dot.GREY, 0, null, null)
        assertEquals("Looking for nearby phones", c.villagerTitle(none))
        assertEquals("Searching", c.villagerWord(none))
    }

    @Test fun everyLanguageHasEveryCommonString() {
        val now = at(18, 14, 0)
        for (lang in appLangs) {
            val c = stringsFor(lang).common
            val all = mutableListOf(c.navHome, c.navRequests, c.navNetwork, c.navSettings, c.change, c.continueAction, c.send, c.cancel,
                c.done, c.back, c.close, c.seeAll, c.turnOn, c.somethingElse, c.statusClosed, c.statusCancelled, c.statusActive,
                c.liveTag, c.runningLate, c.people(1), c.people(4), c.phones(2), c.hops(2),
                c.ago(now - 10_000, now), c.ago(now - 120_000, now), c.ago(now - 7_200_000, now), c.ago(at(17, 20), now), c.ago(at(10, 1), now))
            (0..3).forEach { all += c.urgencyWord(it); all += c.urgencyHelp(it) }
            Category.entries.forEach { all += c.categoryName(it) }
            VulnFlag.entries.forEach { all += c.vulnFlag(it) }
            RequestTimeline.Key.entries.forEach { all += c.stepTitle(it) }
            VillagerStatus.Kind.entries.forEach {
                val s = VillagerStatus(it, VillagerStatus.Dot.GREEN, 2, 1, 60_000)
                all += c.villagerTitle(s); all += c.villagerLine(s, now); all += c.villagerWord(s)
            }
            all.forEach { assertTrue("blank string in $lang", it.isNotBlank()) }
            assertTrue(c.clock(now).isNotBlank())
        }
    }

    @Test fun nonEnglishDiffersFromEnglishForKeyTexts() {
        val en = stringsFor(Lang.EN).common
        for (lang in listOf(Lang.HI, Lang.OR, Lang.TL, Lang.WAR)) {
            val c = stringsFor(lang).common
            assertNotEquals(en.send, c.send)
            assertNotEquals(en.urgencyWord(3), c.urgencyWord(3))
            assertNotEquals(en.stepTitle(RequestTimeline.Key.HELP_COMING), c.stepTitle(RequestTimeline.Key.HELP_COMING))
        }
    }

    @Test fun stringsForIsCachedPerLanguage() {
        assertTrue(stringsFor(Lang.HI) === stringsFor(Lang.HI))
        assertEquals(Lang.OR, stringsFor(Lang.OR).lang)
    }
}
