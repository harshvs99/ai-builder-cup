package org.relay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.Intent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relay.app.location.MapPackUpdater
import org.relay.app.ui.RelayRoot
import org.relay.app.ui.network.CarryPolicy
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.theme.RelayTheme

class MainActivity : ComponentActivity() {
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MeshRepository.init(applicationContext)
        speaker = Speaker(this)
        setContent {
            val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
            speaker.speed = settings.voiceSpeed
            RelayTheme(mode = settings.themeMode, largerText = settings.largerText) {
                CompositionLocalProvider(
                    LocalStrings provides stringsFor(settings.language),
                    LocalSpeaker provides speaker,
                ) { RelayRoot() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
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
