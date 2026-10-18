package org.relay.app

import org.relay.app.gemma.GemmaNeedParser
import org.relay.core.payload.Category
import org.relay.core.payload.VulnFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaNeedParserTest {
    @Test fun fencedReplyParsesAndMapsToModelFields() {
        val raw = "Sure!\n```json\n" +
            """{"transcript":"4 trapped on roof","lang":"en","category":"RESCUE","urgency":3,"people":4,"vuln":["TRAPPED"],"item":"rescue","landmark":"clock tower","summary":"4 trapped on roof"}""" +
            "\n```"
        val p = GemmaNeedParser.parse(raw)
        assertTrue(p.issues.toString(), p.valid)
        assertEquals(Category.RESCUE, p.category)
        assertEquals(setOf(VulnFlag.TRAPPED), p.vuln)
        assertEquals(4, p.toModelFields()?.people)
    }

    @Test fun clampsAndTruncatesWithoutInvalidating() {
        val p = GemmaNeedParser.parse("""{"transcript":"x","lang":"hi","category":"water","urgency":9,"people":0,"vuln":[],"item":"a very long item name here","landmark":"","summary":"s"}""")
        assertTrue(p.valid)
        assertEquals(3, p.urgency)
        assertEquals(16, p.item.toByteArray().size)
        assertTrue(p.repairs.isNotEmpty())
        assertNull(p.toModelFields()?.people)
    }

    @Test fun badEnumAndMissingKeysAreInvalid() {
        val p = GemmaNeedParser.parse("""{"transcript":"x","lang":"xx","category":"LOST","urgency":1}""")
        assertFalse(p.valid)
        assertNull(p.toModelFields())
        assertFalse(GemmaNeedParser.parse("no json").jsonFound)
    }

    @Test fun bracesInsideStringsDoNotBreakExtraction() {
        val p = GemmaNeedParser.parse("""{"transcript":"a } b","lang":"en","category":"FOOD","urgency":1,"people":2,"vuln":[],"item":"food","landmark":"","summary":"s"} trailing""")
        assertTrue(p.issues.toString(), p.valid)
        assertEquals("a } b", p.transcript)
    }
}
