package org.relay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.relay.app.ui.RelayRoot
import org.relay.app.ui.RelayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MeshRepository.init(applicationContext)
        setContent {
            RelayTheme { RelayRoot() }
        }
    }
}
