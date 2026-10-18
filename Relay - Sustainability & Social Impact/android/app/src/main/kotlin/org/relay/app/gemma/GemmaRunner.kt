package org.relay.app.gemma

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "GemmaRunner"

data class GemmaLoad(val ok: Boolean, val backend: String, val loadMs: Long, val note: String)

data class GemmaRun(
    val raw: String,
    val error: String?,
    /** From just before sendMessageAsync to the first streamed chunk (prefill + first token). */
    val firstTokenMs: Long,
    val totalMs: Long,
    val backend: String,
    val fellBack: Boolean,
) {
    val parsed: ParsedNeed by lazy { GemmaNeedParser.parse(raw) }
}

/**
 * Gemma 4 E2B on device through LiteRT-LM (audio + text). The model file is NOT bundled: it lives at
 * `<externalFilesDir>/gemma-4-E2B-it.litertlm` (push it with adb, see ml/voice-spike/README.md). Loads once; each request
 * gets a fresh Conversation. Blocking calls: run them off the main thread.
 *
 * LiteRT-LM needs API 31+. The app's minSdk is 26, so everything here is a no-op below 31 ([supported] = false).
 *
 * Lifecycle is automatic: [runText]/[runAudio] load the model on first use ([ensureLoaded], gated on device RAM),
 * [IDLE_MS] after the last request it unloads itself, and [onAppBackgrounded] / [onLowMemory] (called from
 * `RelayApplication.onTrimMemory`) unload immediately. Callers never load or unload by hand; a failed load just returns an
 * error result and the caller keeps using the rule extractor.
 */
class GemmaRunner private constructor(private val ctx: Context) {
    @Volatile private var engine: Engine? = null
    @Volatile var backend: String = "none"; private set
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val idleClose = Runnable { bg.execute { closeIfIdle() } }
    private var inFlight = 0

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 31
    fun modelFile(): File = File(ctx.getExternalFilesDir(null), MODEL_FILE)
    fun isLoaded() = engine != null

    /** Why the model must not be loaded right now (too little RAM, no file, old Android), or null when it may be. */
    fun blockedReason(): String? {
        if (!supported) return "needs Android 12 (API 31) or newer"
        if (!modelFile().exists()) return "model not found at ${modelFile().absolutePath}"
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (mi.totalMem < MIN_TOTAL_RAM) return "device has ${mi.totalMem / MB} MB RAM, needs about ${MIN_TOTAL_RAM / MB} MB"
        if (!isLoaded() && (mi.lowMemory || mi.availMem < MIN_FREE_RAM)) return "only ${mi.availMem / MB} MB free right now, needs ${MIN_FREE_RAM / MB} MB"
        return null
    }

    /** Loads if needed, unless [blockedReason] says no. Cheap when already loaded. */
    fun ensureLoaded(): GemmaLoad {
        if (isLoaded()) return GemmaLoad(true, backend, 0, "already loaded")
        blockedReason()?.let { return GemmaLoad(false, "none", 0, it) }
        return load("gpu")
    }

    /** The app went to the background: give the memory back at once. */
    fun onAppBackgrounded() { bg.execute { closeIfIdle() } }

    /** The system is short of memory: drop the model even if a request is queued (it will fail and the rules take over). */
    fun onLowMemory() { main.removeCallbacks(idleClose); bg.execute { close() } }

    // close() takes the load lock, which a running load holds for seconds: never call it on the main thread.
    private val bg = Executors.newSingleThreadExecutor { r -> Thread(r, "gemma-lifecycle").apply { isDaemon = true } }
    private fun closeIfIdle() = synchronized(lock) { if (inFlight == 0) close() }
    private fun armIdleTimer() { main.removeCallbacks(idleClose); main.postDelayed(idleClose, IDLE_MS) }

