package org.relay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the mesh alive: owns the [MeshEngine] (gossip node, BLE transport, gateway,
 * timers). The UI talks to it only through [MeshRepository].
 */
class RelayService : Service() {
    private var engine: MeshEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MeshRepository.init(this)
        if (!enterForeground()) {
            stopSelf()
            return
        }
        val e = MeshEngine(this)
        engine = e
        MeshRepository.engine = e
        e.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-delivered start (e.g. after the user granted permissions or turned Bluetooth on): retry the transport.
        engine?.ensureTransport()
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        MeshRepository.engine = null
        MeshRepository.publishStatus(MeshStatus())
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // API 34+: connectedDevice needs a BLUETOOTH_* runtime grant before startForeground; without it we get a SecurityException.
            MeshRepository.log("RelayService: cannot enter foreground (${e.javaClass.simpleName}: ${e.message}); grant Bluetooth permissions and start again")
            false
        }
    }

    private companion object {
        const val CHANNEL_ID = "relay_mesh"
        const val NOTIFICATION_ID = 1
    }
}
