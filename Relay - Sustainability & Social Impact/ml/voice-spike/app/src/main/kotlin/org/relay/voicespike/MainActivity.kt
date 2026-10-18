package org.relay.voicespike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private var recorder: Recorder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val store = GuidedStore(applicationContext)
        store.writeManifest() // self-heal: manifest always matches the takes on disk
        handleIntent(intent) // may set the backend preference, so it runs before the preload starts
        Spike.preload(applicationContext)
        setContent { MaterialTheme { Root(store) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * adb drivers:
     *   --es eval <dir>            batch eval (dir absolute, or relative to the app's external files dir)
     *   --es text "<msg>"          single text-mode run          (--es lang en)
     *   --es wav <path>            single audio run from a WAV   (--es lang hi)
     *   --es backend cpu|gpu       backend preference (default gpu with CPU fallback)
     *   --ez load true             just load the model
     */
    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("backend")?.let { Spike.backendPref = it }
        val lang = i.getStringExtra("lang") ?: "en"
        Spike.ui { it.copy(lang = lang) }
        val ctx = applicationContext
        i.getStringExtra("eval")?.let { dir ->
            i.removeExtra("eval")
            Spike.scope.launch { Spike.gate.lock(); try { Eval.run(ctx, dir) } finally { Spike.gate.unlock() } }
        }
        i.getStringExtra("text")?.let { t -> i.removeExtra("text"); Spike.doText(ctx, t, lang) }
        i.getStringExtra("wav")?.let { p ->
            i.removeExtra("wav")
            val f = File(p)
            if (!f.exists()) { Log.e(TAG, "wav not found: $p"); return }
            Spike.doAudio(ctx, f.readBytes(), lang, null)
        }
        if (i.getBooleanExtra("load", false)) {
            i.removeExtra("load")
            Spike.scope.launch { Spike.gate.lock(); try { Spike.ensureLoaded(ctx) } finally { Spike.gate.unlock() } }
        }
    }

    @Composable
    private fun Root(store: GuidedStore) {
        val st by Spike.state.collectAsStateWithLifecycle()
        var tab by remember { mutableStateOf("record") }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                FilterChip(selected = tab == "record", onClick = { tab = "record" }, label = { Text("Record", fontSize = 16.sp) })
                FilterChip(selected = tab == "eval", onClick = { tab = "eval" }, label = { Text("Eval", fontSize = 16.sp) })
                FilterChip(selected = tab == "free", onClick = { tab = "free" }, label = { Text("Free-form", fontSize = 16.sp) })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    "record" -> GuidedRecordTab(store, st, onFinish = { tab = "eval" })
                    "eval" -> GuidedEvalTab(store, st)
                    else -> Screen()
                }
            }
        }
    }

    @Composable
    private fun Screen() {
        val st by Spike.state.collectAsStateWithLifecycle()
        var typed by remember { mutableStateOf("my mother needs insulin, near the school") }
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val mono = FontFamily.Monospace

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Gemma 4 E2B voice spike", style = MaterialTheme.typography.titleLarge)
            Text("status: ${st.status}   backend: ${st.backend}", fontSize = 12.sp)
            if (st.evalStatus.isNotEmpty()) Text(st.evalStatus, fontSize = 12.sp, color = Color(0xFF8A4B00))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Prompt.LANGS.forEach { l ->
                    FilterChip(selected = st.lang == l, onClick = { Spike.ui { it.copy(lang = l) } }, label = { Text(l) })
                }
            }

            Box(
                Modifier.fillMaxWidth().height(80.dp)
                    .background(if (st.recording) Color(0xFFC62828) else MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    .pointerInput(st.busy) {
                        detectTapGestures(onPress = {
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permLauncher.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures
                            }
                            if (st.busy) return@detectTapGestures
                            startRec()
                            tryAwaitRelease()
                            stopRec()
                        })
                    },
                contentAlignment = Alignment.Center,
            ) { Text(if (st.recording) "RECORDING... release to send" else "HOLD TO RECORD (max 15 s)", color = Color.White) }

            OutlinedTextField(typed, { typed = it }, label = { Text("text mode input") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { Spike.doText(applicationContext, typed, st.lang) }, enabled = !st.busy) { Text("Run text mode") }

            Section("Transcript (Gemma)", st.transcript, mono)
            Section("Extracted JSON (Gemma)", st.json, mono)
            Section("Latency", st.latency, mono)
            Section("RuleExtractor on Gemma transcript", st.rule, mono)
        }
    }

    @Composable
    private fun Section(title: String, body: String, font: FontFamily) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(body.ifEmpty { "-" }, fontFamily = font, fontSize = 12.sp)
    }

    private fun startRec() {
        try {
            recorder = Recorder { runOnUiThread { stopRec() } }.also { it.start() }
            Spike.ui { it.copy(recording = true, status = "recording") }
        } catch (t: Throwable) {
            Log.e(TAG, "record start failed", t); Spike.ui { it.copy(status = "record error: ${t.message}") }
        }
    }

    private fun stopRec() {
        val r = recorder ?: return
        recorder = null
        val pcm = r.stop()
        Spike.ui { it.copy(recording = false) }
        if (pcm.size < Wav.SAMPLE_RATE / 2) { Spike.ui { it.copy(status = "too short") }; return }
        val base = Spike.saveRecording(applicationContext, pcm)
        Spike.doAudio(applicationContext, File(base.path + ".wav").readBytes(), Spike.state.value.lang, base)
    }
}
