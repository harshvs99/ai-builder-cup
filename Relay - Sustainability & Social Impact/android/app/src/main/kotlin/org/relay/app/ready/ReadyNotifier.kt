package org.relay.app.ready

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import org.relay.app.R
import org.relay.app.ui.i18n.stringsFor
import org.relay.core.payload.Lang
import org.relay.core.ready.ReadyWarning

/** The one local notification of Ready mode: "Cyclone warning for {district}. Your plan is ready." Tapping opens the Ready screen. */
object ReadyNotifier {
    private const val CHANNEL = "ready"

    /** Returns true when the notification was posted (so the caller records the warning as told). */
    fun post(ctx: Context, warning: ReadyWarning, districtId: String, lang: Lang): Boolean {
        val s = stringsFor(lang).ready
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, s.notificationChannel, NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(
            ctx, 41, ReadyRepository.openReadyIntent(ctx), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.notificationTitle(s.districtName(districtId)))
            .setContentText(s.notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(warning.headline + "\n" + s.notificationText))
            .setContentIntent(tap).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        return try { NotificationManagerCompat.from(ctx).notify(4100 + (warning.id % 1000).toInt(), n); true } catch (e: SecurityException) { false }
    }
}
