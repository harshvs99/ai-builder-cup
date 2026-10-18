package org.relay.voicespike

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.relay.core.nlu.NeedDraft
import org.relay.core.nlu.RuleExtractor
import org.relay.core.payload.Lang
import java.io.File

data class UiState(
    val status: String = "idle",
    val lang: String = "en",
    val recording: Boolean = false,
    val busy: Boolean = false,
    val transcript: String = "",
    val json: String = "",
    val latency: String = "",
    val rule: String = "",
    val evalStatus: String = "",
    val backend: String = "not loaded",
    /** "idle" | "loading" | "ready" | "failed" (background preload + any later load). */
    val modelState: String = "idle",
    /** SystemClock.elapsedRealtime() when the current load began (for the "Loading model... 12 s" ticker). */
    val loadStartedAt: Long = 0,
    val modelNote: String = "",
    val evalSummary: String = "",
)

/** Audio / text outcome for one request. */
data class Outcome(val run: RunResult, val parsed: ParsedNeed)

object Spike {
    val state = MutableStateFlow(UiState())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val gate = Mutex()
    @Volatile var backendPref = "gpu"
    private var runner: GemmaRunner? = null

    fun runner(ctx: Context): GemmaRunner = synchronized(this) {
        runner ?: GemmaRunner(ctx.applicationContext).also { runner = it }
    }

    private var smallRunner: GemmaRunner? = null

    /** Gemma 3 270M, text only (see [ModelSpec.G270M]); a separate engine, loaded only by the eval. */
    fun smallRunner(ctx: Context): GemmaRunner = synchronized(this) {
        smallRunner ?: GemmaRunner(ctx.applicationContext, ModelSpec.G270M).also { smallRunner = it }
    }

    fun processTextSmall(ctx: Context, text: String, lang: String): Outcome {
        val r = smallRunner(ctx).run(null, text, lang)
        return Outcome(r, NeedJson.parse(r.raw))
    }

    fun ui(f: (UiState) -> UiState) = state.update(f)

    fun ensureLoaded(ctx: Context): LoadInfo {
        val r = runner(ctx)
        if (r.isLoaded()) return r.lastLoad!!
        ui { it.copy(status = "loading model (${backendPref.uppercase()} first)...", modelState = "loading",
            loadStartedAt = SystemClock.elapsedRealtime(), modelNote = "") }
        val li = r.load(backendPref)
        ui { it.copy(status = if (li.ok) "model loaded: ${li.backend} in ${li.loadMs} ms" else "LOAD FAILED: ${li.note}",
            backend = li.backend, modelState = if (li.ok) "ready" else "failed", modelNote = li.note) }
        return li
    }

    private var preloadStarted = false

    /** Starts loading the model in the background (once per process, or again when [force] after a failure). */
    fun preload(ctx: Context, force: Boolean = false) {
        synchronized(this) { if (preloadStarted && !force) return; preloadStarted = true }
        val app = ctx.applicationContext
        scope.launch { gate.withLock { try { ensureLoaded(app) } catch (t: Throwable) {
            Log.e(TAG, "preload failed", t); ui { it.copy(modelState = "failed", modelNote = "${t.javaClass.simpleName}: ${t.message}") } } } }
    }

    /** Batch eval over [dir] (absolute). Queues behind the model preload via [gate]. */
    fun runEval(ctx: Context, dir: String) {
        val app = ctx.applicationContext
        ui { it.copy(busy = true, evalStatus = if (it.modelState == "ready") "eval: starting..." else "eval queued: waiting for the model...") }
        scope.launch { gate.withLock { Eval.run(app, dir) } }
    }

    fun processAudio(ctx: Context, wavBytes: ByteArray, lang: String): Outcome {
        val (wav, _) = Wav.normalize(wavBytes)
        val r = runner(ctx).run(wav, null, lang)
        return Outcome(r, NeedJson.parse(r.raw))
    }

    fun processText(ctx: Context, text: String, lang: String): Outcome {
        val r = runner(ctx).run(null, text, lang)
        return Outcome(r, NeedJson.parse(r.raw))
    }

    fun ruleJson(d: NeedDraft): JSONObject = JSONObject()
        .put("category", d.category.name).put("urgency", d.urgency).put("people", d.people)
        .put("vuln", JSONArray(d.vulnFlags.map { it.name }.sorted())).put("item", d.item)
        .put("landmark", d.landmark).put("summary", d.summary).put("lang", d.lang.tag)

