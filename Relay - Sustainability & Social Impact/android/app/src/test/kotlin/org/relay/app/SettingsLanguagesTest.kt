package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.relay.core.payload.Lang

class SettingsLanguagesTest {
    @Test fun preferredLanguagesAreEnglishHindiThenWhatTheUserAdded() {
        assertEquals(listOf(Lang.EN, Lang.HI), Settings().preferredLanguages)
        // An install from before this setting: only the app language is known.
        assertEquals(listOf(Lang.EN, Lang.HI, Lang.OR), Settings(language = Lang.OR).preferredLanguages)

        val s = Settings().withLanguage(Lang.WAR).withLanguage(Lang.EN).withLanguage(Lang.OR).withLanguage(Lang.WAR)
        assertEquals(Lang.WAR, s.language)
        assertEquals(listOf(Lang.WAR, Lang.OR), s.extraLanguages) // back to English kept Waray; no duplicate
        assertEquals(listOf(Lang.EN, Lang.HI, Lang.WAR, Lang.OR), s.preferredLanguages)
        assertEquals(listOf(Lang.EN, Lang.HI), Settings().withLanguage(Lang.HI).preferredLanguages)
    }
}
