package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.relay.core.payload.Lang

/** The default build offers English and Hindi only (`relay.enabledLangs=en,hi`); the picker rules themselves are in core-protocol's LanguageCatalogTest. */
class SettingsLanguagesTest {
    @Test fun preferredLanguagesAreEnglishAndHindiWhileOthersAreHidden() {
        assertEquals(listOf(Lang.EN, Lang.HI), Settings().preferredLanguages)
        // A stored Odia choice (an install from before the freeze) is kept but not offered: speech stays en-IN and hi-IN.
        val odia = Settings(language = Lang.OR)
        assertEquals(listOf(Lang.EN, Lang.HI), odia.preferredLanguages)
        assertEquals(Lang.OR, odia.language)
        assertEquals(Lang.EN, odia.effectiveLanguage)
        assertEquals(Lang.OR, odia.languageResolution.unavailable)
    }

    @Test fun switchingLanguageKeepsTheStoredChoiceAndHindiIsUsedWhenOffered() {
        val s = Settings().withLanguage(Lang.WAR).withLanguage(Lang.EN).withLanguage(Lang.OR).withLanguage(Lang.WAR)
        assertEquals(Lang.WAR, s.language)
        assertEquals(listOf(Lang.WAR, Lang.OR), s.extraLanguages) // remembered, so they return if a reviewer switches them on
        assertEquals(listOf(Lang.EN, Lang.HI), s.preferredLanguages)
        assertEquals(Lang.HI, Settings().withLanguage(Lang.HI).effectiveLanguage)
        assertEquals(listOf(Lang.EN, Lang.HI), Settings().withLanguage(Lang.HI).preferredLanguages)
    }

    @Test fun aServerListThatSwitchesOdiaOnDoesNotOfferItInABuildWithoutIt() {
        val on = """[{"code":"en","enabled":true},{"code":"hi","enabled":true},{"code":"or","enabled":true}]"""
        assertEquals(listOf(Lang.EN, Lang.HI), Settings(serverLanguagesJson = on).offeredLanguages)
    }
}