    fun rule(text: String, lang: String): JSONObject = ruleJson(RuleExtractor.extract(text, Lang.fromTag(lang)))

    fun pssMb(): Double {
        val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi); return mi.totalPss / 1024.0
    }

    fun outcomeJson(o: Outcome): JSONObject = JSONObject()
        .put("raw", o.run.raw).put("error", o.run.error ?: JSONObject.NULL)
        .put("valid", o.parsed.valid && o.run.error == null).put("json_found", o.parsed.jsonFound)
        .put("issues", JSONArray(o.parsed.issues)).put("repairs", JSONArray(o.parsed.repairs))
        .put("parsed", o.parsed.toJson())
        .put("latency_ms", JSONObject().put("first_token", o.run.firstTokenMs).put("total", o.run.totalMs)
            .put("conversation_create", o.run.convCreateMs))
        .put("chunks", o.run.chunks).put("backend", o.run.backend).put("fell_back_to_cpu", o.run.fellBack)

    fun latencyText(load: LoadInfo?, o: Outcome): String =
        "backend=${o.run.backend}${if (o.run.fellBack) " (fell back from GPU)" else ""}  load=${load?.loadMs ?: "-"} ms  " +
            "first-token=${o.run.firstTokenMs} ms  total=${o.run.totalMs} ms  conv-create=${o.run.convCreateMs} ms  " +
            "PSS=${"%.0f".format(pssMb())} MB  valid=${o.parsed.valid && o.run.error == null}" +
            (if (o.run.error != null) "  ERROR=${o.run.error}" else "")

    /** Show an outcome in the UI, log it, and optionally persist next to a recording. */
    fun publish(ctx: Context, mode: String, lang: String, o: Outcome, saveBase: File?) {
        val rule = rule(o.parsed.transcript, lang)
        val li = runner(ctx).lastLoad
        val res = JSONObject().put("mode", mode).put("lang", lang).put("gemma", outcomeJson(o))
            .put("rule_on_gemma_transcript", rule).put("model_load_ms", li?.loadMs ?: JSONObject.NULL)
            .put("pss_mb", pssMb())
        Log.i(TAG, "RESULT $res")
        saveBase?.let { File(it.path + ".json").writeText(res.toString(2)) }
        ui {
            it.copy(
                busy = false, status = if (o.run.error == null) "done" else "error",
                transcript = o.parsed.transcript.ifEmpty { "(none) raw: " + o.run.raw.take(200) },
                json = if (o.parsed.jsonFound) o.parsed.toJson().toString(2) + (if (o.parsed.issues.isNotEmpty()) "\nissues: ${o.parsed.issues}" else "") else "NO JSON. raw:\n${o.run.raw.take(600)}",
                latency = latencyText(li, o), rule = rule.toString(2), backend = o.run.backend,
            )
        }
    }

    fun doAudio(ctx: Context, wavBytes: ByteArray, lang: String, saveBase: File?) {
        ui { it.copy(busy = true, status = "running audio...") }
        scope.launch {
            gate.withLock {
                try {
                    val li = ensureLoaded(ctx)
                    if (!li.ok) { ui { it.copy(busy = false) }; return@withLock }
                    publish(ctx, "audio", lang, processAudio(ctx, wavBytes, lang), saveBase)
                } catch (t: Throwable) {
                    Log.e(TAG, "audio failed", t); ui { it.copy(busy = false, status = "error: ${t.message}") }
                }
            }
        }
    }

    fun doText(ctx: Context, text: String, lang: String) {
        ui { it.copy(busy = true, status = "running text...") }
        scope.launch {
            gate.withLock {
                try {
                    val li = ensureLoaded(ctx)
                    if (!li.ok) { ui { it.copy(busy = false) }; return@withLock }
                    publish(ctx, "text", lang, processText(ctx, text, lang), null)
                } catch (t: Throwable) {
                    Log.e(TAG, "text failed", t); ui { it.copy(busy = false, status = "error: ${t.message}") }
                }
            }
        }
    }

    fun saveRecording(ctx: Context, pcm: ByteArray): File {
        val dir = File(ctx.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val base = File(dir, "rec_${System.currentTimeMillis()}")
        File(base.path + ".wav").writeBytes(Wav.pcmToWav(pcm))
        return base
    }
}
