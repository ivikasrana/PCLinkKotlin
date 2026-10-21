package com.vik.pclink.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.vik.pclink.R
import java.io.File

object Installer {
    const val ACTION = "com.vik.pclink.INSTALL_RESULT"
    private const val CHANNEL = "install"

    fun install(context: Context, apk: File) {
        val label = context.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName ?: apk.name
        val installer = context.packageManager.packageInstaller
        var id = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallReceiver::class.java).setAction(ACTION).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pending.intentSender)
            }
            id = -1
            ServerState.add("Installing $label")
        } catch (e: Exception) {
            if (id != -1) runCatching { installer.abandonSession(id) }
            ServerState.add("Install failed for $label: ${e.message}")
        } finally {
            apk.delete()
        }
    }

    fun notifyConfirm(context: Context, confirm: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Install requests", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(context, confirm.hashCode(), confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Confirm install")
            .setContentText("Tap to finish installing the app sent from your PC")
            .setContentIntent(tap)
            .setFullScreenIntent(tap, true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        manager.notify(2, n)
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ServerState.add("Waiting for your confirmation on the phone")
                runCatching { context.startActivity(confirm) }
                Installer.notifyConfirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> ServerState.add("Install finished")
            else -> ServerState.add("Install failed: $message")
        }
    }
}
