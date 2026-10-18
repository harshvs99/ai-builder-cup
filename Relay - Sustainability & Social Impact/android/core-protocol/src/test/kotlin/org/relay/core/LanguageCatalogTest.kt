package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.lang.LanguageCatalog
import org.relay.core.payload.Lang

class LanguageCatalogTest {
    private val everyLang = Lang.entries

    // what the backend serves by default: English and Hindi on, the rest registered but off (backend/app/languages.py)
    private val serverDefault = """[
        {"code":"en","english_name":"English","native_name":"English","enabled":true,"reviewed":true},
        {"code":"hi","english_name":"Hindi","native_name":"हिन्दी","enabled":true,"reviewed":true},
        {"code":"or","english_name":"Odia","native_name":"ଓଡ଼ିଆ","enabled":false,"reviewed":false},
        {"code":"tl","english_name":"Filipino (Tagalog)","native_name":"Filipino","enabled":false,"reviewed":false},
        {"code":"war","english_name":"Waray","native_name":"Winaray","enabled":false,"reviewed":false},
        {"code":"ceb","english_name":"Cebuano","native_name":"Cebuano","enabled":false,"reviewed":false}]"""

    @Test fun languagePickerShowsOnlyLanguagesEnabledInBuildAndServer() {
        val server = LanguageCatalog.parseServer(serverDefault)
        assertEquals(listOf(Lang.EN, Lang.HI), LanguageCatalog.offered(everyLang, server))
        // enabled on the server but not in this build: hidden
        val odiaOn = LanguageCatalog.parseServer(serverDefault.replace("\"code\":\"or\",\"english_name\":\"Odia\",\"native_name\":\"ଓଡ଼ିଆ\",\"enabled\":false", "\"code\":\"or\",\"english_name\":\"Odia\",\"native_name\":\"ଓଡ଼ିଆ\",\"enabled\":true"))
        assertEquals(listOf(Lang.EN, Lang.HI, Lang.OR), LanguageCatalog.offered(everyLang, odiaOn))
        assertEquals(listOf(Lang.EN, Lang.HI), LanguageCatalog.offered(listOf(Lang.EN, Lang.HI), odiaOn))
        // enabled in the build but off on the server: hidden
        assertEquals(listOf(Lang.EN, Lang.HI), LanguageCatalog.offered(everyLang, server))
    }

    @Test fun languagePickerUsesBuildDefaultWithoutAServerResponse() {
        assertEquals(listOf(Lang.EN, Lang.HI), LanguageCatalog.offered(everyLang, null))
        assertEquals(listOf(Lang.EN, Lang.HI), LanguageCatalog.offered(LanguageCatalog.parseBuildLanguages("en,hi"), null))
        assertEquals(listOf(Lang.EN), LanguageCatalog.offered(listOf(Lang.EN), null))
    }

    @Test fun englishIsAlwaysOffered() {
        assertTrue(Lang.EN in LanguageCatalog.offered(emptyList(), null))
        assertTrue(Lang.EN in LanguageCatalog.offered(everyLang, LanguageCatalog.parseServer("""[{"code":"hi","enabled":true}]""")))
        assertTrue(Lang.EN in LanguageCatalog.parseBuildLanguages("hi"))
        assertEquals(LanguageCatalog.BUILD_DEFAULT, LanguageCatalog.parseBuildLanguages(""))
        assertEquals(listOf(Lang.EN, Lang.HI, Lang.WAR), LanguageCatalog.parseBuildLanguages(" hi , war, xx"))
    }

    @Test fun malformedOrUnknownServerDataIsIgnored() {
        assertNull(LanguageCatalog.parseServer(null))
        assertNull(LanguageCatalog.parseServer(""))
        assertNull(LanguageCatalog.parseServer("{\"error\":1}"))
        assertNull(LanguageCatalog.parseServer("not json"))
        // unknown fields, unknown codes and entries without a code are skipped, not fatal
        val s = LanguageCatalog.parseServer("""[{"code":"hi","enabled":true,"future":1},{"code":"xx","enabled":true},{"enabled":true},7]""")!!
        assertEquals(listOf("hi", "xx"), s.map { it.code })
        assertEquals(listOf(Lang.EN, Lang.HI), LanguageCatalog.offered(everyLang, s))
        // a response that lists nothing leaves English only
        assertEquals(listOf(Lang.EN), LanguageCatalog.offered(everyLang, LanguageCatalog.parseServer("[]")))
    }

    @Test fun storedDisabledLanguageFallsBackToEnglishAndAnnouncesOnce() {
        val offered = listOf(Lang.EN, Lang.HI)
        val ok = LanguageCatalog.resolve(Lang.HI, offered)
        assertEquals(Lang.HI, ok.effective); assertNull(ok.unavailable)
        assertFalse(LanguageCatalog.shouldAnnounce(ok, null))

        val odia = LanguageCatalog.resolve(Lang.OR, offered)
        assertEquals(Lang.EN, odia.effective); assertEquals(Lang.OR, odia.unavailable)
        assertTrue(LanguageCatalog.shouldAnnounce(odia, null))
        assertFalse(LanguageCatalog.shouldAnnounce(odia, Lang.OR)) // once
        assertTrue(LanguageCatalog.shouldAnnounce(LanguageCatalog.resolve(Lang.WAR, offered), Lang.OR)) // a different one is new
        // the stored choice is untouched: when Odia is switched on again it is used again
        assertEquals(Lang.OR, LanguageCatalog.resolve(Lang.OR, listOf(Lang.EN, Lang.HI, Lang.OR)).effective)
    }
}
