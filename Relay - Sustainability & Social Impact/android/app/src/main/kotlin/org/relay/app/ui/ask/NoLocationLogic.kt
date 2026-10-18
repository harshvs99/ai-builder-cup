package org.relay.app.ui.ask

/** Why there is no GPS fix right now. */
enum class NoLocationReason { PERMISSION_OFF, SERVICES_OFF, FINDING }

/** Primary action of the no-location dialog: which flow "Turn on location" starts. */
enum class TurnOnAction { REQUEST_PERMISSION, OPEN_LOCATION_SETTINGS, RETRY_FIX }

/** What the dialog shows. [confirmSendAnyway] = a second tap is needed on "Send without location". */
data class NoLocationPrompt(
    val reason: NoLocationReason,
    val bodyWithLandmark: Boolean,
    val turnOn: TurnOnAction,
    /** True: the secondary button says "Add a landmark"; false: "Change landmark". */
    val addLandmark: Boolean,
    val confirmSendAnyway: Boolean,
)

/** Pure decisions for "never let a request leave without the person knowing it has no location". */
object NoLocationLogic {
    /** Reason for the Where row's warning line; null when there is a fix. */
    fun reason(permissionGranted: Boolean, servicesEnabled: Boolean, fixPresent: Boolean): NoLocationReason? = when {
        fixPresent -> null
        !permissionGranted -> NoLocationReason.PERMISSION_OFF
        !servicesEnabled -> NoLocationReason.SERVICES_OFF
        else -> NoLocationReason.FINDING
    }

    /**
     * The dialog to show when Send is tapped, or null when the request may go out: there is a fix, or we are still
     * inside the short wait for one.
     */
    fun promptOnSend(
        permissionGranted: Boolean, servicesEnabled: Boolean, fixPresent: Boolean, waiting: Boolean, landmarkEmpty: Boolean,
    ): NoLocationPrompt? {
        if (fixPresent || waiting) return null
        val reason = reason(permissionGranted, servicesEnabled, fixPresent = false) ?: return null
        return NoLocationPrompt(
            reason = reason,
            bodyWithLandmark = !landmarkEmpty,
            turnOn = when (reason) {
                NoLocationReason.PERMISSION_OFF -> TurnOnAction.REQUEST_PERMISSION
                NoLocationReason.SERVICES_OFF -> TurnOnAction.OPEN_LOCATION_SETTINGS
                NoLocationReason.FINDING -> TurnOnAction.RETRY_FIX
            },
            addLandmark = landmarkEmpty,
            confirmSendAnyway = landmarkEmpty,
        )
    }
}
