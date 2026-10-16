package org.relay.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.relay.app.MeshRepository
import org.relay.app.location.LocAccuracy
import org.relay.core.ids.NodeId
import org.relay.core.nlu.RuleExtractor
import org.relay.core.payload.Geo
import org.relay.core.payload.Lang

/**
 * Debug-only automation hooks for test harnesses (the manifest entry lives in src/debug and requires
 * android.permission.DUMP, i.e. adb shell).
 *
 * `org.relay.app.DEBUG_SEND`   extras: text (required), lang (en|hi|or|tl|war|ceb), lat, lon, acc (all strings)
 * `org.relay.app.DEBUG_CONFIG` extras: backend, key, allowlist, offline (string or boolean)
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MeshRepository.init(context)
        when (intent.action) {
            ACTION_SEND -> send(context, intent)
            ACTION_CONFIG -> config(intent)
        }
    }

    private fun send(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text")
        if (text.isNullOrBlank()) { MeshRepository.log("DEBUG_SEND ignored: missing text extra"); return }
        val lang = intent.getStringExtra("lang")?.let { Lang.fromTag(it) }
        val draft = RuleExtractor.extract(text, lang)
        val lat = intent.getStringExtra("lat")?.toDoubleOrNull()
        val lon = intent.getStringExtra("lon")?.toDoubleOrNull()
        val acc = intent.getStringExtra("acc")?.toFloatOrNull()
        val hasFix = lat != null && lon != null
        val need = draft.toNeed(
            msgId = NodeId.random(),
            originNode = MeshRepository.nodeId,
            createdAtSec = System.currentTimeMillis() / 1000,
            latE5 = if (hasFix) Geo.toE5(lat!!) else 0,
            lonE5 = if (hasFix) Geo.toE5(lon!!) else 0,
            locAcc = if (hasFix) LocAccuracy.bucket(acc ?: 5f) else 0,
            lang = lang ?: draft.lang,
        )
        MeshRepository.log("DEBUG_SEND text=\"$text\" -> NEED ${need.msgIdHex} cat=${need.category} urgency=${need.urgency}")
        MeshRepository.submitNeed(need, context)
    }

    private fun config(intent: Intent) {
        val extras = intent.extras
        MeshRepository.settings.update { s ->
            var n = s
            intent.getStringExtra("backend")?.let { n = n.copy(backendUrl = it.trim()) }
            intent.getStringExtra("key")?.let { n = n.copy(deviceKey = it.trim()) }
            // An empty string clears the allowlist.
            intent.getStringExtra("allowlist")?.let { n = n.copy(allowlist = it.trim()) }
            if (extras?.containsKey("offline") == true) {
                // Accept `--es offline true` and `--ez offline true`.
                val str = intent.getStringExtra("offline")?.trim()?.lowercase()
                val on = if (str != null) str == "true" || str == "1" || str == "yes" else extras.getBoolean("offline", false)
                n = n.copy(pretendOffline = on)
            }
            n
        }
        val s = MeshRepository.settings.current
        MeshRepository.log("DEBUG_CONFIG backend=${s.backendUrl} key=${s.deviceKey} allowlist='${s.allowlist}' offline=${s.pretendOffline}")
    }

    companion object {
        const val ACTION_SEND = "org.relay.app.DEBUG_SEND"
        const val ACTION_CONFIG = "org.relay.app.DEBUG_CONFIG"
    }
}
