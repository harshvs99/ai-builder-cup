package org.relay.voicespike

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val Blue = Color(0xFF1565C0)
private val Red = Color(0xFFC62828)
private val Grey = Color(0xFF9E9E9E)
private val Green = Color(0xFF2E7D32)
private const val MIN_BYTES = (0.8 * Wav.SAMPLE_RATE * 2).toInt() // 0.8 s of PCM16 mono

/** "Loading model... 12 s" / "Model ready ..." line, ticking while the model loads. */
@Composable
fun modelLine(st: UiState): String {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(st.modelState) {
        while (st.modelState == "loading") { now = SystemClock.elapsedRealtime(); delay(250) }
    }
    return when (st.modelState) {
        "loading" -> "Loading model… ${((now - st.loadStartedAt) / 1000).coerceAtLeast(0)} s (you can record meanwhile)"
        "ready" -> "Model ready (${st.backend}, ${"%.1f".format((Spike.runner(ctx).lastLoad?.loadMs ?: 0) / 1000.0)} s load)"
        "failed" -> "Model load FAILED: ${st.modelNote.take(160)}"
        else -> "Model not loaded yet"
    }
}

private fun langLabel(it: GuidedItem) = when {
    it.id.startsWith("hx") -> "Hinglish (read as written)"
    it.lang == "hi" -> "हिन्दी  Hindi"
    else -> "English (Indian)"
}

@Composable
fun GuidedRecordTab(store: GuidedStore, st: UiState, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val items = store.items
    val init = remember { store.loadState() }
    var index by remember { mutableIntStateOf(init.first) }
    var skipped by remember { mutableStateOf(init.second) }
    var rev by remember { mutableIntStateOf(0) } // bumped whenever a take is saved / deleted
    var recording by remember { mutableStateOf(false) }
    var recMs by remember { mutableLongStateOf(0L) }
    var msg by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    val recorder = remember { arrayOfNulls<Recorder>(1) }
    val recId = remember { arrayOfNulls<String>(1) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    val main = remember { Handler(Looper.getMainLooper()) }

    val item = items[index]
    val hasTake = run { rev; store.hasTake(item.id) }
    val recorded = run { rev; store.recordedCount() }
    val canRecord = !hasTake && !st.busy

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun hasPerm() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(Unit) { if (!hasPerm()) permLauncher.launch(Manifest.permission.RECORD_AUDIO) }

    fun stopPlay() { try { player[0]?.release() } catch (_: Throwable) {}; player[0] = null; playing = false }
    fun play() {
        stopPlay()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(store.wav(item.id).path)
            mp.setOnCompletionListener { stopPlay() }
            mp.prepare(); mp.start()
            player[0] = mp; playing = true
        } catch (t: Throwable) { Log.e(TAG, "playback failed", t); msg = "Playback failed: ${t.message}"; stopPlay() }
    }
    DisposableEffect(Unit) { onDispose { stopPlay(); try { recorder[0]?.stop() } catch (_: Throwable) {}; recorder[0] = null } }

    fun goTo(i: Int) {
        stopPlay(); msg = ""; index = i.coerceIn(0, items.size - 1); store.saveState(index, skipped)
    }

    fun finish(auto: Boolean) {
        stopRec(store, recorder, recId, auto) { id, secs ->
            skipped = skipped - id; store.saveState(index, skipped); rev++
            msg = if (secs < 0) "Too short (under 0.8 s), discarded. Hold the button for the whole sentence."
            else "Saved ${"%.1f".format(secs)} s${if (auto) " (hit the 15 s limit)" else ""}. Tap Play to check, then Next."
        }
        recording = false
    }

    fun startRec(): Boolean {
        stopPlay()
        return try {
            recId[0] = item.id
            recorder[0] = Recorder { main.post { finish(true) } }.also { it.start() }
            recording = true; recMs = SystemClock.elapsedRealtime(); msg = ""
            true
        } catch (t: Throwable) { recorder[0] = null; Log.e(TAG, "record start failed", t); msg = "Record error: ${t.message}"; false }
    }

    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording) { while (recording) { elapsed = SystemClock.elapsedRealtime() - recMs; delay(100) } }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // header: progress + counts + model status
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1} / ${items.size}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val next = items.indexOfFirst { !store.hasTake(it.id) && it.id !in skipped }
                    .takeIf { it >= 0 } ?: items.indexOfFirst { !store.hasTake(it.id) }
                if (next >= 0) goTo(next) else msg = "All prompts have a take."
            }) { Text("Next pending", fontSize = 16.sp) }
        }
        Text("$recorded recorded · ${skipped.count { !store.hasTake(it) }} skipped · ${langLabel(item)}", fontSize = 14.sp)
        Text(modelLine(st), fontSize = 13.sp, color = if (st.modelState == "failed") Red else Color(0xFF555555))

        // prompt card
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(when { hasTake -> Color(0xFFE8F5E9); item.id in skipped -> Color(0xFFEEEEEE); else -> Color(0xFFF5F7FA) }, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text(item.text, fontSize = 30.sp, lineHeight = 42.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
        }

        Text(
            when {
                recording -> "Recording… keep holding and read the sentence"
                msg.isNotEmpty() -> msg
                hasTake -> "Take saved (${"%.1f".format(store.takeSeconds(item.id))} s)."
                st.busy -> "Eval is running, recording is locked."
                else -> "Hold the blue button and read the sentence aloud."
            },
            fontSize = 15.sp, minLines = 2, color = if (msg.startsWith("Too short") || msg.contains("error", true)) Red else Color(0xFF333333),
        )

        // play / re-record (only when a take exists)
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasTake) {
                Button(onClick = { if (playing) stopPlay() else play() }, modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text(if (playing) "Stop" else "Play", fontSize = 18.sp) }
                OutlinedButton(onClick = { stopPlay(); store.deleteTake(item.id); rev++; msg = "Take deleted. Hold the button to record again." },
                    modifier = Modifier.weight(1f).height(56.dp), enabled = !st.busy) { Text("Re-record", fontSize = 18.sp) }
            }
        }

        // the hold-to-record button
        Box(
            Modifier.fillMaxWidth().height(120.dp)
                .background(when { recording -> Red; canRecord -> Blue; else -> Grey }, RoundedCornerShape(20.dp))
                .pointerInput(item.id, canRecord) {
                    detectTapGestures(onPress = {
                        if (!canRecord) return@detectTapGestures
                        if (!hasPerm()) { permLauncher.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                        if (startRec()) { tryAwaitRelease(); finish(false) }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    recording -> "RECORDING  ${"%.1f".format(elapsed / 1000.0)} s\nrelease to save (max 15 s)"
                    hasTake -> "TAKE SAVED  ${"%.1f".format(store.takeSeconds(item.id))} s\nuse Re-record to replace"
                    st.busy -> "LOCKED while eval runs"
                    else -> "HOLD TO RECORD"
                },
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 28.sp,
            )
        }

        // navigation
        val last = index == items.size - 1
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { goTo(index - 1) }, enabled = index > 0 && !recording, modifier = Modifier.weight(1f).height(64.dp)) {
                Text("Prev", fontSize = 18.sp)
            }
            OutlinedButton(
                onClick = { if (!hasTake) skipped = skipped + item.id; if (last) goTo(index) else goTo(index + 1) },
                enabled = !last && !recording, modifier = Modifier.weight(1f).height(64.dp),
            ) { Text("Skip", fontSize = 18.sp) }
            Button(
                onClick = { if (last) onFinish() else goTo(index + 1) },
                enabled = !recording, modifier = Modifier.weight(1.4f).height(64.dp),
            ) { Text(if (last) "Finish" else "Next", fontSize = 20.sp) }
        }
    }
}

