package com.leocb.screenqr.util

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.leocb.screenqr.MainActivity
import com.leocb.screenqr.R
import com.leocb.screenqr.capture.ScreenCaptureService
import com.leocb.screenqr.model.QrPayload

object Notifications {

    const val CHANNEL_CAPTURE = "capture"
    const val CHANNEL_RESULTS = "results"

    const val ID_ONGOING = 1001
    const val ID_RESULT = 1002
    const val ID_ERROR = 1003

    const val ACTION_COPY = "com.leocb.screenqr.COPY"
    const val EXTRA_VALUE = "value"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                context.getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.capture_channel_description)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                context.getString(R.string.results_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.results_channel_description)
            }
        )
    }

    /** Required while MediaProjection is capturing; also the session's stop control. */
    fun ongoingCapture(context: Context): Notification {
        val stop = PendingIntent.getService(
            context,
            3,
            Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle(context.getString(R.string.notification_capture_title))
            .setContentText(context.getString(R.string.notification_capture_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, context.getString(R.string.action_stop), stop)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun showResult(context: Context, payload: QrPayload) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val openIntent = payload.openIntent?.let { target ->
            PendingIntent.getActivity(
                context,
                payload.raw.hashCode(),
                Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle(context.getString(payload.headlineRes))
            .setContentText(payload.resolvedValue(context))
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.resolvedValue(context)))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(context))
            .addAction(
                0,
                context.getString(R.string.notification_copy),
                PendingIntent.getBroadcast(
                    context,
                    payload.raw.hashCode() + 1,
                    Intent(context, NotificationActionReceiver::class.java)
                        .setAction(ACTION_COPY)
                        .putExtra(EXTRA_VALUE, payload.raw),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (openIntent != null) {
            builder.addAction(
                0,
                context.getString(payload.openLabelRes ?: R.string.action_open),
                openIntent,
            )
        }

        runCatching { manager.notify(ID_RESULT, builder.build()) }
    }

    @SuppressLint("MissingPermission")
    fun showError(context: Context, message: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle(context.getString(R.string.notification_no_code_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching { manager.notify(ID_ERROR, notification) }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            7,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
