package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.relay.app.store.MyNeed
import org.relay.app.store.NeedStatus
import org.relay.app.store.NeedStatusLogic
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang

class NeedStatusTest {
    private fun need(sent: Boolean = false) = MyNeed(
        msgIdHex = "aa", createdAtMs = 0, category = Category.RESCUE, urgency = 3, people = 4, item = "rescue",
        landmark = "temple", summary = "s", lang = Lang.EN, needPayloadB64 = "", sentToMesh = sent,
    )

    private fun derive(n: MyNeed, gw: Boolean, stored: Boolean, up: Boolean) = NeedStatusLogic.derive(n, gw, stored, up)

    @Test fun nonGatewayFollowsMeshStage() {
        assertEquals(NeedStatus.QUEUED, derive(need(), false, true, false))
        assertEquals(NeedStatus.SENT, derive(need(sent = true), false, true, false))
    }

    @Test fun gatewayWithLocalNeedIsWaitingForUploadEvenIfSentToMesh() {
        assertEquals(NeedStatus.WAITING_UPLOAD, derive(need(), true, true, false))
        assertEquals(NeedStatus.WAITING_UPLOAD, derive(need(sent = true), true, true, false))
    }

    @Test fun gatewayNeedNotYetOriginatedStaysQueued() {
        assertEquals(NeedStatus.QUEUED, derive(need(), true, false, false))
    }

    @Test fun uploadedWaitsForConfirmation() {
        assertEquals(NeedStatus.UPLOADED, derive(need(), true, true, true))
        assertEquals(NeedStatus.UPLOADED, derive(need(sent = true), false, true, true)) // gateway role lost after uploading
    }

    @Test fun acksOverrideEverything() {
        val received = need().withAck(AckStatus.RECEIVED, 12, "got it", 0xFFFF)
        assertEquals(NeedStatus.RECEIVED, derive(received, true, true, false))
        assertEquals(NeedStatus.RECEIVED, derive(received, true, true, true))
        val dispatched = received.withAck(AckStatus.DISPATCHED, 12, "on the way", 15)
        assertEquals(NeedStatus.DISPATCHED, derive(dispatched, true, true, true))
    }
}
