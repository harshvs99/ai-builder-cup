package org.relay.app.store

/** What the "My needs" list shows for one NEED. Order is roughly "how far it has got". */
enum class NeedStatus { QUEUED, SENT, WAITING_UPLOAD, UPLOADED, RECEIVED, DISPATCHED, ON_THE_WAY, RESOLVED }

object NeedStatusLogic {
    /**
     * @param isGateway this phone currently has internet and is syncing to the backend
     * @param storedLocally the NEED frame is in this phone's packet store (it has been originated)
     * @param uploaded a backend sync has gone through for this NEED (it is in the gateway's uploaded set)
     *
     * A NEED sitting on a gateway that no sync has accepted yet must not look delivered, so it is
     * WAITING_UPLOAD, and once uploaded it is UPLOADED until the RECEIVED ack arrives.
     */
    fun derive(need: MyNeed, isGateway: Boolean, storedLocally: Boolean, uploaded: Boolean): NeedStatus = when {
        need.stage == MyNeed.Stage.RESOLVED -> NeedStatus.RESOLVED
        need.stage == MyNeed.Stage.ON_THE_WAY -> NeedStatus.ON_THE_WAY
        need.stage == MyNeed.Stage.DISPATCHED -> NeedStatus.DISPATCHED
        need.stage == MyNeed.Stage.RECEIVED -> NeedStatus.RECEIVED
        uploaded -> NeedStatus.UPLOADED
        isGateway && storedLocally -> NeedStatus.WAITING_UPLOAD
        need.sentToMesh -> NeedStatus.SENT
        else -> NeedStatus.QUEUED
    }
}
