package org.relay.app.ui

import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.lifecycle.ViewModelStoreOwner

enum class AskMode(val arg: String) {
    VOICE("voice"), TEXT("text");

    companion object {
        fun fromArg(s: String?): AskMode = entries.firstOrNull { it.arg == s } ?: VOICE
    }
}

/** Route names of the app's navigation graph. Build concrete routes with [Routes] helpers, not by string. */
object Routes {
    const val LANGUAGE = "language"
    const val HOME = "home"
    const val REQUESTS = "requests"
    const val REQUEST = "request/{msgId}"
    /** Parent graph of ask + check: a ViewModel scoped to its back stack entry is shared by both screens. */
    const val ASK_FLOW = "askflow"
    const val ASK = "ask?mode={mode}&prefillFrom={prefillFrom}"
    const val CHECK = "check"
    const val NETWORK = "network"
    const val CONSOLE = "network/console"
    const val SETTINGS = "settings"
    const val DEVELOPER = "settings/developer"
    /** Temporary: the pre-redesign Home, reachable until the new screens replace its pieces. */
    const val LEGACY_HOME = "legacy-home"

    fun request(msgId: String) = "request/$msgId"
    fun ask(mode: AskMode, prefillFrom: String? = null) =
        "ask?mode=${mode.arg}" + (prefillFrom?.let { "&prefillFrom=$it" } ?: "")

    /** The route without arguments, to compare a destination's route with the constants above. */
    fun base(route: String?): String? = route?.substringBefore('?')

    /** Routes that hide the bottom bar. */
    fun hidesBottomBar(route: String?): Boolean = when (base(route)) {
        "ask", CHECK, LANGUAGE -> true
        else -> false
    }

    /** Which bottom tab a route belongs to (Request detail counts as Requests). Null = none. */
    fun tabOf(route: String?): String? = when (base(route)) {
        HOME, LEGACY_HOME -> HOME
        REQUESTS, REQUEST -> REQUESTS
        NETWORK, CONSOLE -> NETWORK
        SETTINGS, DEVELOPER -> SETTINGS
        else -> null
    }
}

/**
 * What a screen gets to navigate with. Screens call these, never the NavController. [setup] is the legacy
 * permission/Bluetooth actions used by the developer screens.
 */
class RelayNav(val controller: NavHostController, val setup: SetupActions) {
    fun back() { controller.popBackStack() }

    /** Switch bottom tab (keeps each tab's state). */
    fun toTab(route: String) = controller.navigateTab(route)
    fun toHome() = toTab(Routes.HOME)
    fun toRequests() = toTab(Routes.REQUESTS)
    fun toNetwork() = toTab(Routes.NETWORK)
    fun toSettings() = toTab(Routes.SETTINGS)

    fun openRequest(msgId: String) { controller.navigate(Routes.request(msgId)) }
    fun startAsk(mode: AskMode = AskMode.VOICE, prefillFrom: String? = null) { controller.navigate(Routes.ask(mode, prefillFrom)) }
    /** Ask step 1 -> step 2. */
    fun toCheck() { controller.navigate(Routes.CHECK) }
    /** After Send: open the new request's detail and drop the ask/check flow from the back stack. */
    fun finishAskTo(msgId: String) {
        controller.navigate(Routes.request(msgId)) { popUpTo(Routes.ASK_FLOW) { inclusive = true } }
    }
    fun openConsole() { controller.navigate(Routes.CONSOLE) }
    fun openDeveloper() { controller.navigate(Routes.DEVELOPER) }
    fun openLegacyHome() { controller.navigate(Routes.LEGACY_HOME) }

    /** First run: the language is confirmed, go to Home and drop the language screen. */
    fun languageDone() {
        controller.navigate(Routes.HOME) { popUpTo(Routes.LANGUAGE) { inclusive = true } }
    }

    /** The owner to pass to `viewModel(...)` so ask and check share one ViewModel (needs the current entry's graph). */
    fun askFlowOwner(): ViewModelStoreOwner = controller.getBackStackEntry(Routes.ASK_FLOW)
}

internal fun NavController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
