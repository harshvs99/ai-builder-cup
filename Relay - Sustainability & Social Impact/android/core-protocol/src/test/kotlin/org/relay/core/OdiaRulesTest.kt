package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.nlu.RuleExtractor
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.VulnFlag

/** Odia-script (and romanised Odia) cases added with the rule-parser fixes, plus the negation / supply rules. */
class OdiaRulesTest {
    private fun x(t: String) = RuleExtractor.extract(t)

    @Test fun roofAndRisingWaterIsRescueNotAWaterRequest() {
        val d = x("ଆମେ ପାଞ୍ଚ ଜଣ ଛାତ ଉପରେ ଫସି ରହିଛୁ, ପାଣି ବଢୁଛି")
        assertEquals(Category.RESCUE, d.category); assertEquals("rescue", d.item); assertEquals(5, d.people)
        assertEquals(setOf(VulnFlag.TRAPPED), d.vulnFlags); assertEquals(Lang.OR, d.lang)
    }

    @Test fun drinkingWater() {
        val d = x("ପିଇବା ପାଣି ନାହିଁ, ଦୁଇ ଦିନ ହେଲା")
        assertEquals(Category.WATER, d.category); assertEquals("water", d.item); assertEquals(Lang.OR, d.lang)
    }

    @Test fun hungryChildren() {
        val d = x("ଖାଇବାକୁ କିଛି ନାହିଁ, ପିଲାମାନେ ଭୋକିଲା")
        assertEquals(Category.FOOD, d.category); assertTrue(VulnFlag.CHILD in d.vulnFlags)
    }

    @Test fun unconsciousFatherAsksForDoctor() {
        val d = x("ମୋ ବାପା ବେହୋସ ହୋଇଯାଇଛନ୍ତି, ଡାକ୍ତର ଦରକାର")
        assertEquals(Category.MEDICAL, d.category); assertEquals(3, d.urgency); assertEquals("doctor", d.item)
    }

    @Test fun houseCollapsedNeedsShelter() {
        val d = x("ଆମ ଘର ଭାଙ୍ଗିଗଲା, ଆଶ୍ରୟ ଦରକାର")
        assertEquals(Category.SHELTER, d.category); assertEquals("shelter", d.item)
    }

    @Test fun missingSonLastSeenAtBusStand() {
        val d = x("ମୋ ପୁଅ ନିଖୋଜ, ଶେଷଥର ବସ ଷ୍ଟାଣ୍ଡ ପାଖରେ ଦେଖିଥିଲି")
        assertEquals(Category.MISSING_PERSON, d.category); assertEquals("bus stand", d.landmark)
    }

    @Test fun labourWithAmbulance() {
        val d = x("ଗର୍ଭବତୀ ମହିଳା, ପ୍ରସବ ବେଦନା, ଆମ୍ବୁଲାନ୍ସ ପଠାନ୍ତୁ")
        assertEquals(Category.MEDICAL, d.category); assertEquals(3, d.urgency); assertEquals("ambulance", d.item)
        assertTrue(VulnFlag.PREGNANT in d.vulnFlags)
    }

    @Test fun snakeBiteNearHospital() {
        val d = x("ସାପ କାମୁଡ଼ିଛି, ହସ୍ପିଟାଲ ନିକଟରେ")
        assertEquals("snake bite", d.item); assertEquals("hospital", d.landmark)
    }

    @Test fun floodRescueWithOdiaDigit() {
        val d = x("ବନ୍ୟାରେ ଆମେ ୬ ଜଣ ଫସିଯାଇଛୁ, ଉଦ୍ଧାର କରନ୍ତୁ")
        assertEquals(Category.RESCUE, d.category); assertEquals(6, d.people)
    }

    @Test fun insulinWithApostropheMother() {
        val d = x("ମୋ ମା'ଙ୍କୁ ଇନସୁଲିନ ଦରକାର, ସ୍କୁଲ ପାଖରେ")
        assertEquals("insulin", d.item); assertEquals("school", d.landmark)
    }

    @Test fun romanisedOdiaIsTaggedOdia() {
        val d = x("pina pani nahi, dui dina hela")
        assertEquals(Lang.OR, d.lang); assertEquals(Category.WATER, d.category)
        val f = x("amey 5 jane chhata upare phasi achhu, pani badhuchhi")
        assertEquals(Category.RESCUE, f.category); assertEquals(5, f.people)
    }

    // ---- supplies already in hand / negation
    @Test fun haveFoodButNoWater() {
        assertEquals(Category.WATER, x("We have food but we have no water, please send water.").category)
        assertEquals(Category.FOOD, x("हमारे पास पानी है लेकिन खाना नहीं है").category)
        assertEquals(Category.WATER, x("Khana to hai lekin peene ka paani nahi hai").category)
    }

    @Test fun floodWaterIsNotASupply() {
        assertEquals(Category.RESCUE, x("we have water inside the house, stuck on the roof").category)
    }
}
