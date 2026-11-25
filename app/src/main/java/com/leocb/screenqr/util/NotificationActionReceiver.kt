package com.leocb.screenqr.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/**
 * Handles the "Copy" action on result notifications, for scans that finished while the app
 * was in the background and therefore could not show an activity.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifications.ACTION_COPY) return
        val value = intent.getStringExtra(Notifications.EXTRA_VALUE) ?: return
        Clipboard.copy(context, value)
        NotificationManagerCompat.from(context).cancel(Notifications.ID_RESULT)
    }
}
