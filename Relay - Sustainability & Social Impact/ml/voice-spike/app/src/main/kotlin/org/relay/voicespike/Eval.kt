package org.relay.voicespike

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Batch evaluation. <dir>/manifest.json = [{file?, lang, expected{...}, reference_transcript}]. Model is loaded once.
 * Per clip: Gemma on audio (if file), Gemma text on reference_transcript, RuleExtractor on reference_transcript, and, when the
 * Gemma 3 270M file is present (see [ModelSpec.G270M]), the 270M on reference_transcript as `gemma270_text`.
 * Output: <dir>/results.jsonl (one JSON object per clip).
 */
object Eval {
    fun resolveDir(ctx: Context, arg: String): File {
        val f = File(arg)
        return if (f.isAbsolute) f else File(ctx.getExternalFilesDir(null), arg)
    }

    fun run(ctx: Context, dirArg: String) {
        val dir = resolveDir(ctx, dirArg)
        val out = File(dir, "results.jsonl")
        try {
            val items = JSONArray(File(dir, "manifest.json").readText())
            out.writeText("")
            Spike.ui { it.copy(evalStatus = "eval: loading model...", busy = true) }
            val li = Spike.ensureLoaded(ctx)
            Log.i(TAG, "EVAL start dir=$dir n=${items.length()} load=$li")
            if (!li.ok) {
                out.writeText(JSONObject().put("fatal", "model load failed").put("note", li.note).toString() + "\n")
                Log.i(TAG, "EVAL DONE n=0 FAILED load: ${li.note}")
                Spike.ui { it.copy(evalStatus = "eval FAILED: ${li.note}", busy = false) }
                return
            }
            // Gemma 3 270M is optional: only if its file was pushed. Load time is recorded on every row (warm-up gate input).
            val small = Spike.smallRunner(ctx)
            var smallLoad: LoadInfo? = null
            if (small.modelFile().exists()) {
                Spike.ui { it.copy(evalStatus = "eval: loading Gemma 3 270M...") }
                smallLoad = small.load(Spike.backendPref)
                Log.i(TAG, "EVAL 270M load=$smallLoad file=${small.modelFile().name}")
            } else Log.i(TAG, "EVAL 270M skipped: no file (${small.modelFile().absolutePath})")
            var done = 0; var audioValid = 0; var textValid = 0
            for (i in 0 until items.length()) {
                val m = items.getJSONObject(i)
                val lang = m.optString("lang", "en")
                val ref = m.optString("reference_transcript", "")
                val file = m.optString("file", "")
                val line = JSONObject().put("index", i).put("file", file).put("lang", lang)
                    .put("expected", m.opt("expected") ?: JSONObject.NULL).put("reference_transcript", ref)
                    .put("backend", li.backend).put("model_load_ms", li.loadMs)
                if (smallLoad != null) line.put("small_load_ms", if (smallLoad.ok) smallLoad.loadMs else JSONObject.NULL)
                    .put("small_backend", smallLoad.backend).put("small_note", smallLoad.note)
                Spike.ui { it.copy(evalStatus = "eval ${i + 1}/${items.length()} $file") }

                if (file.isNotEmpty() && File(dir, file).exists()) {
                    val bytes = File(dir, file).readBytes()
                    val info = try { Wav.parse(bytes) } catch (e: Exception) { null }
                    line.put("audio_seconds", info?.seconds ?: JSONObject.NULL)
                    line.put("audio_format", info?.let { "${it.sampleRate}Hz/${it.channels}ch/${it.bits}bit" } ?: "unparseable")
                    val o = try { Spike.processAudio(ctx, bytes, lang) } catch (t: Throwable) {
                        Outcome(RunResult("", "${t.javaClass.simpleName}: ${t.message}", 0, 0, 0, 0, li.backend, false), NeedJson.parse(""))
                    }
                    line.put("gemma_audio", Spike.outcomeJson(o)).put("pss_mb_after_audio", Spike.pssMb())
                    if (o.parsed.valid && o.run.error == null) audioValid++
                } else if (file.isNotEmpty()) line.put("gemma_audio", JSONObject().put("error", "file missing: $file").put("valid", false))

                if (ref.isNotEmpty()) {
                    val t = try { Spike.processText(ctx, ref, lang) } catch (th: Throwable) {
                        Outcome(RunResult("", "${th.javaClass.simpleName}: ${th.message}", 0, 0, 0, 0, li.backend, false), NeedJson.parse(""))
                    }
                    line.put("gemma_text", Spike.outcomeJson(t)).put("pss_mb_after_text", Spike.pssMb())
                    if (t.parsed.valid && t.run.error == null) textValid++
                    if (smallLoad?.ok == true) {
                        val s2 = try { Spike.processTextSmall(ctx, ref, lang) } catch (th: Throwable) {
                            Outcome(RunResult("", "${th.javaClass.simpleName}: ${th.message}", 0, 0, 0, 0, smallLoad.backend, false), NeedJson.parse(""))
                        }
                        line.put("gemma270_text", Spike.outcomeJson(s2)).put("pss_mb_after_270", Spike.pssMb())
                    }
                    val t0 = System.nanoTime()
                    val rule = Spike.rule(ref, lang)
                    line.put("rule", rule.put("latency_ms", (System.nanoTime() - t0) / 1e6))
                }
                out.appendText(line.toString() + "\n")
                done++
                Log.i(TAG, "EVAL clip ${i + 1}/${items.length()} $file lang=$lang audio=${line.optJSONObject("gemma_audio")?.optJSONObject("parsed")} ")
            }
            small.close() // free the 270M engine before the next E2B-only use
            Log.i(TAG, "EVAL DONE n=$done backend=${li.backend} audio_valid=$audioValid text_valid=$textValid results=${out.absolutePath}")
            val summary = try { Summary.build(Summary.readRows(out)) } catch (t: Throwable) { "summary failed: ${t.message}" }
            try { File(dir, "summary.txt").writeText(summary) } catch (_: Throwable) {}
            summary.lines().forEach { Log.i(TAG, "EVAL SUMMARY $it") }
            Spike.ui { it.copy(evalStatus = "EVAL DONE n=$done audio_valid=$audioValid text_valid=$textValid", busy = false,
                evalSummary = if (dir.name == "guided") summary else it.evalSummary) } // the Eval tab only shows the guided run
        } catch (t: Throwable) {
            Log.e(TAG, "EVAL failed", t)
            try { out.appendText(JSONObject().put("fatal", "${t.javaClass.simpleName}: ${t.message}").toString() + "\n") } catch (_: Throwable) {}
            Log.i(TAG, "EVAL DONE n=0 FAILED ${t.message}")
            Spike.ui { it.copy(evalStatus = "eval FAILED: ${t.message}", busy = false) }
        }
    }
}
