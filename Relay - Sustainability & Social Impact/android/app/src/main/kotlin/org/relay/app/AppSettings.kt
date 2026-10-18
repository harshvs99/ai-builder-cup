package org.relay.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.relay.app.ble.RecentPeers
import org.relay.core.payload.Lang
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Light / Dark, or follow the system (default). */
enum class ThemeMode { LIGHT, DARK, AUTO }

/** Text-to-speech rate for "Hear it" buttons and read-aloud. */
enum class VoiceSpeed { SLOW, NORMAL }

/**
 * Whether this phone uploads for the mesh when it has internet. NEVER is the old "Pretend offline" switch.
 * AUTOMATIC and ALWAYS behave the same today (a phone with internet is a gateway); ALWAYS is reserved for
 * "stay a gateway even when the network looks constrained".
 */
enum class GatewayMode { AUTOMATIC, NEVER, ALWAYS }

data class Settings(
    val backendUrl: String = BackendDefaults.EMULATOR_URL,
    val deviceKey: String = DEFAULT_KEY,
    /** Comma-separated node-id hex prefixes; empty = accept every peer. */
    val allowlist: String = "",
    val language: Lang = Lang.EN,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    /** Multiplies all text by 1.2. */
    val largerText: Boolean = false,
    /** Speak new messages from the control room aloud. */
    val readAloud: Boolean = false,
    val voiceSpeed: VoiceSpeed = VoiceSpeed.NORMAL,
    /** The user has picked a language on the first-run screen (false until then). */
    val languageConfirmed: Boolean = false,
    /** Optional "Your area" text shown on Home and in request references; empty = omit. */
    val areaLabel: String = "",
    val gatewayMode: GatewayMode = GatewayMode.AUTOMATIC,
    /** Keep the mesh service running when the app is closed. */
    val carryInBackground: Boolean = true,
    /**
     * Opt-in "Help locate neighbours" (mesh-assisted location, SPEC 5.5): when a neighbour's NEED has no position,
     * this phone publishes its own position (about 100 m or better) to the mesh and the control room. Default on in
     * demo builds, off in village builds (`relay.helpLocateDefault`).
     */
    val helpLocateNeighbours: Boolean = BuildConfig.HELP_LOCATE_DEFAULT,
) {
    /** The old "Pretend offline" behaviour: never act as a gateway. */
    val pretendOffline: Boolean get() = gatewayMode == GatewayMode.NEVER

    companion object {
        /** Build-time `relay.deviceKey`; `dev-relay-key` when none was configured. */
        val DEFAULT_KEY: String = BuildConfig.DEFAULT_KEY
    }
}

/** SharedPreferences-backed settings, observable as a [StateFlow] and through [addListener]. */
class AppSettings(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("relay_settings", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<(Settings) -> Unit>()

    /**
     * Backend used when the user has not set one: `http://10.0.2.2:8080` on an emulator, otherwise the
     * build-time `relay.backendUrl` ([BuildConfig.DEFAULT_BACKEND]).
     */
    private val onEmulator = BackendDefaults.isEmulator(Build.FINGERPRINT, Build.HARDWARE, Build.PRODUCT)
    val defaultBackend: String = BackendDefaults.defaultFor(onEmulator, BuildConfig.DEFAULT_BACKEND)

    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<Settings> get() = _flow

    val current: Settings get() = _flow.value

    /**
     * Release builds pin backend and key to the build-time values and ignore anything stored, so a stale or
     * mistyped value from an older install cannot break sync. Debug builds keep both editable.
     */
    val connectionLocked: Boolean = BuildConfig.LOCK_CONNECTION

    private fun read() = Settings(
        backendUrl = if (connectionLocked) defaultBackend
            else BackendDefaults.resolve(prefs.getString("backend", null), onEmulator, BuildConfig.DEFAULT_BACKEND),
        deviceKey = if (connectionLocked) Settings.DEFAULT_KEY
            else prefs.getString("key", Settings.DEFAULT_KEY) ?: Settings.DEFAULT_KEY,
        allowlist = prefs.getString("allowlist", "") ?: "",
        language = Lang.fromTag(prefs.getString("lang", "en") ?: "en") ?: Lang.EN,
        themeMode = enumOr(prefs.getString("theme", null), ThemeMode.AUTO),
        largerText = prefs.getBoolean("larger_text", false),
        readAloud = prefs.getBoolean("read_aloud", false),
        voiceSpeed = enumOr(prefs.getString("voice_speed", null), VoiceSpeed.NORMAL),
        // Existing installs have no flag, so they see the language screen once.
        languageConfirmed = prefs.getBoolean("lang_confirmed", false),
        areaLabel = prefs.getString("area", "") ?: "",
        // Older installs stored the "Pretend offline" switch as a boolean.
        gatewayMode = prefs.getString("gateway_mode", null)?.let { enumOr(it, GatewayMode.AUTOMATIC) }
            ?: if (prefs.getBoolean("offline", false)) GatewayMode.NEVER else GatewayMode.AUTOMATIC,
        carryInBackground = prefs.getBoolean("carry_bg", true),
        helpLocateNeighbours = prefs.getBoolean("help_locate", BuildConfig.HELP_LOCATE_DEFAULT),
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    @Synchronized fun update(f: (Settings) -> Settings) {
        val next = f(_flow.value).let {
            if (connectionLocked) it.copy(backendUrl = defaultBackend, deviceKey = Settings.DEFAULT_KEY) else it
        }
        if (next == _flow.value) return
        val ed = prefs.edit()
        // The default is stored as "unset" so it keeps following the build-time value.
        if (next.backendUrl == defaultBackend) ed.remove("backend") else ed.putString("backend", next.backendUrl)
        ed.putString("key", next.deviceKey)
            .putString("allowlist", next.allowlist)
            .putBoolean("offline", next.pretendOffline)
            .putString("lang", next.language.tag)
            .putString("theme", next.themeMode.name)
            .putBoolean("larger_text", next.largerText)
            .putBoolean("read_aloud", next.readAloud)
            .putString("voice_speed", next.voiceSpeed.name)
            .putBoolean("lang_confirmed", next.languageConfirmed)
            .putString("area", next.areaLabel)
            .putString("gateway_mode", next.gatewayMode.name)
            .putBoolean("carry_bg", next.carryInBackground)
            .putBoolean("help_locate", next.helpLocateNeighbours)
            .apply()
        _flow.value = next
        for (l in listeners) try { l(next) } catch (_: Exception) {}
    }

    /** The last peers with a successful exchange (see RecentPeers). Stored outside [Settings]: no listener fires. */
    var recentPeers: List<RecentPeers.Peer>
        get() = RecentPeers.parse(prefs.getString("recent_peers", null))
        set(v) { prefs.edit().putString("recent_peers", RecentPeers.serialize(v)).apply() }

    fun addListener(l: (Settings) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Settings) -> Unit) { listeners.remove(l) }
}
