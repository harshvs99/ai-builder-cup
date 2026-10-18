package org.relay.app.ready

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.relay.app.MeshRepository
import org.relay.app.location.LocationProvider
import org.relay.core.ready.HttpReadyApi
import org.relay.core.ready.ReadyRefresher
import org.relay.core.ready.ReadySnapshot
import org.relay.core.ready.ReadyStore
import org.relay.core.ready.RefreshInput
import org.relay.core.ready.RefreshResult
import org.relay.core.ready.RefreshTrigger
import java.io.File

/**
 * The phone's Ready data (contract v2 section 3): what the Ready screen and the Home banner read, and the refresh that fills it.
 * The UI renders from [snapshot] only, which is read from storage, so it works in airplane mode.
 */
object ReadyRepository {
    private lateinit var store: ReadyStore
    private val _snapshot = MutableStateFlow(
        ReadySnapshot(pack = null, plan = null, planKey = null, audio = null, district = null, notifiedWarningId = null, lastPackFetchMs = 0),
    )
    val snapshot: StateFlow<ReadySnapshot> get() = _snapshot

    /** True when the last refresh found neither a position nor a district: the Ready screen asks which district. */
    private val _needsDistrict = MutableStateFlow(false)
    val needsDistrict: StateFlow<Boolean> get() = _needsDistrict

    @Synchronized fun init(ctx: Context) {
        if (::store.isInitialized) return
        store = ReadyStore(File(ctx.applicationContext.filesDir, "ready"))
        reload()
    }

    /** Demo builds (every debug build, or `-Prelay.demoShelters=true`) may show a demo shelter list, with a "Demo" badge; release builds never do. */
    val demoBuild: Boolean get() = org.relay.app.BuildConfig.DEBUG || org.relay.app.BuildConfig.DEMO_SHELTERS

    fun reload() { _snapshot.value = store.load() }

    fun setTick(version: String, index: Int, done: Boolean) = store.setTick(version, index, done)
    fun ticks(version: String): Set<Int> = store.ticks(version)

    /** The person said which district they are in (nothing else is known yet). */
    fun chooseDistrict(id: String) { store.setDistrict(id); _needsDistrict.value = false; reload() }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * One refresh (pack, then plan, then audio) and, for a new warning, one local notification. Safe to call from any
     * coroutine; the network work runs on the IO dispatcher. Never throws.
     */
    suspend fun refresh(ctx: Context, trigger: RefreshTrigger): RefreshResult = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        init(app)
        val s = MeshRepository.settings.current
        val fix = LocationProvider.fix.value
        val api = HttpReadyApi(s.backendUrl, s.deviceKey)
        val refresher = ReadyRefresher(api, store)
        val result = refresher.refresh(
            trigger,
            RefreshInput(isOnline(app), fix?.lat, fix?.lon, s.effectiveLanguage.tag, s.household),
        )
        _needsDistrict.value = result.needsDistrict
        // no pack and no way to get one (or none yet): the district's published list needs no key and may still be there
        if (result.error != null || result.skipped) {
            val input = RefreshInput(isOnline(app), fix?.lat, fix?.lon, s.effectiveLanguage.tag, s.household)
            if (refresher.refreshPublicShelters(input)) reload()
        }
        if (!result.skipped) reload()
        result.newWarning?.let { w ->
            val pack = _snapshot.value.pack
            if (ReadyNotifier.post(app, w, pack?.district.orEmpty(), s.effectiveLanguage)) refresher.noteNotified(w.id)
        }
        result
    }

    /** The intent extra that tells MainActivity to open the Ready screen (the notification's tap target). */
    const val EXTRA_OPEN_READY = "org.relay.app.OPEN_READY"

    fun openReadyIntent(ctx: Context): Intent =
        Intent(ctx, org.relay.app.MainActivity::class.java).putExtra(EXTRA_OPEN_READY, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
