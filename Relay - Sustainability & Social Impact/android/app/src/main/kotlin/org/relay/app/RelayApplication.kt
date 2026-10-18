package org.relay.app

import android.app.Application
import android.content.ComponentCallbacks2
import org.relay.app.gemma.GemmaRunner

class RelayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MeshRepository.init(this)
    }

    // The on-device model is the biggest thing the app can hold in RAM: free it when the UI is hidden or the system is tight.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val gemma = GemmaRunner.peek() ?: return
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) gemma.onAppBackgrounded()
        else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) gemma.onLowMemory()
    }
}
