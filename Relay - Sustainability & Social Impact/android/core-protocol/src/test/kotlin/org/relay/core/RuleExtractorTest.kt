package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.nlu.NeedDraft
import org.relay.core.nlu.RuleExtractor
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.NeedCodec
import org.relay.core.payload.VulnFlag
import java.util.Random

class RuleExtractorTest {

    private fun x(text: String, hint: Lang? = null) = RuleExtractor.extract(text, hint)

    private fun NeedDraft.check(
        category: Category? = null, urgency: Int? = null, item: String? = null, landmark: String? = null,
        people: Int? = null, lang: Lang? = null, flags: Set<VulnFlag>? = null, text: String = "",
    ) {
        category?.let { assertEquals("category of '$text' -> $this", it, this.category) }
        urgency?.let { assertEquals("urgency of '$text' -> $this", it, this.urgency) }
        item?.let { assertEquals("item of '$text' -> $this", it, this.item) }
        landmark?.let { assertEquals("landmark of '$text' -> $this", it, this.landmark) }
        people?.let { assertEquals("people of '$text' -> $this", it, this.people) }
        lang?.let { assertEquals("lang of '$text' -> $this", it, this.lang) }
        flags?.let { assertEquals("flags of '$text' -> $this", it, this.vulnFlags) }
        // universal invariants
        assertTrue("summary <= 40 bytes: $summary", summary.toByteArray().size <= 40)
        assertTrue("item <= 16 bytes: $item", this.item.toByteArray().size <= 16)
        assertTrue("landmark <= 24 bytes: ${this.landmark}", this.landmark.toByteArray().size <= 24)
        assertTrue(this.urgency in 0..3 && this.people in 1..255)
    }

    private fun t(text: String, hint: Lang? = null, block: NeedDraft.() -> Unit = {}) { x(text, hint).apply(block) }

    // ------------------------------------------------------------------ the required case
    @Test fun requiredExample() {
        val d = x("my mother needs insulin, near the school")
        assertEquals(Category.MEDICAL, d.category)
        assertEquals(3, d.urgency)
        assertEquals("insulin", d.item)
        assertEquals("school", d.landmark)
        assertEquals(1, d.people)
        assertEquals(Lang.EN, d.lang)
        assertEquals("mother needs insulin", d.summary)
    }

    // ------------------------------------------------------------------ English
    @Test fun english1() = x("Please help! We are 5 people trapped on the roof, water rising fast, near the temple").check(
        Category.RESCUE, 3, "rescue", "temple", 5, Lang.EN, setOf(VulnFlag.TRAPPED))
    @Test fun english2() = x("My grandfather is bleeding badly, we are at the hospital gate").check(
        Category.MEDICAL, 3, "first aid", "hospital", 1, Lang.EN, setOf(VulnFlag.ELDERLY, VulnFlag.INJURED))
    @Test fun english3() = x("We need drinking water and food, 3 children and 2 adults, behind the market").check(
        Category.WATER, 1, "water", "market", 5, Lang.EN, setOf(VulnFlag.CHILD))
    @Test fun english4() = x("pregnant woman in labour, need ambulance by the bridge").check(
        Category.MEDICAL, 3, "ambulance", "bridge", 1, Lang.EN, setOf(VulnFlag.PREGNANT))
    @Test fun english5() = x("My brother was bitten by a snake near the river").check(
        Category.MEDICAL, 3, "snake bite", "river", 1, Lang.EN)
    @Test fun english6() = x("our house collapsed, we have no shelter, 4 people").check(
        Category.SHELTER, 1, "shelter", "", 4, Lang.EN)
    @Test fun collapsedWithPeopleUnderDebrisStaysRescue() = x("house collapsed, two people under the debris").check(
        Category.RESCUE, 3, "rescue", "", 2, Lang.EN, setOf(VulnFlag.TRAPPED))
    @Test fun english7() = x("my son is missing since the flood, last seen at the bus stand").check(
        Category.MISSING_PERSON, 1, "missing person", "bus stand", 1, Lang.EN)
    @Test fun english8() = x("my 80 year old grandmother has fever and needs medicine").check(
        Category.MEDICAL, 2, "medicine", "", 1, Lang.EN, setOf(VulnFlag.ELDERLY))
    @Test fun englishUnconsciousNotBreathing() {
        x("he is unconscious and not breathing").check(Category.MEDICAL, 3, "first aid", "", 1, Lang.EN)
        x("drowning, please send a boat").check(Category.RESCUE, 3, "rescue", "", 1, Lang.EN)
    }
    @Test fun hospitalAsLandmarkIsNotAMedicalNeed() =
        x("no food since two days, we are near the hospital").check(Category.FOOD, 1, "food", "hospital", 1, Lang.EN)

