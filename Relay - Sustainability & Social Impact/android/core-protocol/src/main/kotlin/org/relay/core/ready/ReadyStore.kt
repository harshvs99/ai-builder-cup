package org.relay.core.ready

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.relay.core.store.AtomicFiles
import java.io.File

/** What a stored plan was made for. The refresher fetches a new plan when any part differs from the current pack, household or language. */
data class PlanKey(val packVersion: String, val householdKey: String, val lang: String, val warningId: Long?)

/** What the Ready screen renders: everything comes from storage, nothing from the network. */
data class ReadySnapshot(
    val pack: ReadyPack?,
    val plan: ReadyPlan?,
    val planKey: PlanKey?,
    /** The stored spoken plan (WAV), or null. */
    val audio: File?,
    /** Last known district id (from a pack), or null before the first pack. */
    val district: String?,
    val notifiedWarningId: Long?,
    val lastPackFetchMs: Long,
) {
    /** The plan is shown only while it answers the pack's current warning. */
    val currentPlan: ReadyPlan? get() = plan?.takeIf { p -> pack?.warning != null && p.warningId == pack.warning.id }
}

/**
 * Pack, plan and spoken plan kept in app files (contract section 3.3). Every file is written whole through [AtomicFiles]; the
 * `ready_state.json` pointer that names the current files is written LAST, so a crash at any point leaves the previous copy
 * readable, and files the pointer no longer names are deleted only after it was replaced.
 *
 * [beforeCommit] runs between the data file and the pointer; tests use it to simulate a crash there.
 */
class ReadyStore(private val dir: File, private val beforeCommit: (String) -> Unit = {}) {
    private val pointerFile get() = File(dir, "ready_state.json")

    private class Pointer(
        val packFile: String?, val planFile: String?, val planKey: PlanKey?, val audioFile: String?,
        val district: String?, val notifiedWarningId: Long?, val lastPackFetchMs: Long,
    ) {
        fun toJson(): String = JSONObject().apply {
            packFile?.let { put("pack_file", it) }
            planFile?.let { put("plan_file", it) }
            planKey?.let { k ->
                put("plan_key", JSONObject().put("pack_version", k.packVersion).put("household", k.householdKey).put("lang", k.lang)
                    .also { o -> k.warningId?.let { o.put("warning_id", it) } })
            }
            audioFile?.let { put("audio_file", it) }
            district?.let { put("district", it) }
            notifiedWarningId?.let { put("notified_warning_id", it) }
            put("last_pack_fetch_ms", lastPackFetchMs)
        }.toString()
    }

    @Synchronized private fun pointer(): Pointer {
        val text = AtomicFiles.readOrNull(pointerFile)?.toString(Charsets.UTF_8) ?: return EMPTY
        return try {
            val o = JSONObject(text)
            Pointer(
                o.optString("pack_file").ifBlank { null }, o.optString("plan_file").ifBlank { null },
                o.optJSONObject("plan_key")?.let { k ->
                    PlanKey(k.optString("pack_version"), k.optString("household"), k.optString("lang"), if (k.has("warning_id")) k.optLong("warning_id") else null)
                },
                o.optString("audio_file").ifBlank { null }, o.optString("district").ifBlank { null },
                if (o.has("notified_warning_id")) o.optLong("notified_warning_id") else null, o.optLong("last_pack_fetch_ms", 0),
            )
        } catch (e: JSONException) {
            EMPTY // a damaged pointer is as good as none: the next refresh rewrites it
        }
    }

    private fun readText(name: String?): String? = name?.let { AtomicFiles.readOrNull(File(dir, it))?.toString(Charsets.UTF_8) }

    @Synchronized fun load(): ReadySnapshot {
        val p = pointer()
        val audio = p.audioFile?.let { File(dir, it) }?.takeIf { it.isFile }
        return ReadySnapshot(
            pack = ReadyJson.parsePack(readText(p.packFile)), plan = ReadyJson.parsePlan(readText(p.planFile)), planKey = p.planKey,
            audio = audio, district = p.district, notifiedWarningId = p.notifiedWarningId, lastPackFetchMs = p.lastPackFetchMs,
        )
    }

