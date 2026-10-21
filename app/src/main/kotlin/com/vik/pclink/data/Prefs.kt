package com.vik.pclink.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID

class Settings(
    val dav: Boolean,
    val http: Boolean,
    val ftp: Boolean,
    val davPort: Int,
    val httpPort: Int,
    val ftpPort: Int,
    val user: String,
    val password: String,
    val root: String,
    val autostart: Boolean,
) {
    companion object {
        const val DAV_PORT = 1099
        const val HTTP_PORT = 1199
        const val FTP_PORT = 1299
        const val USER = "pclink"
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pclink", Context.MODE_PRIVATE)

    private fun randomPassword(): String {
        val chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        return (1..8).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    fun defaultRoot(): String = Environment.getExternalStorageDirectory().path

    private fun stored(c: Context, key: String, create: () -> String): String {
        val p = sp(c)
        return p.getString(key, null) ?: create().also { p.edit().putString(key, it).apply() }
    }

    fun deviceId(c: Context): String = stored(c, "deviceId") { UUID.randomUUID().toString() }

    fun load(c: Context): Settings {
        val p = sp(c)
        val pw = stored(c, "password", ::randomPassword)
        return Settings(
            dav = p.getBoolean("dav", true),
            http = p.getBoolean("http", true),
            ftp = p.getBoolean("ftp", true),
            davPort = p.getInt("davPort", Settings.DAV_PORT),
            httpPort = p.getInt("httpPort", Settings.HTTP_PORT),
            ftpPort = p.getInt("ftpPort", Settings.FTP_PORT),
            user = p.getString("user", null) ?: Settings.USER,
            password = pw,
            root = p.getString("root", null) ?: defaultRoot(),
            autostart = p.getBoolean("autostart", false),
        )
    }

    fun bindIp(c: Context): String = sp(c).getString("bindIp", "") ?: ""

    fun setBindIp(c: Context, ip: String) {
        sp(c).edit().putString("bindIp", ip).apply()
    }

    fun activeIp(c: Context): String? {
        val all = Net.addresses()
        return all.firstOrNull { it == bindIp(c) } ?: all.firstOrNull()
    }

    fun save(c: Context, s: Settings) {
        sp(c).edit()
            .putBoolean("dav", s.dav)
            .putBoolean("http", s.http)
            .putBoolean("ftp", s.ftp)
            .putInt("davPort", s.davPort)
            .putInt("httpPort", s.httpPort)
            .putInt("ftpPort", s.ftpPort)
            .putString("user", s.user)
            .putString("password", s.password)
            .putString("root", s.root)
            .putBoolean("autostart", s.autostart)
            .apply()
    }
}

object Paths {
    fun treeToPath(uri: Uri): String? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val storage = Environment.getExternalStorageDirectory().path
        if (id.startsWith("raw:")) return id.removePrefix("raw:")
        if (uri.authority == "com.android.providers.downloads.documents") {
            return if (id == "downloads") "$storage/${Environment.DIRECTORY_DOWNLOADS}" else null
        }
        if (uri.authority != "com.android.externalstorage.documents") return null
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = when (volume) {
            "primary" -> storage
            "home" -> "$storage/${Environment.DIRECTORY_DOCUMENTS}"
            else -> "/storage/$volume"
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}

object Net {
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { ni -> ni.inetAddresses.toList().filter { it is Inet4Address && !it.isLoopbackAddress }.mapNotNull { it.hostAddress } }
    }.getOrDefault(emptyList())
}