    // ------------------------------------------------------------------ Hindi
    @Test fun hindiDevanagari1() = x("मेरी माँ को इंसुलिन चाहिए, स्कूल के पास").check(
        Category.MEDICAL, 3, "insulin", "school", 1, Lang.HI)
    @Test fun hindiDevanagari2() = x("हम 6 लोग छत पर फंसे हैं, बाढ़ का पानी बढ़ रहा है, मंदिर के पास").check(
        Category.RESCUE, 2, "rescue", "temple", 6, Lang.HI, setOf(VulnFlag.TRAPPED))
    @Test fun hindiDevanagari3() = x("बच्चा बुखार से बेहोश है, दवा चाहिए, अस्पताल के पीछे").check(
        Category.MEDICAL, 3, "medicine", "hospital", 1, Lang.HI, setOf(VulnFlag.CHILD))
    @Test fun hindiHinglish1() = x("meri maa ko dawai chahiye, mandir ke paas").check(
        Category.MEDICAL, 2, "medicine", "temple", 1, Lang.HI)
    @Test fun hindiHinglish2() = x("hum 5 log chhat par fase hain, baadh aa gayi, bachao").check(
        Category.RESCUE, 2, "rescue", "", 5, Lang.HI, setOf(VulnFlag.TRAPPED))
    @Test fun hindiHinglish3() = x("dadi ko khoon aa raha hai, school ke paas").check(
        Category.MEDICAL, 3, "first aid", "school", 1, Lang.HI, setOf(VulnFlag.ELDERLY, VulnFlag.INJURED))
    @Test fun hindiHinglish4() = x("bachcha bhookha hai, khana chahiye, panchayat ke paas").check(
        Category.FOOD, 1, "food", "panchayat office", 1, Lang.HI, setOf(VulnFlag.CHILD))

    // ------------------------------------------------------------------ Odia
    @Test fun odiaScript1() = x("ମୋ ମାଙ୍କୁ ଇନସୁଲିନ ଦରକାର, ସ୍କୁଲ ପାଖରେ").check(
        Category.MEDICAL, 3, "insulin", "school", 1, Lang.OR)
    @Test fun odiaScript2() = x("ଆମେ ୪ ଜଣ ଛାତ ଉପରେ ଫସି ରହିଛୁ, ବନ୍ୟା").check(
        Category.RESCUE, 2, "rescue", "", 4, Lang.OR, setOf(VulnFlag.TRAPPED))
    @Test fun odiaScript3() = x("ଝିଅ ଜ୍ୱର ହୋଇଛି, ଔଷଧ ଦରକାର, ମନ୍ଦିର ପାଖରେ").check(
        Category.MEDICAL, 2, "medicine", "temple", 1, Lang.OR)
    @Test fun odiaScript4() = x("ପାଣି ଓ ଖାଦ୍ୟ ଦରକାର").check(Category.WATER, 1, "water", "", null, Lang.OR)
    @Test fun odiaRoman1() = x("mo maa ku ausadha darkar, school pakhare").check(
        Category.MEDICAL, 2, "medicine", "school", 1, Lang.OR)
    @Test fun odiaRoman2() = x("amaku pani darkar, 5 jane ghara bhasi gala, bapa ku bukhar").check(
        lang = Lang.OR, people = 5)
    @Test fun odiaRoman3() = x("jhia ku sapa kamudichhi, hospital nikate").check(
        Category.MEDICAL, 3, "snake bite", "hospital", 1, Lang.OR)

    // ------------------------------------------------------------------ Tagalog
    @Test fun tagalog1() = x("Kailangan namin ng gamot, may sugatan, malapit sa barangay hall").check(
        Category.MEDICAL, 2, "medicine", "barangay hall", 2, Lang.TL, setOf(VulnFlag.INJURED))
    @Test fun tagalog2() = x("Naipit kami sa bubong, tulong po, malapit sa simbahan").check(
        Category.RESCUE, 3, "rescue", "church", 2, Lang.TL, setOf(VulnFlag.TRAPPED))
    @Test fun tagalog3() = x("Nagugutom na kami, walang pagkain, lima kami").check(
        Category.FOOD, 1, "food", "", 5, Lang.TL)
    @Test fun tagalog4() = x("Manganganak na ang nanay ko, tulong po, sa tabi ng ospital").check(
        Category.MEDICAL, 3, "delivery help", "hospital", null, Lang.TL)

