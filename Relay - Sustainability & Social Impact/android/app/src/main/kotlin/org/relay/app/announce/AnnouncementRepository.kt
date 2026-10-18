package org.relay.app.announce

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.relay.app.MeshRepository
import org.relay.app.R
import org.relay.app.ready.ReadyRepository
import org.relay.app.ui.i18n.stringsFor
import org.relay.core.announce.AnnouncementBoard
import org.relay.core.announce.AnnouncementCard
import org.relay.core.announce.AnnouncementStore
import org.relay.core.announce.HeldFrame
import org.relay.core.announce.HttpAnnouncement
import org.relay.core.ready.HttpReadyApi
import org.relay.core.ready.RefreshTrigger
import org.relay.core.ready.ReadyPolicy
import java.io.File

/**
 * "Message from the control room" (contract v2 section 5.2): the announcements this phone may show, from the mesh frames it holds
 * and the backend's list when it has signal. [cards] is computed from storage with the board's rules; nothing here translates.
 */
object AnnouncementRepository {
    private lateinit var store: AnnouncementStore
    private val _held = MutableStateFlow<List<HeldFrame>>(emptyList())
    private val _http = MutableStateFlow<List<HttpAnnouncement>>(emptyList())
    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())
    val held: StateFlow<List<HeldFrame>> get() = _held
    val http: StateFlow<List<HttpAnnouncement>> get() = _http
    val dismissed: StateFlow<Set<String>> get() = _dismissed

    @Synchronized fun init(ctx: Context) {
        if (::store.isInitialized) return
        store = AnnouncementStore(File(ctx.applicationContext.filesDir, "ready"))
        _http.value = store.http()
        _dismissed.value = store.dismissed()
        reloadHeld()
    }

    /** Re-reads the announcement frames from the gossip store (called when one arrives or expires). */
    fun reloadHeld() { _held.value = AnnouncementBoard.heldFrom(MeshRepository.packetStore) }

    fun dismiss(id: String) { store.dismiss(id); _dismissed.value = store.dismissed() }

    /** The cards to show now, for the phone's district and language. */
    fun cards(nowMs: Long): List<AnnouncementCard> {
        val s = MeshRepository.settings.current
        return AnnouncementBoard.visible(_held.value, _http.value, ReadyRepository.snapshot.value.district, s.effectiveLanguage, nowMs, _dismissed.value)
    }

    /** `GET /v1/announcements/active` for the phone's district (best effort, blocking). The Ready refresh calls it. */
    fun fetch(ctx: Context, trigger: RefreshTrigger, online: Boolean) {
        init(ctx)
        val district = ReadyRepository.snapshot.value.district ?: return
        val now = System.currentTimeMillis()
        if (!ReadyPolicy.shouldFetchPack(trigger, online, store.lastHttpFetchMs(), now)) return
        val s = MeshRepository.settings.current
        try {
            val body = HttpReadyApi(s.backendUrl, s.deviceKey).activeAnnouncements(district) ?: return
            if (org.relay.core.announce.AnnouncementJson.parseActive(body, now) == null) return
            store.saveHttp(body, now)
            _http.value = store.http()
        } catch (e: Exception) {
            MeshRepository.log("announcements: ${e.message}")
        }
    }

    /** One notification per announcement, the first time it can be shown (contract section 5.2). */
    fun notifyNew(ctx: Context) {
        init(ctx)
        val fresh = AnnouncementBoard.toNotify(cards(System.currentTimeMillis()), store.notified())
        for (c in fresh) if (post(ctx.applicationContext, c)) store.markNotified(c.announcementId)
    }

    private fun post(ctx: Context, c: AnnouncementCard): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val lang = MeshRepository.settings.current.effectiveLanguage
        val s = stringsFor(lang).ready
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("announcements", s.announcementChannel, NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(
            ctx, 42, android.content.Intent(ctx, org.relay.app.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, "announcements").setSmallIcon(R.drawable.ic_notification).setContentTitle(s.announcementTitle)
            .setContentText(c.text).setStyle(NotificationCompat.BigTextStyle().bigText(c.text)).setContentIntent(tap).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        return try { NotificationManagerCompat.from(ctx).notify(4200 + (c.announcementId.hashCode() and 0xFF), n); true } catch (e: SecurityException) { false }
    }
}
