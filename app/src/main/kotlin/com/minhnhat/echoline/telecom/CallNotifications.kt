package com.minhnhat.echoline.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Build
import com.minhnhat.echoline.R
import com.minhnhat.echoline.data.systemService
import com.minhnhat.echoline.diag.Diag
import com.minhnhat.echoline.ui.incall.InCallActivity

/** Thông báo cuộc gọi đến (full-screen trên màn hình khóa) và cuộc gọi đang diễn ra. */
class CallNotifications(private val context: Context) {
    private val nm: NotificationManager = context.systemService()

    @Volatile private var channelsReady = false

    /** Tạo kênh thông báo (một lần). Gọi được từ nhiều luồng; cũng được gọi lười trước lần đăng thông báo đầu tiên. */
    @Synchronized
    fun ensureChannels() {
        if (channelsReady) return
        val incoming = NotificationChannel(
            CH_INCOMING, context.getString(R.string.channel_incoming), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            // Hệ thống tự đổ chuông, thông báo này không phát tiếng.
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val ongoing = NotificationChannel(
            CH_ONGOING, context.getString(R.string.channel_ongoing), NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannels(listOf(incoming, ongoing))
        channelsReady = true
    }

    private fun title(ui: CallUi): String = ui.name ?: ui.number ?: context.getString(R.string.hidden_number)

    private fun subtitle(ui: CallUi, resId: Int): String {
        val base = context.getString(resId)
        return if (ui.simLabel != null) "$base · ${ui.simLabel}" else base
    }

    private fun openUi(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(ui: CallUi, action: String, k: Int): PendingIntent = PendingIntent.getBroadcast(
        context, ui.id * 8 + k,
        Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(CallActionReceiver.EXTRA_ID, ui.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun person(ui: CallUi): Person = Person.Builder().setName(title(ui)).build()

    @Suppress("DEPRECATION")
    fun postIncoming(ui: CallUi) {
        val builder = Notification.Builder(context, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setColor(context.getColor(R.color.brand_brown))
            .setContentTitle(title(ui))
            .setContentText(subtitle(ui, R.string.incoming_call))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openUi())
            .setFullScreenIntent(openUi(), true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                Notification.CallStyle.forIncomingCall(
                    person(ui), action(ui, CallActionReceiver.ACTION_DECLINE, 0), action(ui, CallActionReceiver.ACTION_ANSWER, 1)
                )
            )
        } else {
            builder.addAction(0, context.getString(R.string.decline), action(ui, CallActionReceiver.ACTION_DECLINE, 0))
            builder.addAction(0, context.getString(R.string.answer), action(ui, CallActionReceiver.ACTION_ANSWER, 1))
        }
        post(ui.id, builder.build())
    }

    @Suppress("DEPRECATION")
    fun postOngoing(ui: CallUi) {
        val builder = Notification.Builder(context, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setColor(context.getColor(R.color.brand_brown))
            .setContentTitle(title(ui))
            .setContentText(subtitle(ui, R.string.ongoing_call))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openUi())
        if (ui.connectTimeMs > 0L) {
            builder.setShowWhen(true).setWhen(ui.connectTimeMs).setUsesChronometer(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(Notification.CallStyle.forOngoingCall(person(ui), action(ui, CallActionReceiver.ACTION_HANGUP, 2)))
        } else {
            builder.addAction(0, context.getString(R.string.hang_up), action(ui, CallActionReceiver.ACTION_HANGUP, 2))
        }
        post(ui.id, builder.build())
    }

    fun cancel(id: Int) {
        try {
            nm.cancel(NOTIF_BASE + id)
        } catch (e: Exception) {
            Diag.w(TAG, "Không hủy được thông báo: ${e.message}")
        }
    }

    private fun post(id: Int, notification: Notification) {
        try {
            ensureChannels()
            nm.notify(NOTIF_BASE + id, notification)
        } catch (e: Exception) {
            Diag.w(TAG, "Không đăng được thông báo cuộc gọi: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "notify"
        const val CH_INCOMING = "incoming_calls"
        const val CH_ONGOING = "ongoing_calls"
        const val NOTIF_BASE = 7000
    }
}