    // ------------------------------------------------------------------ Cebuano
    @Test fun cebuano1() = x("Kinahanglan mi og tambal, naa mi sa atop, duol sa eskwelahan").check(
        Category.MEDICAL, 2, "medicine", "school", 2, Lang.CEB)
    @Test fun cebuano2() = x("Palihug tabang, nalumos ang bata, duol sa suba").check(
        Category.RESCUE, 3, "rescue", "river", 1, Lang.CEB, setOf(VulnFlag.CHILD))
    @Test fun cebuano3() = x("Gigutom mi, wala mi pagkaon, tulo ka bata").check(
        Category.FOOD, 1, "food", "", 3, Lang.CEB, setOf(VulnFlag.CHILD))
    @Test fun cebuano4() = x("ang akong lola nagdugo, dili makaginhawa, duol sa simbahan").check(
        Category.MEDICAL, 3, null, "church", 1, Lang.CEB, setOf(VulnFlag.ELDERLY, VulnFlag.INJURED))

    // ------------------------------------------------------------------ Waray
    @Test fun waray1() = x("Kinahanglan kami hin tambal, harani ha eskwelahan, bulig waray").check(
        Category.MEDICAL, 2, "medicine", "school", 2, Lang.WAR)
    @Test fun waray2() = x("Nasagpan kami ha atop, nalunod an bata, bulig, harani han ilog").check(
        Category.RESCUE, 3, "rescue", "river", 2, Lang.WAR)
    @Test fun waray3() = x("Nagugutom kami, waray pagkaon, tulo kami, harani han simbahan, bulig").check(
        Category.FOOD, 1, "food", "church", 3, Lang.WAR)

    // ------------------------------------------------------------------ behaviour
    @Test fun unknownTextIsOtherUrgencyOneWithTruncatedInput() {
        val text = "hello this is just a normal sentence about the weather being very nice today"
        val d = x(text)
        d.check(Category.OTHER, 1, "", "", 1)
        assertTrue(text.startsWith(d.summary))
        assertTrue(d.summary.toByteArray().size <= 40 && d.summary.isNotEmpty())
        x("").check(Category.OTHER, 1)
        assertEquals("", x("   ").summary)
    }

    @Test fun langHintBreaksTiesButScriptWins() {
        assertEquals(Lang.CEB, x("insulin", Lang.CEB).lang)
        assertEquals(Lang.HI, x("insulin chahiye", Lang.EN).lang)
        assertEquals(Lang.HI, x("इंसुलिन", Lang.CEB).lang)
        assertEquals(Lang.EN, x("insulin").lang)
        assertEquals(Lang.EN, x("my mother needs insulin, near the school", Lang.OR).lang)
    }

    @Test fun peopleParsing() {
        assertEquals(12, x("12 people on the roof").people)
        assertEquals(5, x("3 adults and 2 children need water").people)
        assertEquals(4, x("family of four needs food").people)
        assertEquals(7, x("hum saat log fase hain").people)
        assertEquals(1, x("80 years old woman needs medicine").people)
        assertEquals(2, x("we need water").people)
    }

    @Test fun agesSetFlags() {
        assertTrue(VulnFlag.ELDERLY in x("my father 75 years old needs medicine").vulnFlags)
        assertTrue(VulnFlag.CHILD in x("a 3 year old needs medicine").vulnFlags)
        assertTrue(x("a 30 year old man needs medicine").vulnFlags.isEmpty())
    }

    @Test fun landmarkFallbackKeepsRawPhrase() {
        x("need food near the old banyan tree by the river ghat").check(landmark = "tree")
        x("need food near Sharma uncle house, please").check(landmark = "sharma uncle house")
        val long = x("need food near the extraordinarily long named community gathering pavilion")
        assertTrue(long.landmark.toByteArray().size <= 24)
    }

    @Test fun neverThrowsOnGarbage() {
        val rnd = Random(2024)
        val alphabet = "abc xyz ,.!?\n\t१२३ଅକ😀\u0000\uD800'-()[]{}\\^\$*+|नमस्ते".toCharArray()
        repeat(5000) {
            val s = buildString { repeat(rnd.nextInt(200)) { append(alphabet[rnd.nextInt(alphabet.size)]) } }
            val d = RuleExtractor.extract(s, if (rnd.nextBoolean()) Lang.values()[rnd.nextInt(6)] else null)
            assertTrue(d.summary.toByteArray().size <= 40)
        }
        RuleExtractor.extract("x".repeat(100_000)).check()
        RuleExtractor.extract("near the " + "a ".repeat(50_000))
        RuleExtractor.extract("5 ".repeat(30_000) + "people")
    }

    @Test fun draftBuildsAValidWireNeed() {
        val d = x("my mother needs insulin, near the school")
        val need = d.toNeed(msgId = ByteArray(8) { 1 }, originNode = ByteArray(8) { 2 }, createdAtSec = 1_790_000_000,
            latE5 = 2029612, lonE5 = 8582451, locAcc = 1, lang = Lang.OR)
        val payload = NeedCodec.encode(need)
        assertTrue(payload.size <= 128)
        val back = NeedCodec.decode(payload)!!
        assertEquals(need, back)
        assertEquals(Lang.OR, back.lang)
        assertEquals("insulin", back.item)
    }
}
