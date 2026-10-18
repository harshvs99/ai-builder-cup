package org.relay.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.relay.app.gateway.GatewayState
import org.relay.app.gateway.GatewaySyncer
import org.relay.app.gateway.HintCounts
import org.relay.app.gateway.SyncClient
import org.relay.app.gateway.SyncConfig
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.InMemoryPacketStore
import org.relay.core.ids.Hex
import org.relay.core.payload.AckCodec
import org.relay.core.payload.AckStatus
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** Runs the real [SyncClient] / [GatewaySyncer] against a fake backend on a loopback port. */
class SyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Req(val key: String?, val contentType: String?, val body: JSONObject)

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Req>()
    @Volatile private var status = 200
    @Volatile private var responder: (JSONObject) -> JSONObject = { JSONObject().put("accepted", 0).put("duplicates", 0).put("rejected", 0).put("acks", JSONArray()).put("ack_cursor", 0) }

    private val gatewayId = TestSupport.id(0x40)
    private val cfg get() = SyncConfig("http://127.0.0.1:${server.address.port}/", "dev-relay-key") // trailing slash on purpose

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/sync") { ex: HttpExchange ->
            val body = JSONObject(String(ex.requestBody.readBytes(), Charsets.UTF_8))
            requests += Req(ex.requestHeaders.getFirst("X-Relay-Key"), ex.requestHeaders.getFirst("Content-Type"), body)
            val out = if (status in 200..299) responder(body).toString().toByteArray() else "boom".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
    }

    @After fun stopServer() { server.stop(0) }

    private fun syncer(node: org.relay.core.gossip.GossipNode, store: InMemoryPacketStore, state: GatewayState, logs: MutableList<String> = ArrayList()) =
        GatewaySyncer(node, store, state, SyncClient(connectTimeoutMs = 2_000, readTimeoutMs = 4_000), { cfg }, { logs += it })

    private fun framesIn(r: Req): List<ByteArray> =
        (0 until r.body.getJSONArray("frames").length()).map { Base64.getDecoder().decode(r.body.getJSONArray("frames").getString(it)) }

    @Test fun uploadsOnlyNewFramesAndSendsHeaderAndGatewayId() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        val state = GatewayState(null)
        val s = syncer(node, store, state)

        node.originateNeed(TestSupport.need(1)); node.originateNeed(TestSupport.need(2))
        val first = s.syncOnce()
        assertTrue(first.ok); assertEquals(2, first.uploaded)
        val r1 = requests.single()
        assertEquals("dev-relay-key", r1.key)
        assertEquals("application/json", r1.contentType)
        assertEquals(Hex.encode(gatewayId), r1.body.getString("gateway_node"))
        assertEquals(0L, r1.body.getLong("ack_cursor"))
        // frames are the exact stored raw bytes, standard base64 with padding
        val sent = framesIn(r1)
        val stored = store.all().filter { it.type == FrameTypes.NEED }.map { it.raw }
        assertEquals(2, sent.size)
        sent.forEachIndexed { i, f -> assertArrayEquals(stored[i], f) }
        assertTrue(r1.body.getJSONArray("frames").getString(0).let { it.length % 4 == 0 && !it.contains('\n') })

        // Second round: nothing new -> an empty upload (still polls for ACKs).
        assertEquals(0, s.syncOnce().uploaded)
        assertEquals(0, framesIn(requests[1]).size)

        // A third NEED uploads only that one.
        val n3 = TestSupport.need(3); node.originateNeed(n3)
        assertEquals(1, s.syncOnce().uploaded)
        val last = framesIn(requests[2])
        assertEquals(1, last.size)
        assertEquals(n3.msgIdHex, org.relay.core.payload.NeedCodec.decode(FrameCodec.decode(last[0])!!.payload)!!.msgIdHex)
    }

    @Test fun ackFramesInTheStoreAreNeverUploaded() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        node.originateAck(TestSupport.ack(9, TestSupport.id(1), TestSupport.id(2)))
        val s = syncer(node, store, GatewayState(null))
        assertEquals(0, s.syncOnce().uploaded)
    }

    @Test fun cursorIsSentPersistedAndReloaded() {
        val file = File(tmp.root, "gw.json")
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        responder = { JSONObject().put("accepted", 1).put("duplicates", 0).put("rejected", 0).put("acks", JSONArray()).put("ack_cursor", 17) }
        val state = GatewayState(file)
        node.originateNeed(TestSupport.need(1))
        val s = syncer(node, store, state)
        assertTrue(s.syncOnce().ok)
        assertEquals(17L, state.ackCursor())

        // "Restart": a fresh state from the same file remembers cursor and uploaded ids.
        val reloaded = GatewayState(file)
        assertEquals(17L, reloaded.ackCursor())
        assertTrue(reloaded.isUploaded(TestSupport.need(1).msgIdHex))
        val s2 = syncer(node, store, reloaded)
        assertEquals(0, s2.syncOnce().uploaded)
        assertEquals(17L, requests.last().body.getLong("ack_cursor"))
    }

    @Test fun returnedAckPayloadsAreWrappedAndOriginatedIntoTheNode() {
        val store = InMemoryPacketStore()
        val rec = TestSupport.Recorder()
        val myOrigin = TestSupport.id(0x70) // pretend this gateway phone is also the requester
        val node = TestSupport.node(myOrigin, store, rec)
        val need = TestSupport.need(1, origin = myOrigin)
        node.originateNeed(need)

        val ack1 = TestSupport.ack(0x31, need.msgId, myOrigin, ticket = 142, text = "Received. Ticket 142.")
        val ack2 = TestSupport.ack(0x32, need.msgId, myOrigin, ticket = 142, status = AckStatus.DISPATCHED, text = "Help dispatched")
        val foreign = TestSupport.ack(0x33, TestSupport.id(5), TestSupport.id(0x99), ticket = 9)
        responder = {
            JSONObject().put("accepted", 1).put("duplicates", 0).put("rejected", 0).put("ack_cursor", 3)
                .put("acks", JSONArray(listOf(ack1, ack2, foreign).map { Base64.getEncoder().encodeToString(AckCodec.encode(it)) }))
        }
        val state = GatewayState(null)
        val out = syncer(node, store, state).syncOnce()
        assertTrue(out.ok); assertEquals(3, out.acksOriginated)

        assertEquals("all three ACKs originated into the engine", 3, rec.acksSeen.size)
        assertEquals("only ACKs targeted at this node are shown", listOf(142L, 142L), rec.acksForMe.map { it.ticketNo })
        assertEquals(listOf(AckStatus.RECEIVED, AckStatus.DISPATCHED), rec.acksForMe.map { it.status })
        val storedAcks = node.storedAcks()
        assertEquals(3, storedAcks.size)
        val (_, frame) = storedAcks.first()
        assertEquals(FrameTypes.ACK, frame.type)
        assertEquals("ttl 7 at origination", 7, frame.ttl)
        assertArrayEquals("sender is the gateway", myOrigin, frame.senderId)
        assertNotNull(frame.recipientId)
        assertEquals(3L, state.ackCursor())
    }

    @Test fun serverErrorLeavesStateUntouchedAndNextRoundRetriesTheSameFrames() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        val state = GatewayState(null)
        node.originateNeed(TestSupport.need(1))
        val logs = ArrayList<String>()
        val s = syncer(node, store, state, logs)

        status = 500
        val bad = s.syncOnce()
        assertFalse(bad.ok); assertTrue(bad.error!!.contains("500"))
        assertEquals(0, state.uploadedCount()); assertEquals(0L, state.ackCursor())

        status = 200
        val good = s.syncOnce()
        assertTrue(good.ok); assertEquals(1, good.uploaded)
        assertEquals(1, framesIn(requests.last()).size)
        assertTrue(logs.any { it.startsWith("sync ok") })
    }

    @Test fun unreachableBackendIsReportedNotThrown() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        node.originateNeed(TestSupport.need(1))
        val dead = SyncConfig("http://127.0.0.1:1", "k")
        val s = GatewaySyncer(node, store, GatewayState(null), SyncClient(connectTimeoutMs = 500, readTimeoutMs = 500), { dead })
        val out = s.syncOnce()
        assertFalse(out.ok); assertNotNull(out.error)
    }

    @Test fun garbageResponseBodyIsAFailureAndUndecodableAckIsSkipped() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        val state = GatewayState(null)
        val s = syncer(node, store, state)
        responder = { JSONObject().put("ack_cursor", 4).put("acks", JSONArray(listOf(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), "!!notbase64!!"))) }
        val out = s.syncOnce()
        assertTrue(out.ok); assertEquals(0, out.acksOriginated)
        assertEquals(4L, state.ackCursor())

        responder = { JSONObject().put("nope", true) } // no ack_cursor
        assertFalse(s.syncOnce().ok)
    }

    @Test fun batchesAreCappedAndMoreWaitingIsReported() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        val state = GatewayState(null)
        repeat(5) { node.originateNeed(TestSupport.need(10 + it)) }
        val s = GatewaySyncer(node, store, state, SyncClient(), { cfg }, {}, maxFramesPerSync = 2)
        assertEquals(2, s.syncOnce().uploaded); assertTrue(s.moreWaiting)
        assertEquals(2, s.syncOnce().uploaded); assertTrue(s.moreWaiting)
        assertEquals(1, s.syncOnce().uploaded); assertFalse(s.moreWaiting)
        assertEquals(5, state.uploadedCount())
    }

    @Test fun clientParsesBackendResponseShape() {
        val c = SyncClient()
        val json = """{"accepted":3,"duplicates":1,"rejected":0,"acks":["AQID"],"ack_cursor":17}"""
        val r = c.parse(json)
        assertEquals(3, r.accepted); assertEquals(1, r.duplicates); assertEquals(17L, r.ackCursor)
        assertArrayEquals(byteArrayOf(1, 2, 3), r.acks.single())
        assertNull(runCatching { c.parse("not json") }.getOrNull())
    }

    // ---------------------------------------------------------------- LOC_HINT upload (SPEC 5.5)

    private fun typesIn(r: Req): List<Int> = framesIn(r).map { FrameCodec.decode(it)!!.type }

    @Test fun storedHintIsUploadedWithNeedsOnceAndNotAgain() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        val state = GatewayState(null)
        val s = syncer(node, store, state)
        node.originateNeed(TestSupport.need(1))
        val hint = TestSupport.hint(1, gatewayId)
        assertNotNull(node.originateLocHint(hint))

        val first = s.syncOnce()
        assertTrue(first.ok); assertEquals(2, first.uploaded); assertEquals(1, first.hintsUploaded)
        val r1 = requests.single()
        assertEquals(listOf(FrameTypes.NEED, FrameTypes.LOC_HINT), typesIn(r1))
        // the hint goes out as its exact stored raw bytes
        assertArrayEquals(store.all().first { it.type == FrameTypes.LOC_HINT }.raw, framesIn(r1)[1])
        assertTrue(state.isUploaded(hint.keyHex))
        assertTrue(s.pendingNeedKeys().isEmpty())

        // Second round: nothing new, so an empty upload.
        val second = s.syncOnce()
        assertEquals(0, second.uploaded); assertEquals(0, second.hintsUploaded)
        assertEquals(0, framesIn(requests[1]).size)

        // A later hint uploads alone.
        node.originateLocHint(TestSupport.hint(2, gatewayId))
        assertEquals(1, s.syncOnce().uploaded)
        assertEquals(listOf(FrameTypes.LOC_HINT), typesIn(requests[2]))
    }

    @Test fun pendingNeedKeysIgnoreHintsAndAFailedRoundKeepsTheHintForRetry() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        val state = GatewayState(null)
        val s = syncer(node, store, state)
        val hint = TestSupport.hint(1, gatewayId)
        node.originateLocHint(hint)
        assertTrue("hints are not NEEDs", s.pendingNeedKeys().isEmpty())

        status = 500
        assertFalse(s.syncOnce().ok)
        assertFalse(state.isUploaded(hint.keyHex))

        status = 200
        val ok = s.syncOnce()
        assertTrue(ok.ok); assertEquals(1, ok.uploaded); assertEquals(1, ok.hintsUploaded)
        assertTrue(state.isUploaded(hint.keyHex))
    }

    @Test fun needsGoBeforeHintsUnderTheCapAndMoreWaitingCountsBoth() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        val state = GatewayState(null)
        // Hints are stored first, NEEDs after: the NEEDs must still be uploaded first.
        node.originateLocHint(TestSupport.hint(1, gatewayId))
        node.originateLocHint(TestSupport.hint(2, gatewayId))
        repeat(3) { node.originateNeed(TestSupport.need(10 + it)) }
        val s = GatewaySyncer(node, store, state, SyncClient(), { cfg }, {}, maxFramesPerSync = 4)

        val a = s.syncOnce()
        assertEquals(4, a.uploaded); assertEquals(1, a.hintsUploaded)
        assertEquals(
            listOf(FrameTypes.NEED, FrameTypes.NEED, FrameTypes.NEED, FrameTypes.LOC_HINT),
            typesIn(requests[0]),
        )
        assertTrue(s.moreWaiting)

        val b = s.syncOnce()
        assertEquals(1, b.uploaded); assertEquals(1, b.hintsUploaded)
        assertEquals(listOf(FrameTypes.LOC_HINT), typesIn(requests[1]))
        assertFalse(s.moreWaiting)
        assertEquals(5, state.uploadedCount())
    }

    @Test fun outcomeReportsHintsAcceptedOnlyWhenTheBackendSentTheHintsObject() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        val s = syncer(node, store, GatewayState(null))
        node.originateLocHint(TestSupport.hint(1, gatewayId))

        responder = {
            JSONObject().put("accepted", 0).put("duplicates", 0).put("rejected", 0).put("acks", JSONArray()).put("ack_cursor", 1)
                .put("hints", JSONObject().put("accepted", 1).put("duplicates", 0).put("rejected", 0))
        }
        val withHints = s.syncOnce()
        assertTrue(withHints.ok); assertEquals(1, withHints.hintsAccepted)

        node.originateLocHint(TestSupport.hint(2, gatewayId))
        responder = { JSONObject().put("accepted", 0).put("duplicates", 0).put("rejected", 0).put("acks", JSONArray()).put("ack_cursor", 2) }
        val without = s.syncOnce()
        assertTrue(without.ok); assertEquals(1, without.hintsUploaded); assertNull(without.hintsAccepted)
    }

    @Test fun clientParsingToleratesTheOptionalHintsObject() {
        val c = SyncClient()
        val base = "\"accepted\":1,\"duplicates\":0,\"rejected\":0,\"acks\":[],\"ack_cursor\":2"
        assertNull(c.parse("{$base}").hints)
        assertEquals(HintCounts(2, 1, 3), c.parse("{$base,\"hints\":{\"accepted\":2,\"duplicates\":1,\"rejected\":3}}").hints)
        assertEquals(HintCounts(4, 0, 0), c.parse("{$base,\"hints\":{\"accepted\":4}}").hints)
        // A malformed hints value is ignored, not a failure.
        assertNull(c.parse("{$base,\"hints\":\"nope\"}").hints)
        assertNull(c.parse("{$base,\"hints\":null}").hints)
        assertEquals(2L, c.parse("{$base,\"hints\":[1]}").ackCursor)
    }

    // ------------------------------------------------------------ control-room broadcasts (SPEC 5.6)
    private fun broadcastPayload(type: org.relay.core.triage.DisasterType, ageSec: Long = 60, id: ByteArray = TestSupport.id(0x61)): String {
        val b = org.relay.core.payload.Broadcast(id, org.relay.core.payload.BroadcastKind.DISASTER, type, System.currentTimeMillis() / 1000 - ageSec)
        return Base64.getEncoder().encodeToString(org.relay.core.payload.BroadcastCodec.encode(b))
    }

    @Test fun everySyncReturnsLiveBroadcastsAndTheGatewayOriginatesEachOnce() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        val logs = ArrayList<String>()
        val s = syncer(node, store, GatewayState(null), logs)
        responder = { JSONObject().put("accepted", 0).put("duplicates", 0).put("rejected", 0).put("acks", JSONArray()).put("ack_cursor", 0)
            .put("broadcasts", JSONArray().put(broadcastPayload(org.relay.core.triage.DisasterType.FLOOD))) }

        val first = s.syncOnce()
        assertTrue(first.ok); assertEquals(1, first.broadcastsOriginated)
        assertEquals(1, store.all().count { it.type == FrameTypes.BROADCAST })
        // the backend repeats the same live list every round: nothing new is originated, nothing duplicated
        assertEquals(0, s.syncOnce().broadcastsOriginated)
        assertEquals(1, store.all().count { it.type == FrameTypes.BROADCAST })
    }

    @Test fun broadcastsAreNeverUploadedBackAndABadOrExpiredOneIsSkipped() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        val s = syncer(node, store, GatewayState(null))
        responder = { JSONObject().put("accepted", 0).put("duplicates", 0).put("rejected", 0).put("acks", JSONArray()).put("ack_cursor", 0)
            .put("broadcasts", JSONArray()
                .put(broadcastPayload(org.relay.core.triage.DisasterType.CYCLONE, id = TestSupport.id(0x62)))
                .put(broadcastPayload(org.relay.core.triage.DisasterType.FLOOD, ageSec = 25 * 3600, id = TestSupport.id(0x63)))
                .put("not-base64-!!")
                .put(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))) }
        val out = s.syncOnce()
        assertTrue(out.ok)
        assertEquals(1, out.broadcastsOriginated)                 // only the live, well-formed one
        assertEquals(0, s.syncOnce().uploaded)                    // a broadcast is not a NEED: it goes down, never up
    }

    @Test fun anOlderBackendWithoutTheKeyChangesNothing() {
        val store = InMemoryPacketStore()
        val node = TestSupport.node(gatewayId, store)
        val out = syncer(node, store, GatewayState(null)).syncOnce()
        assertTrue(out.ok); assertEquals(0, out.broadcastsOriginated)
    }
}
