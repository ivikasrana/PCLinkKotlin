package com.vik.pclink.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vik.pclink.R
import com.vik.pclink.data.Prefs
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

class ServerService : Service() {
    private var stoppers = emptyList<() -> Unit>()
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wifiPerf: WifiManager.WifiLock? = null
    @Volatile private var boundIp: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recheck = Runnable { followAddress() }
    private val networkIps = ConcurrentHashMap<Network, List<String>>()
    @Volatile private var boundLost = false
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            networkIps[network] = properties.linkAddresses.mapNotNull { a -> a.address.takeIf { it is Inet4Address }?.hostAddress }
            schedule()
        }
        override fun onLost(network: Network) {
            val ips = networkIps.remove(network).orEmpty()
            if (boundIp != null && boundIp in ips) boundLost = true
            schedule()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopServers()
            quit()
            return START_NOT_STICKY
        }
        enterForeground("Starting")
        startServers()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching { getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, network) }
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        handler.removeCallbacks(recheck)
        stopServers()
        super.onDestroy()
    }

    private fun schedule() {
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, ADDRESS_SETTLE_MS)
    }

    private fun followAddress() {
        val ip = Prefs.activeIp(this) ?: return
        if (stoppers.isEmpty() || (ip == boundIp && !boundLost)) return
        ServerState.add(if (ip == boundIp) "Network reconnected on $ip" else "Address changed to $ip")
        startServers()
    }

    private fun enterForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "File sharing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ServerService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("PCLink")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun startServers() {
        stopServers()
        ServerState.error.value = ""
        val s = Prefs.load(this)
        val started = mutableListOf<() -> Unit>()
        val urls = mutableListOf<Pair<String, String>>()
        val ip = Prefs.activeIp(this)
        if (ip == null) {
            ServerState.error.value = "No network address found. Connect to Wi-Fi first."
            quit()
            return
        }
        val onApk = { file: File -> Installer.install(applicationContext, file) }
        val info = {
            JSONObject()
                .put("app", "PCLink")
                .put("id", Prefs.deviceId(applicationContext))
                .put("name", Build.MODEL)
                .put("dav", if (s.dav) s.davPort else 0)
                .put("http", if (s.http) s.httpPort else 0)
                .put("ftp", if (s.ftp) s.ftpPort else 0)
                .put("user", s.user)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("root", s.root)
        }
        val api = ApiRoutes(applicationContext, File(s.root).canonicalFile, info, ServerState::add)
        fun open(kind: String, url: String, port: Int, start: () -> Unit, stop: () -> Unit) {
            try {
                start()
                started += stop
                urls += kind to url
            } catch (e: Exception) {
                ServerState.error.value = "Could not open port $port: ${e.message}"
            }
        }
        fun openHttp(webdav: Boolean, port: Int) {
            val server = ShareServer(applicationContext, ip, port, webdav, s.root, s.user, s.password, onApk, ServerState::add, api)
            open(if (webdav) "WebDAV" else "HTTP", "http://$ip:$port/", port, { server.start(30000, false) }, server::stop)
        }
        if (s.dav) openHttp(true, s.davPort)
        if (s.http) openHttp(false, s.httpPort)
        if (s.ftp) {
            val server = FtpServer(ip, s.ftpPort, s.root, s.user, s.password, cacheDir, onApk, ServerState::add)
            open("FTP", "ftp://$ip:${s.ftpPort}/", s.ftpPort, server::start, server::stop)
        }
        if (started.isEmpty()) {
            quit()
            return
        }
        val discovery = Discovery { info().toString() }
        discovery.start()
        started += discovery::stop
        stoppers = started
        boundIp = ip
        acquireLocks()
        ServerState.urls.value = urls
        ServerState.running.value = true
        ServerState.add("Sharing ${s.root}")
        enterForeground(urls.first().second)
    }

    private fun quit() {
        ServerState.running.value = false
        ServerState.urls.value = emptyList()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopServers() {
        stoppers.forEach { it() }
        stoppers = emptyList()
        boundIp = null
        boundLost = false
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        wifiPerf?.takeIf { it.isHeld }?.release()
        wake = null
        wifi = null
        wifiPerf = null
        if (ServerState.running.value) ServerState.add("Stopped")
        ServerState.running.value = false
        ServerState.urls.value = emptyList()
    }

    private fun acquireLocks() {
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pclink:server").apply { acquire() }
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "pclink:wifi").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiPerf = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pclink:wifiperf").apply { acquire() }
    }

    companion object {
        const val STOP = "com.vik.pclink.STOP"
        private const val CHANNEL = "server"
        private const val ADDRESS_SETTLE_MS = 2000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ServerService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(STOP))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.load(context).autostart) ServerService.start(context)
    }
}
