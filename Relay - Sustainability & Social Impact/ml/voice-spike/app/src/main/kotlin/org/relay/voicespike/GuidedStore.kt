package org.relay.voicespike

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One prompt from assets/guided_prompts.json (source of truth: eval/guided_prompts.json). */
data class GuidedItem(val id: String, val lang: String, val text: String, val expected: JSONObject)

/**
 * Guided-recording storage, all app-internal: <filesDir>/guided/
 *   <id>.wav        16 kHz mono PCM16 take
 *   manifest.json   [{file, lang, expected, reference_transcript}] for the takes that exist (what [Eval] consumes)
 *   state.json      {index, skipped[]} so the session resumes after a restart
 * Read from a PC with: adb exec-out run-as org.relay.voicespike cat files/guided/<name>
 */
class GuidedStore(ctx: Context) {
    val dir = File(ctx.filesDir, "guided").apply { mkdirs() }
    val items: List<GuidedItem> = ctx.assets.open("guided_prompts.json").bufferedReader(Charsets.UTF_8).use { r ->
        val a = JSONArray(r.readText())
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            GuidedItem(o.getString("id"), o.getString("lang"), o.getString("text"), o.getJSONObject("expected"))
        }
    }

    fun wav(id: String) = File(dir, "$id.wav")
    fun hasTake(id: String) = wav(id).length() > 44
    fun takeSeconds(id: String): Double = ((wav(id).length() - 44).coerceAtLeast(0)) / (Wav.SAMPLE_RATE * 2.0)

    private fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun saveTake(id: String, pcm: ByteArray) {
        atomicWrite(wav(id), Wav.pcmToWav(pcm))
        writeManifest()
    }

    fun deleteTake(id: String) {
        wav(id).delete()
        writeManifest()
    }

    /** Rebuilds manifest.json from the takes that exist on disk, in prompt order. */
    fun writeManifest() {
        val a = JSONArray()
        for (it in items) if (hasTake(it.id)) {
            a.put(JSONObject().put("file", "${it.id}.wav").put("lang", it.lang)
                .put("expected", it.expected).put("reference_transcript", it.text))
        }
        atomicWrite(File(dir, "manifest.json"), a.toString(2).toByteArray(Charsets.UTF_8))
    }

    fun recordedCount() = items.count { hasTake(it.id) }

    // ---- resumable position ----
    fun loadState(): Pair<Int, Set<String>> = try {
        val o = JSONObject(File(dir, "state.json").readText())
        val sk = o.optJSONArray("skipped")
        o.optInt("index", 0).coerceIn(0, items.size - 1) to (0 until (sk?.length() ?: 0)).map { sk!!.getString(it) }.toSet()
    } catch (_: Exception) { 0 to emptySet() }

    fun saveState(index: Int, skipped: Set<String>) {
        try {
            atomicWrite(File(dir, "state.json"),
                JSONObject().put("index", index).put("skipped", JSONArray(skipped.toList())).toString().toByteArray())
        } catch (_: Exception) {}
    }
}
