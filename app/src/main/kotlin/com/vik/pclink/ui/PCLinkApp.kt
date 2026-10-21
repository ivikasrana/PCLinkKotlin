package com.vik.pclink.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vik.pclink.data.Net
import com.vik.pclink.data.Paths
import com.vik.pclink.data.Prefs
import com.vik.pclink.data.Settings
import com.vik.pclink.server.ServerService
import com.vik.pclink.server.ServerState
import java.io.File

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
fun PCLinkApp(resumeTick: Int, onDark: (Boolean) -> Unit) {
    val dark = resolveDark()
    LaunchedEffect(dark) { onDark(dark) }
    PCLinkTheme(dark) { Backdrop { Home(resumeTick) } }
}

private fun granted(c: Context, permission: String) =
    ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED

internal fun copyText(c: Context, text: String) =
    c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PCLink", text))

@Composable
private fun Home(resumeTick: Int) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val running by ServerState.running.collectAsState()
    val urls by ServerState.urls.collectAsState()
    val error by ServerState.error.collectAsState()
    val log by ServerState.log.collectAsState()

    val saved = remember { Prefs.load(ctx) }
    var applied by remember { mutableStateOf(saved) }
    var useDav by rememberSaveable { mutableStateOf(saved.dav) }
    var useHttp by rememberSaveable { mutableStateOf(saved.http) }
    var useFtp by rememberSaveable { mutableStateOf(saved.ftp) }
    var davPort by rememberSaveable { mutableStateOf(saved.davPort.toString()) }
    var httpPort by rememberSaveable { mutableStateOf(saved.httpPort.toString()) }
    var ftpPort by rememberSaveable { mutableStateOf(saved.ftpPort.toString()) }
    var user by rememberSaveable { mutableStateOf(saved.user) }
    var password by rememberSaveable { mutableStateOf(saved.password) }
    var root by rememberSaveable { mutableStateOf(saved.root) }
    var autostart by rememberSaveable { mutableStateOf(saved.autostart) }

    val addresses = remember(resumeTick, running) { Net.addresses() }
    var selectedIp by remember(addresses) { mutableStateOf(Prefs.activeIp(ctx).orEmpty()) }

    val dav = davPort.toIntOrNull()
    val http = httpPort.toIntOrNull()
    val ftp = ftpPort.toIntOrNull()
    val active = buildList {
        if (useDav) add(dav)
        if (useHttp) add(http)
    }
    val problem = when {
        !useDav && !useHttp && !useFtp -> "Turn on at least one server"
        useDav && (dav == null || dav !in 1024..65535) -> "WebDAV port must be 1024 to 65535"
        useHttp && (http == null || http !in 1024..65535) -> "HTTP port must be 1024 to 65535"
        useFtp && (ftp == null || ftp !in 1024..65515) -> "FTP port must be 1024 to 65515"
        useDav && useHttp && dav == http -> "WebDAV and HTTP ports must differ"
        useFtp && ftp != null && active.any { it != null && it in ftp..ftp + 20 } -> "Keep other ports out of $ftpPort to ${ftp + 20}, FTP uses them for transfers"
        user.isBlank() -> "Username is required"
        password.length < 4 -> "Password needs at least 4 characters"
        !File(root).isDirectory -> "Shared folder not found"
        else -> ""
    }

    fun current() = Settings(
        useDav, useHttp, useFtp,
        davPort.toIntOrNull() ?: Settings.DAV_PORT, httpPort.toIntOrNull() ?: Settings.HTTP_PORT, ftpPort.toIntOrNull() ?: Settings.FTP_PORT,
        user.trim(), password, root, autostart,
    )

    fun save() {
        user = user.trim()
        applied = current()
        Prefs.save(ctx, applied)
    }

    fun commit() {
        save()
        if (running) ServerService.start(ctx)
    }

    var scanError by rememberSaveable { mutableStateOf("") }

    fun applyScan(raw: String?) {
        val uri = raw?.let { Uri.parse(it) }
        val pass = uri?.getQueryParameter("pass")
        if (uri == null || uri.scheme != "pclink" || pass.isNullOrEmpty()) {
            scanError = "Not a PCLink code"
            return
        }
        fun port(name: String, fallback: Int) = (uri.getQueryParameter(name)?.toIntOrNull() ?: fallback).takeIf { it > 0 }
        val newDav = port("dav", Settings.DAV_PORT)
        val newHttp = port("http", Settings.HTTP_PORT)
        val newFtp = port("ftp", Settings.FTP_PORT)
        scanError = ""
        useDav = newDav != null
        useHttp = newHttp != null
        useFtp = newFtp != null
        newDav?.let { davPort = it.toString() }
        newHttp?.let { httpPort = it.toString() }
        newFtp?.let { ftpPort = it.toString() }
        user = uri.getQueryParameter("user") ?: Settings.USER
        password = pass
        save()
        ServerService.start(ctx)
    }

    var scanning by rememberSaveable { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else scanError = "Camera permission is needed to scan the code"
    }

    fun scan() {
        scanError = ""
        if (granted(ctx, Manifest.permission.CAMERA)) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    if (scanning) {
        ScannerDialog(
            onResult = { scanning = false; applyScan(it) },
            onClose = { scanning = false },
        )
    }

    var pickError by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = Paths.treeToPath(uri)
        pickError = if (path == null) "That folder cannot be shared, choose one on phone storage" else ""
        if (path != null) root = path
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    val storageOk = remember(resumeTick) { Environment.isExternalStorageManager() }
    val installOk = remember(resumeTick) { ctx.packageManager.canRequestPackageInstalls() }
    val batteryOk = remember(resumeTick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val notifyOk = remember(resumeTick) { Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS) }
    val mirrorOk = remember(resumeTick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val lanNeeded = Build.VERSION.SDK_INT >= 37
    val lanOk = remember(resumeTick) { !lanNeeded || granted(ctx, LOCAL_NETWORK) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Masthead("PCLink")

        Tile {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                SectionTitle("Server", Modifier.padding(bottom = 0.dp))
                Pill(if (running) "Running" else "Stopped", if (running) Tone.Green else Tone.Neutral)
            }
            AddressPicker(
                addresses = addresses,
                selected = selectedIp,
                onSelect = {
                    Prefs.setBindIp(ctx, it)
                    selectedIp = it
                    if (running) ServerService.start(ctx)
                },
                onCopy = { copyText(ctx, selectedIp) },
            )
            if (running) {
                urls.forEach { (kind, url) -> Wire(kind, url) }
                Wire("Username", applied.user)
                Wire("Password", applied.password)
            }
            Notice(error, modifier = Modifier.padding(bottom = 10.dp))
            AppButton(
                text = if (running) "Stop" else "Start",
                onClick = {
                    if (running) ServerService.stop(ctx) else {
                        commit()
                        ServerService.start(ctx)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                kind = if (running) ButtonKind.Danger else ButtonKind.Primary,
                enabled = running || problem.isEmpty(),
                large = true,
            )
            AppButton("Scan code from PC", { scan() }, Modifier.fillMaxWidth().padding(top = 10.dp), ButtonKind.Secondary)
            Notice(scanError, modifier = Modifier.padding(top = 10.dp))
        }

        val inbox by ServerState.inbox.collectAsState()
        var draft by rememberSaveable { mutableStateOf("") }
        Tile {
            SectionTitle("Text")
            inbox.take(3).forEach { item ->
                TextArea(item, {}, Modifier.padding(bottom = 6.dp), readOnly = true, minHeight = 56.dp, maxLines = 6)
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                    Chip("Copy", true) { copyText(ctx, item) }
                }
            }
            TextArea(draft, { draft = it }, Modifier.padding(bottom = 10.dp), "Send to PC")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Paste", {
                    draft = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                }, Modifier.weight(1f), ButtonKind.Ghost)
                AppButton("Send", {
                    if (draft.isNotBlank()) {
                        ServerState.pushOutbox(draft)
                        draft = ""
                    }
                }, Modifier.weight(1f), enabled = running && draft.isNotBlank())
            }
        }

        Tile {
            SectionTitle("Settings")
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("WebDAV", useDav) { useDav = !useDav }
                Chip("HTTP", useHttp) { useHttp = !useHttp }
                Chip("FTP", useFtp) { useFtp = !useFtp }
            }
            if (useDav) Field(davPort, { davPort = it.filter(Char::isDigit) }, Modifier.padding(bottom = 12.dp), "WebDAV port", KeyboardType.Number)
            if (useHttp) Field(httpPort, { httpPort = it.filter(Char::isDigit) }, Modifier.padding(bottom = 12.dp), "HTTP port", KeyboardType.Number)
            if (useFtp) Field(ftpPort, { ftpPort = it.filter(Char::isDigit) }, Modifier.padding(bottom = 12.dp), "FTP port", KeyboardType.Number)
            Field(user, { user = it }, Modifier.padding(bottom = 12.dp), "Username")
            Field(password, { password = it }, Modifier.padding(bottom = 12.dp), "Password")
            Wire("Shared folder", root)
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Phone storage", { root = Prefs.defaultRoot() }, Modifier.weight(1f), ButtonKind.Ghost)
                AppButton("Choose folder", { picker.launch(null) }, Modifier.weight(1f), ButtonKind.Secondary)
            }
            Notice(pickError, modifier = Modifier.padding(bottom = 12.dp))
            Text("Start on boot", color = t.secondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
            Segmented(listOf("off" to "Off", "on" to "On"), if (autostart) "on" else "off", { autostart = it == "on" }, Modifier.padding(bottom = 12.dp))
            Notice(problem, modifier = Modifier.padding(bottom = 10.dp))
            AppButton("Save", { commit() }, Modifier.fillMaxWidth(), enabled = problem.isEmpty())
        }

        Tile {
            SectionTitle("Permissions")
            fun openForApp(action: String) = ctx.startActivity(Intent(action, Uri.parse("package:${ctx.packageName}")))
            PermRow("All files access", storageOk) { openForApp(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) }
            PermRow("Install apps", installOk) { openForApp(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) }
            PermRow("Unrestricted battery", batteryOk) { openForApp(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) }
            PermRow("Notifications", notifyOk) { runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
            PermRow("Notification access", mirrorOk) { ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            if (lanNeeded) PermRow("Local network", lanOk) { runtime.launch(arrayOf(LOCAL_NETWORK)) }
        }

        Tile {
            SectionTitle("Activity")
            if (log.isEmpty()) Text("Nothing yet", color = t.secondary, fontSize = 15.sp)
            log.forEach { Text(it, color = t.secondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp)) }
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, onGrant: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = t.label, fontSize = 16.sp)
        if (ok) Pill("On", Tone.Green) else Chip("Allow", true, onGrant)
    }
}
