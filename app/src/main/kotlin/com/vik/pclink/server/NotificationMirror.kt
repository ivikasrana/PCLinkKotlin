package com.vik.pclink.server

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationMirror : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!ServerState.running.value || sbn.packageName == packageName) return
        val n = sbn.notification
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        ServerState.addNote(Note(System.currentTimeMillis(), app, title, text))
    }
}
