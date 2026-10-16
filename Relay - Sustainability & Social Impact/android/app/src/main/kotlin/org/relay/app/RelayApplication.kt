package org.relay.app

import android.app.Application

class RelayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MeshRepository.init(this)
    }
}
