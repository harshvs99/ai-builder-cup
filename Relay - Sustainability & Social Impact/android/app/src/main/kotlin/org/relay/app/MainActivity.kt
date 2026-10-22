package org.relay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.Intent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.relay.app.ready.LanguageSync
import org.relay.app.ready.ReadyRepository
import org.relay.app.ready.ReadyWorker
import org.relay.core.ready.RefreshTrigger
import org.relay.app.location.MapPackUpdater
import org.relay.app.ui.RelayRoot
import org.relay.app.ui.network.CarryPolicy
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.theme.RelayTheme

class MainActivity : ComponentActivity() {
    private lateinit var speaker: Speaker
    /** Set when the Ready notification was tapped; RelayRoot opens the Ready screen and clears it. */
    private var openReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MeshRepository.init(applicationContext)
        ReadyRepository.init(applicationContext)
        org.relay.app.announce.AnnouncementRepository.init(applicationContext)
        ReadyWorker.schedule(applicationContext)
        openReady = intent?.getBooleanExtra(ReadyRepository.EXTRA_OPEN_READY, false) == true
        speaker = Speaker(this)
        setContent {
            val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
            speaker.speed = settings.voiceSpeed
            RelayTheme(mode = settings.themeMode, largerText = settings.largerText) {
                CompositionLocalProvider(
                    LocalStrings provides stringsFor(settings.effectiveLanguage),
                    LocalSpeaker provides speaker,
                ) { RelayRoot(openReady, onReadyOpened = { openReady = false }) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(ReadyRepository.EXTRA_OPEN_READY, false)) openReady = true
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { ReadyRepository.refresh(applicationContext, RefreshTrigger.APP_OPEN) }
        // Which languages the backend offers; best effort, the cached copy (or the build default) is used offline.
        lifecycleScope.launch(Dispatchers.IO) { LanguageSync.refresh(MeshRepository.settings.current.backendUrl) }
        // Normal times: keep an offline map of the surroundings for the day a disaster hits (never during one).
        MapPackUpdater.maybeUpdate(applicationContext)
    }

    override fun onStop() {
        super.onStop()
        // "Carry messages in the background" off: the mesh only runs while the app is visible. RelayRoot restarts the
        // service on the next resume. (On: the foreground service keeps running after the app is closed.)
        if (CarryPolicy.stopWhenBackgrounded(MeshRepository.settings.current, isChangingConfigurations)) {
            stopService(Intent(this, RelayService::class.java))
        }
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
}
