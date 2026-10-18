package org.relay.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.relay.app.ble.RecentPeers
import org.relay.core.payload.Lang
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val backendUrl: String = BackendDefaults.EMULATOR_URL,
    val deviceKey: String = DEFAULT_KEY,
    /** Comma-separated node-id hex prefixes; empty = accept every peer. */
    val allowlist: String = "",
    val pretendOffline: Boolean = false,
    val language: Lang = Lang.EN,
) {
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
        pretendOffline = prefs.getBoolean("offline", false),
        language = Lang.fromTag(prefs.getString("lang", "en") ?: "en") ?: Lang.EN,
    )

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
