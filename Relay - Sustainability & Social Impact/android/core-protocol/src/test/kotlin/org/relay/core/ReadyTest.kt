package org.relay.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.relay.core.ready.Household
import org.relay.core.ready.HttpReadyApi
import org.relay.core.ready.PackQuery
import org.relay.core.ready.PlanKey
import org.relay.core.ready.ReadyApi
import org.relay.core.ready.ReadyApiException
import org.relay.core.ready.ReadyJson
import org.relay.core.ready.ReadyPolicy
import org.relay.core.ready.ReadyRefresher
import org.relay.core.ready.ReadyStore
import org.relay.core.ready.RefreshInput
import org.relay.core.ready.RefreshTrigger
import java.io.File
import java.net.InetSocketAddress

/** Ready mode on the phone (contract v2 section 3.3): parsing, atomic storage, refresh decisions. Mirrored by Swift `ReadyTests`. */
class ReadyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun packJson(version: String = "v1", warningId: Long? = 4, source: String = "published", extra: String = "", district: String = "puri") = """{
        "district":"$district","lang":"hi","generated_at":1792336961,"version":"$version",
        "warning":${if (warningId == null) "null" else """{"id":$warningId,"source":"IMD","severity":"warning","headline":"Cyclone warning","issued_at":1792330000,"expected_landfall_at":1792400000,"expires_at":null}"""},
        "shelters":[{"id":"s1","name":"Zilla School","name_en":"Zilla School","kind":"school","lat":19.807,"lon":85.835,"capacity":600,"facilities":["water","toilets"],"block":"Puri Sadar","demo":false},
                    {"id":"s2","name":"Cyclone Shelter 4","kind":"cyclone_shelter","lat":19.812,"lon":85.828}],
        "shelters_source":"$source","shelters_published_at":1792300000,"checklist":["Pack a bag","Charge the phone"],"checklist_lang":"en"$extra}"""

    private fun planJson(planId: String = "plan1", warningId: Long? = 4, audio: Boolean = true, extra: String = "") = """{
        "plan_id":"$planId","lang":"hi","steps_lang":"en","district":"puri","steps":["Go to Zilla School","Pack a bag"],"source":"template",
        "warning_id":${warningId ?: "null"},"nearest_shelters":[{"id":"s1","name":"Zilla School","distance_km":1.2}],
        "audio_url":${if (audio) "\"/v1/ready/plan/$planId/audio\"" else "null"}$extra}"""

    private class FakeApi : ReadyApi {
        var pack = ""
        var plan = ""
        var wav: ByteArray? = byteArrayOf(1, 2, 3)
        var failPack: ReadyApiException? = null
        var failAudio: ReadyApiException? = null
        val packQueries = ArrayList<PackQuery>()
        val planCalls = ArrayList<Triple<String, Double?, String?>>()
        var audioCalls = 0
        override fun pack(query: PackQuery, lang: String): String { packQueries += query; failPack?.let { throw it }; return pack }
        override fun plan(lang: String, lat: Double?, lon: Double?, district: String?, household: Household): String {
            planCalls += Triple(lang, lat, district); return plan
        }
        override fun audio(audioUrl: String): ByteArray? { audioCalls++; failAudio?.let { throw it }; return wav }
    }

    private var now = 1_792_340_000_000L
    private val here = RefreshInput(true, 19.8, 85.8, "hi", Household(people = 4))
    private fun fixture(api: FakeApi = FakeApi().also { it.pack = packJson(); it.plan = planJson() }): Triple<FakeApi, ReadyStore, ReadyRefresher> {
        val store = ReadyStore(tmp.newFolder())
        return Triple(api, store, ReadyRefresher(api, store) { now })
    }

    // ------------------------------------------------------------ parsing
    @Test fun packParsingIgnoresUnknownFieldsAndKeepsWhatItNeeds() {
        val p = ReadyJson.parsePack(packJson(extra = ""","future_field":{"a":1},"tiles_url":"https://x""""))!!
        assertEquals("puri", p.district); assertEquals("v1", p.version); assertEquals(1792336961L, p.generatedAt)
        assertEquals(4L, p.warning!!.id); assertEquals("IMD", p.warning!!.source); assertEquals(1792400000L, p.warning!!.expectedLandfallAt)
        assertNull(p.warning!!.expiresAt)
        assertEquals(2, p.shelters.size)
        assertEquals(listOf("water", "toilets"), p.shelters[0].facilities)
        assertEquals(600, p.shelters[0].capacity); assertNull(p.shelters[1].capacity)
        assertEquals("Cyclone Shelter 4", p.shelters[1].nameEn) // name_en missing: falls back to name
        assertTrue(p.isPublished); assertEquals(1792300000L, p.sheltersPublishedAt)
        assertEquals(listOf("Pack a bag", "Charge the phone"), p.checklist)
        assertNull(ReadyJson.parsePack(packJson(warningId = null))!!.warning)
    }

    @Test fun packWithoutTheFieldsTheScreensNeedIsRefused() {
        assertNull(ReadyJson.parsePack(null)); assertNull(ReadyJson.parsePack("")); assertNull(ReadyJson.parsePack("not json"))
        assertNull(ReadyJson.parsePack("""{"district":"puri","version":"v"}""")) // no shelters list
        assertNull(ReadyJson.parsePack("""{"district":"puri","shelters":[]}""")) // no version
        // a shelter without coordinates is skipped, not fatal
        val p = ReadyJson.parsePack("""{"district":"puri","version":"v","shelters":[{"id":"a","name":"A"},{"id":"b","name":"B","lat":1.0,"lon":2.0}]}""")!!
        assertEquals(listOf("b"), p.shelters.map { it.id })
    }

    @Test fun planParsingIgnoresUnknownFields() {
        val p = ReadyJson.parsePlan(planJson(extra = ""","future":[1,2]"""))!!
        assertEquals("plan1", p.planId); assertEquals("hi", p.lang); assertEquals("en", p.stepsLang)
        assertEquals(listOf("Go to Zilla School", "Pack a bag"), p.steps)
        assertEquals(4L, p.warningId); assertEquals("/v1/ready/plan/plan1/audio", p.audioUrl)
        assertEquals("Zilla School", p.nearestShelters.single().name); assertEquals(1.2, p.nearestShelters.single().distanceKm, 1e-9)
        assertNull(ReadyJson.parsePlan(planJson(audio = false))!!.audioUrl)
        assertNull(ReadyJson.parsePlan("""{"plan_id":"x","steps":[]}""")); assertNull(ReadyJson.parsePlan("""{"steps":["a"]}"""))
    }

    @Test fun householdIsLimitedAndKeyedByContent() {
        assertEquals(20, Household(people = 99).normalized().people); assertEquals(1, Household(people = 0).normalized().people)
        assertEquals("a".repeat(100), Household(medicalNeeds = "  " + "a".repeat(150)).normalized().medicalNeeds)
        assertEquals(Household(people = 4, elderly = true).key, Household(people = 4, elderly = true, medicalNeeds = "  ").key)
        assertTrue(Household(people = 4).key != Household(people = 5).key)
        assertTrue(Household().key != Household(livestock = true).key)
        assertTrue(Household(medicalNeeds = "Insulin").key == Household(medicalNeeds = "insulin").key)
        val j = Household(people = 3, children = true, medicalNeeds = "oxygen").toJson()
        assertEquals(3, j.getInt("people")); assertTrue(j.getBoolean("children")); assertEquals("oxygen", j.getString("medical_needs"))
        assertEquals(Household(people = 3, children = true, medicalNeeds = "oxygen"), Household.fromJson(j.toString()))
        assertEquals(Household(), Household.fromJson("garbage"))
    }

    // ------------------------------------------------------------ decisions
    @Test fun refreshDoesNothingWithoutConnectivity() {
        val (api, store, r) = fixture()
        val res = r.refresh(RefreshTrigger.APP_OPEN, here.copy(connected = false))
        assertTrue(res.skipped); assertTrue(api.packQueries.isEmpty()); assertNull(store.load().pack)
        assertFalse(ReadyPolicy.shouldFetchPack(RefreshTrigger.MANUAL, false, 0, now))
    }

    @Test fun appOpenAndNetworkRegainedFetchAtOnceButNotTwiceInAMinute() {
        val h = 3600_000L
        assertTrue(ReadyPolicy.shouldFetchPack(RefreshTrigger.APP_OPEN, true, 0, now))
        assertTrue(ReadyPolicy.shouldFetchPack(RefreshTrigger.NETWORK_REGAINED, true, now - 61_000, now))
        assertFalse(ReadyPolicy.shouldFetchPack(RefreshTrigger.APP_OPEN, true, now - 30_000, now))
        assertFalse(ReadyPolicy.shouldFetchPack(RefreshTrigger.NETWORK_REGAINED, true, now - 30_000, now))
        assertTrue(ReadyPolicy.shouldFetchPack(RefreshTrigger.APP_OPEN, true, now - h, now))
        assertTrue(ReadyPolicy.shouldFetchPack(RefreshTrigger.APP_OPEN, true, now + 5 * h, now)) // the clock went backwards: do not wait for ever
    }

    @Test fun periodicRefreshWaitsThreeHours() {
        val h = 3600_000L
        assertTrue(ReadyPolicy.shouldFetchPack(RefreshTrigger.PERIODIC, true, 0, now))
        assertFalse(ReadyPolicy.shouldFetchPack(RefreshTrigger.PERIODIC, true, now - 2 * h, now))
        assertFalse(ReadyPolicy.shouldFetchPack(RefreshTrigger.BACKGROUND, true, now - 3 * h + 1, now))
        assertTrue(ReadyPolicy.shouldFetchPack(RefreshTrigger.PERIODIC, true, now - 3 * h, now))
        assertTrue(ReadyPolicy.shouldFetchPack(RefreshTrigger.MANUAL, true, now - 1000, now))
    }

    @Test fun aWarningFetchesPackThenPlanThenAudio() {
        val (api, store, r) = fixture()
        val res = r.refresh(RefreshTrigger.APP_OPEN, here)
        assertTrue(res.packFetched && res.planFetched && res.audioFetched); assertNull(res.error)
        assertEquals(listOf<PackQuery>(PackQuery.At(19.8, 85.8)), api.packQueries)
        val s = store.load()
        assertEquals("v1", s.pack!!.version); assertEquals("plan1", s.plan!!.planId)
        assertEquals(PlanKey("v1", here.household.key, "hi", 4L), s.planKey)
        assertArrayEquals(byteArrayOf(1, 2, 3), s.audio!!.readBytes())
        assertEquals("puri", s.district); assertEquals(now, s.lastPackFetchMs)
        assertEquals(s.plan, s.currentPlan)
    }

    @Test fun noWarningMeansNoPlanAndTheOldPlanIsNotShown() {
        val (api, store, r) = fixture()
        r.refresh(RefreshTrigger.APP_OPEN, here)
        now += 4 * 3600_000L
        api.pack = packJson(version = "v2", warningId = null)
        val res = r.refresh(RefreshTrigger.PERIODIC, here)
        assertTrue(res.packFetched); assertFalse(res.planFetched); assertEquals(1, api.planCalls.size)
        assertNotNull(store.load().plan); assertNull(store.load().currentPlan) // kept on disk, but it answers a warning that is over
    }

    @Test fun planIsFetchedAgainOnlyWhenVersionHouseholdLanguageOrWarningChange() {
        val (api, store, r) = fixture()
        r.refresh(RefreshTrigger.APP_OPEN, here); assertEquals(1, api.planCalls.size)
        now += 4 * 3600_000L
        r.refresh(RefreshTrigger.PERIODIC, here)                                                    // same everything
        assertEquals(1, api.planCalls.size)
        now += 4 * 3600_000L
        r.refresh(RefreshTrigger.PERIODIC, here.copy(household = Household(people = 5)))            // household changed
        assertEquals(2, api.planCalls.size)
        now += 4 * 3600_000L
        r.refresh(RefreshTrigger.PERIODIC, here.copy(household = Household(people = 5), lang = "en")) // language changed
        assertEquals(3, api.planCalls.size)
        now += 4 * 3600_000L
        api.pack = packJson(version = "v2")
        r.refresh(RefreshTrigger.PERIODIC, here.copy(household = Household(people = 5), lang = "en")) // pack version changed
        assertEquals(4, api.planCalls.size)
        assertEquals("v2", store.load().planKey!!.packVersion)
    }

    @Test fun audioIsFetchedOnlyWhenOfferedAndNotAlreadyStored() {
        val (api, store, r) = fixture()
        r.refresh(RefreshTrigger.APP_OPEN, here); assertEquals(1, api.audioCalls)
        now += 4 * 3600_000L
        r.refresh(RefreshTrigger.PERIODIC, here); assertEquals(1, api.audioCalls) // stored already
        val none = fixture(FakeApi().also { it.pack = packJson(); it.plan = planJson(audio = false) })
        none.third.refresh(RefreshTrigger.APP_OPEN, here); assertEquals(0, none.first.audioCalls)
        val missing = fixture(FakeApi().also { it.pack = packJson(); it.plan = planJson(); it.wav = null })  // 404 / 503: on-device speech
        val res = missing.third.refresh(RefreshTrigger.APP_OPEN, here)
        assertTrue(res.packFetched && res.planFetched); assertFalse(res.audioFetched); assertNull(res.error); assertNull(missing.second.load().audio)
    }

    @Test fun aNewWarningNotifiesOncePerWarningId() {
        val (api, store, r) = fixture()
        val first = r.refresh(RefreshTrigger.APP_OPEN, here)
        assertEquals(4L, first.newWarning!!.id)
        r.noteNotified(first.newWarning!!.id)
        now += 4 * 3600_000L
        assertNull(r.refresh(RefreshTrigger.PERIODIC, here).newWarning)             // same warning, new fetch
        now += 4 * 3600_000L
        api.pack = packJson(version = "v2", warningId = 5)
        assertEquals(5L, r.refresh(RefreshTrigger.PERIODIC, here).newWarning!!.id)   // a different warning id
        assertEquals(4L, store.load().notifiedWarningId)                              // until the app says it told the person
        assertFalse(ReadyPolicy.shouldNotify(ReadyJson.parsePack(packJson(warningId = null))!!, null))
    }

    @Test fun noPositionAndNoDistrictAsksForOneThenUsesIt() {
        val (api, store, r) = fixture()
        val blind = here.copy(lat = null, lon = null)
        val res = r.refresh(RefreshTrigger.APP_OPEN, blind)
        assertTrue(res.skipped && res.needsDistrict); assertTrue(api.packQueries.isEmpty())
        store.setDistrict("delhi")
        assertTrue(r.refresh(RefreshTrigger.APP_OPEN, blind).packFetched)
        assertEquals(listOf<PackQuery>(PackQuery.District("delhi")), api.packQueries)
        assertEquals(listOf(Triple("hi", null as Double?, "puri")), api.planCalls) // the plan names the pack's district when there is no position
    }

    @Test fun theDistrictIsLearnedFromThePack() {
        val (_, store, r) = fixture(FakeApi().also { it.pack = packJson(district = "delhi"); it.plan = planJson() })
        r.refresh(RefreshTrigger.APP_OPEN, here)
        assertEquals("delhi", store.load().district)
    }

    @Test fun aFailingBackendKeepsTheStoredCopyAndIsNotRetriedInATightLoop() {
        val (api, store, r) = fixture()
        r.refresh(RefreshTrigger.APP_OPEN, here)
        now += 4 * 3600_000L
        api.failPack = ReadyApiException("HTTP 503", 503)
        val res = r.refresh(RefreshTrigger.PERIODIC, here)
        assertEquals("HTTP 503", res.error); assertFalse(res.packFetched)
        assertEquals("v1", store.load().pack!!.version)
        assertTrue(r.refresh(RefreshTrigger.APP_OPEN, here).skipped) // the attempt counts: not again within the minute
        api.pack = "garbage"; api.failPack = null; now += 4 * 3600_000L
        assertEquals("unreadable pack", r.refresh(RefreshTrigger.PERIODIC, here).error)
        assertEquals("v1", store.load().pack!!.version)
    }

    @Test fun aFailingPlanOrAudioStillLeavesTheNewPackStored() {
        val api = FakeApi().also { it.pack = packJson(); it.plan = "garbage" }
        val (_, store, r) = fixture(api)
        val res = r.refresh(RefreshTrigger.APP_OPEN, here)
        assertTrue(res.packFetched); assertFalse(res.planFetched); assertEquals("unreadable plan", res.error)
        assertEquals("v1", store.load().pack!!.version)
        api.plan = planJson(); api.failAudio = ReadyApiException("timeout")
        now += 4 * 3600_000L
        val res2 = r.refresh(RefreshTrigger.PERIODIC, here)
        assertTrue(res2.planFetched); assertFalse(res2.audioFetched); assertEquals("timeout", res2.error)
        assertNotNull(store.load().plan)
    }

    // ------------------------------------------------------------ storage
    @Test fun storageKeepsThePreviousCopyUntilTheNewOneIsFullyWritten() {
        val dir = tmp.newFolder()
        val raw1 = packJson(version = "v1"); val raw2 = packJson(version = "v2")
        ReadyStore(dir).savePack(raw1, ReadyJson.parsePack(raw1)!!, 1)
        // crash after the new data file is written but before the pointer names it
        val crashing = ReadyStore(dir) { stage -> if (stage == "pack") throw IllegalStateException("power cut") }
        try { crashing.savePack(raw2, ReadyJson.parsePack(raw2)!!, 2); error("expected the crash") } catch (_: IllegalStateException) {}
        assertEquals("v1", ReadyStore(dir).load().pack!!.version)
        assertEquals(1L, ReadyStore(dir).load().lastPackFetchMs)
        // the next successful save prunes the orphan
        ReadyStore(dir).savePack(raw2, ReadyJson.parsePack(raw2)!!, 3)
        assertEquals("v2", ReadyStore(dir).load().pack!!.version)
        assertEquals(listOf("pack-v2.json", "ready_state.json"), dir.list()!!.sorted())
    }

    @Test fun storageIsKeyedByVersionAndPlanIdAndDropsWhatIsReplaced() {
        val dir = tmp.newFolder(); val s = ReadyStore(dir)
        val p = packJson(version = "v1"); s.savePack(p, ReadyJson.parsePack(p)!!, 1)
        val pl1 = planJson("planA"); s.savePlan(pl1, ReadyJson.parsePlan(pl1)!!, PlanKey("v1", "h", "hi", 4)); s.saveAudio("planA", byteArrayOf(9))
        assertTrue(File(dir, "plan-planA.json").isFile && File(dir, "audio-planA.wav").isFile)
        val pl2 = planJson("planB"); s.savePlan(pl2, ReadyJson.parsePlan(pl2)!!, PlanKey("v1", "h2", "hi", 4))
        assertFalse(File(dir, "plan-planA.json").exists()); assertFalse(File(dir, "audio-planA.wav").exists())
        assertNull(s.load().audio); assertEquals("planB", s.load().plan!!.planId)
        s.saveAudio("planA", byteArrayOf(1)) // the audio of a plan that is no longer the stored one is ignored
        assertNull(s.load().audio)
        s.saveAudio("planB", byteArrayOf(7)); assertArrayEquals(byteArrayOf(7), s.load().audio!!.readBytes())
    }

    @Test fun storedBodiesKeepFieldsThisBuildDoesNotKnow() {
        val dir = tmp.newFolder(); val s = ReadyStore(dir)
        val p = packJson(extra = ""","tiles_url":"https://x/tiles""""); s.savePack(p, ReadyJson.parsePack(p)!!, 1)
        assertTrue(File(dir, "pack-v1.json").readText().contains("tiles_url"))
    }

    @Test fun aDamagedPointerOrBodyReadsAsNothingStoredNotACrash() {
        val dir = tmp.newFolder(); val s = ReadyStore(dir)
        val p = packJson(); s.savePack(p, ReadyJson.parsePack(p)!!, 1)
        File(dir, "ready_state.json").writeText("{ nope")
        assertNull(ReadyStore(dir).load().pack)
        s.savePack(p, ReadyJson.parsePack(p)!!, 2)
        File(dir, "pack-v1.json").writeText("garbage")
        assertNull(ReadyStore(dir).load().pack)
    }

    @Test fun checklistTicksAreKeptPerVersionAndResetWhenItChanges() {
        val s = ReadyStore(tmp.newFolder())
        s.setTick("v1", 0, true); s.setTick("v1", 2, true)
        assertEquals(setOf(0, 2), s.ticks("v1"))
        s.setTick("v1", 0, false); assertEquals(setOf(2), s.ticks("v1"))
        assertEquals(emptySet<Int>(), s.ticks("v2")) // a new version starts clean
        s.setTick("v2", 1, true); assertEquals(setOf(1), s.ticks("v2")); assertEquals(emptySet<Int>(), s.ticks("v1"))
    }

    @Test fun packFileNamesFromTheNetworkCannotEscapeTheFolder() {
        val dir = tmp.newFolder(); val s = ReadyStore(dir)
        val p = packJson(version = "../../evil"); s.savePack(p, ReadyJson.parsePack(p)!!, 1)
        assertTrue(dir.list()!!.all { !it.contains("/") && !it.contains("..") })
        assertEquals("../../evil", s.load().pack!!.version)
    }

    // ------------------------------------------------------------ http
    @Test fun httpApiSendsTheDeviceKeyAndTheRightRequests() {
        val seen = ArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val key = ex.requestHeaders.getFirst("X-Relay-Key")
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += "${ex.requestMethod} ${ex.requestURI} key=$key body=$body"
            val (status, out) = when {
                ex.requestURI.path == "/v1/ready/pack" -> 200 to packJson().toByteArray()
                ex.requestURI.path == "/v1/ready/plan" -> 200 to planJson().toByteArray()
                ex.requestURI.path == "/v1/ready/plan/plan1/audio" -> 200 to byteArrayOf(5, 6)
                ex.requestURI.path == "/v1/ready/plan/none/audio" -> 404 to """{"detail":"server audio is off"}""".toByteArray()
                else -> 503 to ByteArray(0)
            }
            ex.sendResponseHeaders(status, if (out.isEmpty()) -1 else out.size.toLong())
            if (out.isNotEmpty()) ex.responseBody.use { it.write(out) }
            ex.close()
        }
        server.start()
        try {
            val api = HttpReadyApi("http://127.0.0.1:${server.address.port}/", "k1")
            assertNotNull(ReadyJson.parsePack(api.pack(PackQuery.At(19.8, 85.8), "hi")))
            assertNotNull(ReadyJson.parsePack(api.pack(PackQuery.District("puri"), "en")))
            assertNotNull(ReadyJson.parsePlan(api.plan("hi", 19.8, 85.8, null, Household(people = 2, elderly = true))))
            assertNotNull(ReadyJson.parsePlan(api.plan("en", null, null, "delhi", Household())))
            assertArrayEquals(byteArrayOf(5, 6), api.audio("/v1/ready/plan/plan1/audio"))
            assertNull(api.audio("/v1/ready/plan/none/audio")) // 404: on-device speech
            try { api.pack(PackQuery.District("zz"), "en").also { } ; } catch (_: ReadyApiException) {}
            assertTrue(seen[0].startsWith("GET /v1/ready/pack?lat=19.8&lon=85.8&lang=hi key=k1"))
            assertTrue(seen[1].startsWith("GET /v1/ready/pack?district=puri&lang=en key=k1"))
            assertTrue(seen[2].startsWith("POST /v1/ready/plan key=k1") && seen[2].contains("\"lat\":19.8") && seen[2].contains("\"elderly\":true") && seen[2].contains("\"people\":2"))
            assertTrue(seen[3].contains("\"district\":\"delhi\"") && !seen[3].contains("\"lat\""))
        } finally {
            server.stop(0)
        }
    }

    @Test fun httpApiReportsAnErrorStatusAndAnUnreachableServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> val b = """{"detail":"unknown district 'zz'"}""".toByteArray(); ex.sendResponseHeaders(404, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.start()
        val port = server.address.port
        try {
            val e = try { HttpReadyApi("http://127.0.0.1:$port", "k").pack(PackQuery.District("zz"), "en"); null } catch (e: ReadyApiException) { e }
            assertEquals(404, e!!.status); assertTrue(e.message!!.contains("unknown district"))
        } finally { server.stop(0) }
        val down = try { HttpReadyApi("http://127.0.0.1:$port", "k", connectTimeoutMs = 500).pack(PackQuery.District("puri"), "en"); null } catch (e: ReadyApiException) { e }
        assertEquals(0, down!!.status)
    }
}