/** Stops the recorder (if any) and persists the take. [onSaved] gets (id, seconds, or -1 when discarded as too short). */
private fun stopRec(
    store: GuidedStore, recorder: Array<Recorder?>, recId: Array<String?>, auto: Boolean, onSaved: (String, Double) -> Unit,
) {
    val r = recorder[0] ?: return
    recorder[0] = null
    val id = recId[0] ?: return
    val pcm = r.stop()
    if (pcm.size < MIN_BYTES) { onSaved(id, -1.0); return }
    try {
        store.saveTake(id, pcm)
        onSaved(id, pcm.size / (Wav.SAMPLE_RATE * 2.0))
    } catch (t: Throwable) {
        Log.e(TAG, "save take failed (auto=$auto)", t)
    }
}

@Composable
fun GuidedEvalTab(store: GuidedStore, st: UiState) {
    val ctx = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    val recorded = run { rev; store.recordedCount() }
    val mono = FontFamily.Monospace
    LaunchedEffect(Unit) {
        rev++
        if (Spike.state.value.evalSummary.isEmpty()) {
            val f = java.io.File(store.dir, "summary.txt")
            if (f.exists()) Spike.ui { it.copy(evalSummary = f.readText()) }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$recorded / ${store.items.size} prompts recorded", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(modelLine(st), fontSize = 13.sp, color = if (st.modelState == "failed") Red else Color(0xFF555555))
        Button(
            onClick = { store.writeManifest(); Spike.runEval(ctx, store.dir.absolutePath) },
            enabled = recorded > 0 && !st.busy, modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text("Run eval on my recordings", fontSize = 18.sp) }
        if (st.modelState == "failed") TextButton(onClick = { Spike.preload(ctx, force = true) }) { Text("Retry model load") }
        if (st.evalStatus.isNotEmpty()) Text(st.evalStatus, fontSize = 14.sp, color = Color(0xFF8A4B00))
        Text("Results (also in files/guided/summary.txt and results.jsonl)", style = MaterialTheme.typography.labelLarge)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF5F7FA), RoundedCornerShape(12.dp)).padding(8.dp)) {
            Text(
                st.evalSummary.ifEmpty { "No eval yet. Record some prompts, then run it." },
                fontFamily = mono, fontSize = 10.sp, lineHeight = 13.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
            )
        }
    }
}