    /** [pref] "gpu" tries GPU then falls back to CPU; "cpu" goes straight to CPU. */
    fun load(pref: String = "gpu"): GemmaLoad = synchronized(lock) {
        if (!supported) return GemmaLoad(false, "none", 0, "needs Android 12 (API 31) or newer")
        if (engine != null) return GemmaLoad(true, backend, 0, "already loaded")
        val model = modelFile()
        if (!model.exists()) return GemmaLoad(false, "none", 0, "model not found at ${model.absolutePath}")
        val notes = StringBuilder()
        for (b in if (pref.equals("cpu", true)) listOf("cpu") else listOf("gpu", "cpu")) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = model.absolutePath,
                        backend = if (b == "gpu") Backend.GPU() else Backend.CPU(),
                        audioBackend = Backend.CPU(), // the audio executor must be CPU for Gemma 3n/4
                        maxNumTokens = 3072,
                        cacheDir = ctx.cacheDir.absolutePath,
                    ),
                )
                e.initialize()
                engine = e
                backend = b.uppercase()
                return GemmaLoad(true, backend, SystemClock.elapsedRealtime() - t0, notes.toString().trim())
            } catch (t: Throwable) {
                Log.e(TAG, "backend $b failed", t)
                notes.append("backend $b failed: ${t.javaClass.simpleName}: ${t.message?.take(200)}; ")
            }
        }
        GemmaLoad(false, "none", 0, notes.toString())
    }

    fun close() = synchronized(lock) {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null; backend = "none"
    }

    /** Text request (the transcript is passed unchanged). If GPU inference errors, reloads on CPU once and retries. */
    fun runText(text: String, langHint: String): GemmaRun = tracked { withFallback { once(GemmaPrompt.SYSTEM, wav = null, text = text, langHint = langHint) } }

    /** Audio request: [wav] is 16 kHz mono PCM16 WAV bytes. */
    fun runAudio(wav: ByteArray, langHint: String): GemmaRun = tracked { withFallback { once(GemmaPrompt.SYSTEM, wav = wav, text = null, langHint = langHint) } }

    /**
     * A request with its own [system] prompt and the [instruction] sent as is (triage turns). Short: a conversation cannot
     * wait the minutes a batch intake can, so it times out after [timeoutS] and the caller falls back to rules.
     */
    fun runCustom(system: String, instruction: String, timeoutS: Long = 25, maxTokens: Int = 300): GemmaRun =
        tracked { withFallback { once(system, wav = null, text = null, langHint = "", instruction = instruction, timeoutS = timeoutS, maxTokens = maxTokens) } }

    /** Load on demand, count the request as in flight (so the idle timer cannot unload mid-run), re-arm the idle timer. */
    private fun tracked(block: () -> GemmaRun): GemmaRun {
        synchronized(lock) { inFlight++ }
        main.removeCallbacks(idleClose)
        try {
            val l = ensureLoaded()
            if (!l.ok) return GemmaRun("", "model unavailable: ${l.note}", 0, 0, backend, false)
            return block()
        } finally {
            synchronized(lock) { inFlight-- }
            armIdleTimer()
        }
    }

    private fun withFallback(block: () -> GemmaRun): GemmaRun {
        val r = block()
        if (r.error != null && backend == "GPU" && r.error?.contains("timeout after") != true) {
            Log.w(TAG, "GPU inference failed (${r.error}); reloading on CPU")
            close()
            if (load("cpu").ok) return block().copy(fellBack = true)
        }
        return r
    }

    private fun once(
        system: String, wav: ByteArray?, text: String?, langHint: String,
        instruction: String? = null, timeoutS: Long = TIMEOUT_S, maxTokens: Int = 400,
    ): GemmaRun {
        val e = engine ?: return GemmaRun("", "engine not loaded", 0, 0, backend, false)
        val t0 = SystemClock.elapsedRealtime()
        try {
            val conv = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    maxOutputToken = maxTokens,
                    extraContext = mapOf("enable_thinking" to false),
                ),
            )
            conv.use {
                val parts = ArrayList<Content>()
                if (wav != null) {
                    parts += Content.AudioBytes(wav)
                    parts += Content.Text(GemmaPrompt.audioInstruction(langHint))
                } else if (instruction != null) {
                    parts += Content.Text(instruction)
                } else {
                    parts += Content.Text(GemmaPrompt.textInstruction(text ?: "", langHint))
                }
                val sb = StringBuilder()
                val done = CountDownLatch(1)
                var err: Throwable? = null
                var first = -1L
                val tSend = SystemClock.elapsedRealtime()
                it.sendMessageAsync(Contents.of(parts), object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (first < 0) first = SystemClock.elapsedRealtime() - tSend
                        sb.append(message.toString())
                    }
                    override fun onDone() { done.countDown() }
                    override fun onError(throwable: Throwable) { err = throwable; done.countDown() }
                })
                if (!done.await(timeoutS, TimeUnit.SECONDS)) {
                    try { it.cancelProcess() } catch (_: Throwable) {}
                    err = IllegalStateException("timeout after $timeoutS s")
                }
                val total = SystemClock.elapsedRealtime() - tSend
                return GemmaRun(
                    sb.toString(), err?.let { x -> "${x.javaClass.simpleName}: ${x.message}" },
                    if (first < 0) total else first, total, backend, false,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "run failed", t)
            return GemmaRun("", "${t.javaClass.simpleName}: ${t.message}", 0, SystemClock.elapsedRealtime() - t0, backend, false)
        }
    }

    companion object {
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        private const val TIMEOUT_S = 180L
        /** Unload this long after the last request. */
        const val IDLE_MS = 45_000L
        private const val MB = 1024L * 1024L
        // Thresholds are estimates (E2B is ~2.6 GB on disk): tune after measuring on real phones.
        private const val MIN_TOTAL_RAM = 5_000_000_000L
        private const val MIN_FREE_RAM = 1_200_000_000L

        @Volatile private var instance: GemmaRunner? = null
        /** One runner per process, so the app-level memory callbacks and the screens share one engine. */
        fun get(ctx: Context): GemmaRunner =
            instance ?: synchronized(this) { instance ?: GemmaRunner(ctx.applicationContext).also { instance = it } }
        /** The runner if one was ever created, without creating it (for memory callbacks). */
        fun peek(): GemmaRunner? = instance
    }
}
