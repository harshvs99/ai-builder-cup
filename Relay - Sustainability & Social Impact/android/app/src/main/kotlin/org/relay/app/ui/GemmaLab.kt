package org.relay.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.relay.app.gemma.GemmaRun
import org.relay.app.gemma.GemmaRunner
import org.relay.app.gemma.Recorder
import org.relay.app.gemma.Wav
import org.relay.core.intake.FieldMerge
import org.relay.core.nlu.NeedDraft
import org.relay.core.nlu.RuleExtractor

/**
 * Developer-only lab for the on-device Gemma intake: load the model, run a typed sentence or a short voice clip through
 * it, and compare Gemma's fields with the rule extractor's and the per-field merge (`FieldMerge.PER_EVAL`). Not part of
 * the villager flow, so the text is plain English and not localised.
 */
@Composable
fun GemmaLab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // The process-wide runner loads itself on the first request and unloads itself when idle, backgrounded or short of RAM.
    val runner = remember { GemmaRunner.get(ctx) }
    var loadedNow by remember { mutableStateOf(runner.isLoaded()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("we are 4 people trapped on the roof, water is rising, near the school") }
    var run by remember { mutableStateOf<GemmaRun?>(null) }
    var rules by remember { mutableStateOf<NeedDraft?>(null) }
    var recording by remember { mutableStateOf(false) }
    val recorder = remember { Recorder(onAutoStop = {}) }

    fun fmt(d: NeedDraft) = "${d.category} u${d.urgency} p${d.people} ${d.vulnFlags.joinToString("+")} item=${d.item} lm=${d.landmark}"

    fun finish(r: GemmaRun, forText: String?) {
        run = r
        // Rules run on the model's transcript for audio, on the typed text otherwise.
        val t = forText ?: r.parsed.transcript
        rules = if (t.isBlank()) null else RuleExtractor.extract(t, null)
    }

    LaunchedEffect(Unit) { while (true) { loadedNow = runner.isLoaded(); delay(1_000) } }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Gemma (on-device intake, experimental)")
        Text(
            "Model file: ${runner.modelFile().absolutePath}" +
                (if (runner.modelFile().exists()) " (found)" else " (missing: adb push gemma-4-E2B-it.litertlm there)"),
            style = MaterialTheme.typography.bodySmall,
        )
                Text(
            "Model is ${if (loadedNow) "LOADED on ${runner.backend}" else "unloaded"} (loads on first use, unloads after " +
                "${GemmaRunner.IDLE_MS / 1000} s idle, when the app is backgrounded, or under memory pressure)",
            color = if (loadedNow) COLOR_OK else COLOR_NEUTRAL, style = MaterialTheme.typography.bodySmall,
        )
        runner.blockedReason()?.let { Text("Would not load now: $it", color = COLOR_BAD, style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text request") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = busy == null && text.isNotBlank(), onClick = {
                busy = "Running on text (loads the model first if needed)…"
                val input = text
                scope.launch { finish(withContext(Dispatchers.Default) { runner.runText(input, "en") }, input); busy = null }
            }) { Text("Run text") }
            OutlinedButton(enabled = busy == null || recording, onClick = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else if (!recording) {
                    try { recorder.start(); recording = true } catch (t: Throwable) { busy = null; run = GemmaRun("", "mic: ${t.message}", 0, 0, "", false) }
                } else {
                    val wav = Wav.pcmToWav(recorder.stop()); recording = false
                    busy = "Running on audio (loads the model first if needed)…"
                    scope.launch { finish(withContext(Dispatchers.Default) { runner.runAudio(wav, "en") }, null); busy = null }
                }
            }) { Text(if (recording) "Stop and run" else "Record voice") }
        }
        busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        run?.let { r ->
            val p = r.parsed
            val merged = rules?.let { FieldMerge.PER_EVAL.merge(it, p.toModelFields()).first }
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            Text("${r.backend}${if (r.fellBack) " (fell back from GPU)" else ""}: first token ${r.firstTokenMs} ms, total ${r.totalMs} ms", style = MaterialTheme.typography.bodySmall)
            r.error?.let { Text("Error: $it", color = COLOR_BAD, style = MaterialTheme.typography.bodySmall) }
            Text("Raw: ${r.raw.trim()}", style = mono)
            Text(
                "Gemma: " + (if (p.valid) "valid" else "INVALID ${p.issues}") +
                    (if (p.repairs.isNotEmpty()) " repaired ${p.repairs}" else ""),
                color = if (p.valid) COLOR_OK else COLOR_BAD, style = mono,
            )
            if (p.jsonFound) Text("  ${p.category} u${p.urgency} p${p.people} ${p.vuln.joinToString("+")} item=${p.item} lm=${p.landmark} lang=${p.lang.tag}", style = mono)
            if (p.transcript.isNotBlank()) Text("Transcript: ${p.transcript}", style = mono)
            rules?.let { Text("Rules:  ${fmt(it)}", style = mono) }
            merged?.let { Text("Merged: ${fmt(it)}", style = mono) }
        }
    }
}