    /** Stores a pack keyed by its `version`. [raw] is the body as the backend sent it, so fields this build does not know survive. */
    @Synchronized fun savePack(raw: String, pack: ReadyPack, nowMs: Long) {
        val name = "pack-${safe(pack.version)}.json"
        AtomicFiles.write(File(dir, name), raw.toByteArray(Charsets.UTF_8))
        beforeCommit("pack")
        val p = pointer()
        commit(Pointer(name, p.planFile, p.planKey, p.audioFile, pack.district, p.notifiedWarningId, nowMs))
    }

    /** Stores a plan keyed by its `plan_id`; any spoken plan of an older plan is dropped. */
    @Synchronized fun savePlan(raw: String, plan: ReadyPlan, key: PlanKey) {
        val name = "plan-${safe(plan.planId)}.json"
        AtomicFiles.write(File(dir, name), raw.toByteArray(Charsets.UTF_8))
        beforeCommit("plan")
        val p = pointer()
        val keepAudio = p.audioFile?.takeIf { it == audioName(plan.planId) }
        commit(Pointer(p.packFile, name, key, keepAudio, p.district, p.notifiedWarningId, p.lastPackFetchMs))
    }

    /** Stores the spoken plan of [planId]; ignored when that is no longer the stored plan. */
    @Synchronized fun saveAudio(planId: String, wav: ByteArray) {
        val p = pointer()
        if (p.planFile != "plan-${safe(planId)}.json") return
        val name = audioName(planId)
        AtomicFiles.write(File(dir, name), wav)
        beforeCommit("audio")
        commit(Pointer(p.packFile, p.planFile, p.planKey, name, p.district, p.notifiedWarningId, p.lastPackFetchMs))
    }

    @Synchronized fun markNotified(warningId: Long) {
        val p = pointer()
        commit(Pointer(p.packFile, p.planFile, p.planKey, p.audioFile, p.district, warningId, p.lastPackFetchMs))
    }

    /** The district the person chose by hand (no location, no pack yet). A pack later overwrites it with the backend's answer. */
    @Synchronized fun setDistrict(district: String) {
        val p = pointer()
        commit(Pointer(p.packFile, p.planFile, p.planKey, p.audioFile, district, p.notifiedWarningId, p.lastPackFetchMs))
    }

    /** Remembers when a fetch was last tried (success or not), so the 3 h rule survives a restart. */
    @Synchronized fun noteFetchAttempt(nowMs: Long) {
        val p = pointer()
        commit(Pointer(p.packFile, p.planFile, p.planKey, p.audioFile, p.district, p.notifiedWarningId, nowMs))
    }

    private fun commit(next: Pointer) {
        AtomicFiles.write(pointerFile, next.toJson().toByteArray(Charsets.UTF_8))
        prune(next)
    }

    /** Deletes data files the pointer no longer names (and stray temp files). Runs only after the pointer was replaced. */
    private fun prune(p: Pointer) {
        val keep = setOfNotNull(p.packFile, p.planFile, p.audioFile, pointerFile.name, TICKS_FILE)
        dir.listFiles()?.forEach { f ->
            val n = f.name
            val ours = n.startsWith("pack-") || n.startsWith("plan-") || n.startsWith("audio-") || n.endsWith(".tmp")
            if (ours && n !in keep) f.delete()
        }
    }

    // ---- checklist ticks: kept per pack version and reset when the version changes
    @Synchronized fun ticks(packVersion: String): Set<Int> {
        val text = AtomicFiles.readOrNull(File(dir, TICKS_FILE))?.toString(Charsets.UTF_8) ?: return emptySet()
        return try {
            val o = JSONObject(text)
            if (o.optString("version") != packVersion) return emptySet()
            val a = o.optJSONArray("done") ?: return emptySet()
            (0 until a.length()).map { a.getInt(it) }.toSet()
        } catch (e: JSONException) {
            emptySet()
        }
    }

    @Synchronized fun setTick(packVersion: String, index: Int, done: Boolean) {
        val now = ticks(packVersion).toMutableSet()
        if (done) now += index else now -= index
        AtomicFiles.write(
            File(dir, TICKS_FILE),
            JSONObject().put("version", packVersion).put("done", JSONArray(now.sorted())).toString().toByteArray(Charsets.UTF_8),
        )
    }

    private fun audioName(planId: String) = "audio-${safe(planId)}.wav"

    /** A file-name-safe form of an id or version that came from the network. */
    private fun safe(s: String) = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "x" }

    private companion object {
        const val TICKS_FILE = "ready_ticks.json"
        val EMPTY = Pointer(null, null, null, null, null, null, 0)
    }
}
