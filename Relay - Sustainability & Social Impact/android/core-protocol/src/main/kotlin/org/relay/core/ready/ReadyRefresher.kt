package org.relay.core.ready

/** Why a refresh runs. */
enum class RefreshTrigger { APP_OPEN, NETWORK_REGAINED, PERIODIC, BACKGROUND, MANUAL }

/** The phone state a refresh needs: connectivity, where it is (if it knows), the language to ask for and the household. */
data class RefreshInput(
    val connected: Boolean,
    val lat: Double?,
    val lon: Double?,
    /** UI language tag: pack checklist and plan steps are asked for in it. */
    val lang: String,
    val household: Household,
)

/** What a refresh did. [newWarning] is set when a warning arrived that this phone has not told the person about yet. */
data class RefreshResult(
    val skipped: Boolean = false,
    val packFetched: Boolean = false,
    val planFetched: Boolean = false,
    val audioFetched: Boolean = false,
    val newWarning: ReadyWarning? = null,
    /** True when there is neither a position nor a district: the Ready screen should ask "Which district are you in?". */
    val needsDistrict: Boolean = false,
    val error: String? = null,
)

/**
 * The refresh rules of contract section 3.3, as pure decisions plus an orchestrator over [ReadyApi] and [ReadyStore]. Mirrored in
 * Swift (`ReadyRefresher`) with the same tests.
 */
object ReadyPolicy {
    /** At most one periodic fetch per 3 hours while the app runs. */
    const val PERIODIC_MS = 3L * 3600 * 1000
    /** App open and network regained fetch at once, but not twice inside a minute (a flapping network must not hammer the backend). */
    const val DEBOUNCE_MS = 60L * 1000

    fun shouldFetchPack(trigger: RefreshTrigger, connected: Boolean, lastFetchMs: Long, nowMs: Long): Boolean {
        if (!connected) return false
        val since = nowMs - lastFetchMs
        return when (trigger) {
            RefreshTrigger.MANUAL -> true
            RefreshTrigger.APP_OPEN, RefreshTrigger.NETWORK_REGAINED -> lastFetchMs == 0L || since >= DEBOUNCE_MS || since < 0
            RefreshTrigger.PERIODIC, RefreshTrigger.BACKGROUND -> lastFetchMs == 0L || since >= PERIODIC_MS || since < 0
        }
    }

    /** A plan is fetched only while a warning is active, and only when the stored one is missing or made for something else. */
    fun shouldFetchPlan(pack: ReadyPack, stored: PlanKey?, hasPlan: Boolean, household: Household, lang: String): Boolean {
        val w = pack.warning ?: return false
        if (!hasPlan || stored == null) return true
        return stored.packVersion != pack.version || stored.householdKey != household.key || stored.lang != lang || stored.warningId != w.id
    }

    /** The spoken plan is fetched when the backend offers one and none is stored for this plan. */
    fun shouldFetchAudio(plan: ReadyPlan, hasAudio: Boolean): Boolean = plan.audioUrl != null && !hasAudio

    /** One local notification per new warning id. */
    fun shouldNotify(pack: ReadyPack, notifiedWarningId: Long?): Boolean = pack.warning != null && pack.warning.id != notifiedWarningId
}

class ReadyRefresher(
    private val api: ReadyApi,
    private val store: ReadyStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Runs one refresh. Never throws: a failure is reported in [RefreshResult.error] and the stored copy stays as it was
     * (a pack that fails to parse is not stored).
     */
    fun refresh(trigger: RefreshTrigger, input: RefreshInput): RefreshResult {
        val before = store.load()
        val now = nowMs()
        if (!ReadyPolicy.shouldFetchPack(trigger, input.connected, before.lastPackFetchMs, now)) return RefreshResult(skipped = true)
        val query = when {
            input.lat != null && input.lon != null -> PackQuery.At(input.lat, input.lon)
            before.district != null -> PackQuery.District(before.district)
            else -> return RefreshResult(skipped = true, needsDistrict = true)
        }
        store.noteFetchAttempt(now) // before the call: a failing backend must not be retried in a tight loop
        val packRaw: String
        val pack: ReadyPack
        try {
            packRaw = api.pack(query, input.lang)
            pack = ReadyJson.parsePack(packRaw) ?: return RefreshResult(error = "unreadable pack")
            store.savePack(packRaw, pack, now)
        } catch (e: Exception) {
            return RefreshResult(error = e.message ?: e.javaClass.simpleName)
        }
        var planFetched = false
        var audioFetched = false
        var error: String? = null
        try {
            val snap = store.load()
            if (ReadyPolicy.shouldFetchPlan(pack, snap.planKey, snap.plan != null, input.household, input.lang)) {
                val raw = api.plan(input.lang, input.lat, input.lon, if (input.lat == null || input.lon == null) pack.district else null, input.household)
                val plan = ReadyJson.parsePlan(raw)
                if (plan == null) error = "unreadable plan" else {
                    store.savePlan(raw, plan, PlanKey(pack.version, input.household.key, input.lang, pack.warning?.id))
                    planFetched = true
                }
            }
            val now2 = store.load()
            val plan = now2.plan
            if (plan != null && ReadyPolicy.shouldFetchAudio(plan, now2.audio != null)) {
                val wav = api.audio(plan.audioUrl!!)
                if (wav != null && wav.isNotEmpty()) { store.saveAudio(plan.planId, wav); audioFetched = true }
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName // the pack is already stored: the screen still works
        }
        val fresh = if (ReadyPolicy.shouldNotify(pack, before.notifiedWarningId)) pack.warning else null
        return RefreshResult(packFetched = true, planFetched = planFetched, audioFetched = audioFetched, newWarning = fresh, error = error)
    }

    /**
     * A phone with signal, a known district and no pack at all may use the district's published list instead
     * (`GET /v1/public/shelters`, no key). It is stored as a pack without a warning, so the shelter screens work unchanged, and the next
     * real pack replaces it. Returns true when a list was stored. Never throws.
     */
    fun refreshPublicShelters(input: RefreshInput): Boolean {
        val before = store.load()
        val district = before.district ?: return false
        if (!input.connected || before.pack != null) return false
        return try {
            val got = PublicShelters.toPack(api.publicShelters(district), nowMs() / 1000) ?: return false
            store.savePack(got.first, got.second, before.lastPackFetchMs) // keeps the pack fetch clock: this is not a pack fetch
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Call once the notification was posted, so the same warning never notifies twice. */
    fun noteNotified(warningId: Long) = store.markNotified(warningId)
}
