package org.relay.voicespike

import android.content.Context
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
import java.util.concurrent.TimeUnit

const val TAG = "VoiceSpike"

/** One on-device model: where its file lives, whether it takes audio, and which prompt it gets. */
class ModelSpec(
    val name: String,
    /** Exact file name under the app's external files dir; if absent, [fileContains] is tried. */
    val fileName: String,
    /** Fallback: first *.litertlm whose lower-cased name contains this (null = no fallback). */
    val fileContains: String?,
    val audio: Boolean,
    val system: String,
    val textInstruction: (String, String) -> String,
) {
    fun resolve(dir: File?): File {
        val exact = File(dir, fileName)
        if (exact.exists() || fileContains == null) return exact
        val hit = dir?.listFiles()?.firstOrNull { it.name.lowercase().let { n -> fileContains in n && n.endsWith(".litertlm") } }
        return hit ?: exact
    }

    companion object {
        val E2B = ModelSpec("Gemma 4 E2B", "gemma-4-E2B-it.litertlm", null, true, Prompt.SYSTEM, Prompt::textInstruction)

        /**
         * Gemma 3 270M, text only. TODO(verify before the first download): the litert-community repo name and file name are
         * NOT verified offline. Check https://huggingface.co/litert-community for the Gemma 3 270M LiteRT-LM repo and its exact
         * .litertlm file (there may be several quantisations; prefer int8/q8), then set [fileName] to match. Until then any
         * *.litertlm in the external files dir whose name contains "270m" is picked up.
         */
        val G270M = ModelSpec("Gemma 3 270M", "gemma3-270m-it.litertlm", "270m", false, Prompt.SYSTEM_SMALL, Prompt::textInstructionSmall)
    }
}

data class LoadInfo(val ok: Boolean, val backend: String, val loadMs: Long, val note: String)

data class RunResult(
    val raw: String,
    val error: String?,
    /** Wall time from just before sendMessageAsync to the first streamed chunk (audio/text prefill + first token). */
    val firstTokenMs: Long,
    val totalMs: Long,
    val convCreateMs: Long,
    val chunks: Int,
    val backend: String,
    val fellBack: Boolean,
)

/**
 * Loads Gemma once, then runs one fresh Conversation per request (no history carry-over).
 * [spec] selects the model file and the prompt: [ModelSpec.E2B] (audio + text) or [ModelSpec.G270M] (text only).
 */
class GemmaRunner(private val ctx: Context, private val spec: ModelSpec = ModelSpec.E2B) {
    @Volatile private var engine: Engine? = null
    @Volatile var backend: String = "none"; private set
    @Volatile var lastLoad: LoadInfo? = null; private set
    private val lock = Any()

    fun modelFile(): File = spec.resolve(ctx.getExternalFilesDir(null))
    fun isLoaded() = engine != null

    /** [pref] = "gpu" (try GPU, fall back to CPU) or "cpu". */
    fun load(pref: String = "gpu"): LoadInfo = synchronized(lock) {
        engine?.let { return LoadInfo(true, backend, lastLoad?.loadMs ?: 0, "already loaded") }
        val model = modelFile()
        if (!model.exists()) return LoadInfo(false, "none", 0, "model not found at ${model.absolutePath}").also { lastLoad = it }
        val notes = StringBuilder()
        val order = if (pref.equals("cpu", true)) listOf("cpu") else listOf("gpu", "cpu")
        for (b in order) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val cfg = EngineConfig(
                    modelPath = model.absolutePath,
                    backend = if (b == "gpu") Backend.GPU() else Backend.CPU(),
                    audioBackend = if (spec.audio) Backend.CPU() else null, // Gallery: audio executor must be CPU for Gemma 3n/4
                    maxNumTokens = 3072,
                    cacheDir = ctx.cacheDir.absolutePath,
                )
                val e = Engine(cfg)
                e.initialize()
                engine = e
                backend = b.uppercase()
                val info = LoadInfo(true, backend, SystemClock.elapsedRealtime() - t0, notes.toString().trim())
                lastLoad = info
                Log.i(TAG, "LOAD ok backend=$backend ms=${info.loadMs} $notes")
                return info
            } catch (t: Throwable) {
                val msg = "backend $b failed after ${SystemClock.elapsedRealtime() - t0} ms: ${t.javaClass.simpleName}: ${t.message?.take(300)}"
                Log.e(TAG, msg, t)
                notes.append(msg).append("; ")
            }
        }
        LoadInfo(false, "none", 0, notes.toString()).also { lastLoad = it }
    }

    fun close() = synchronized(lock) {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null; backend = "none"
    }

    /** Runs audio (a WAV) or text. If GPU inference errors out, reloads on CPU once and retries. */
    fun run(wav: ByteArray?, text: String?, langHint: String, allowFallback: Boolean = true): RunResult {
        val r = runOnce(wav, text, langHint)
        if (r.error != null && allowFallback && backend == "GPU") {
            Log.w(TAG, "GPU inference failed (${r.error}); reloading on CPU")
            close()
            val li = load("cpu")
            if (li.ok) return runOnce(wav, text, langHint).copy(fellBack = true)
        }
        return r
    }

    private fun runOnce(wav: ByteArray?, text: String?, langHint: String): RunResult {
        val e = engine ?: return RunResult("", "engine not loaded", 0, 0, 0, 0, backend, false)
        val t0 = SystemClock.elapsedRealtime()
        try {
            val conv = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(spec.system),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    maxOutputToken = 400,
                    extraContext = mapOf("enable_thinking" to false),
                ),
            )
            val convMs = SystemClock.elapsedRealtime() - t0
            conv.use {
                val parts = ArrayList<Content>()
                if (wav != null && spec.audio) {
                    parts += Content.AudioBytes(wav)
                    parts += Content.Text(Prompt.audioInstruction(langHint))
                } else {
                    parts += Content.Text(spec.textInstruction(text ?: "", langHint))
                }
                val sb = StringBuilder()
                val done = CountDownLatch(1)
                var err: Throwable? = null
                var first = -1L
                var chunks = 0
                val tSend = SystemClock.elapsedRealtime()
                it.sendMessageAsync(Contents.of(parts), object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (first < 0) first = SystemClock.elapsedRealtime() - tSend
                        chunks++
                        sb.append(message.toString())
                    }
                    override fun onDone() { done.countDown() }
                    override fun onError(throwable: Throwable) { err = throwable; done.countDown() }
                })
                if (!done.await(180, TimeUnit.SECONDS)) {
                    try { it.cancelProcess() } catch (_: Throwable) {}
                    err = IllegalStateException("timeout after 180 s")
                }
                val total = SystemClock.elapsedRealtime() - tSend
                return RunResult(
                    raw = sb.toString(), error = err?.let { x -> "${x.javaClass.simpleName}: ${x.message}" },
                    firstTokenMs = if (first < 0) total else first, totalMs = total, convCreateMs = convMs,
                    chunks = chunks, backend = backend, fellBack = false,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "run failed", t)
            return RunResult("", "${t.javaClass.simpleName}: ${t.message}", 0, SystemClock.elapsedRealtime() - t0, 0, 0, backend, false)
        }
    }
}
