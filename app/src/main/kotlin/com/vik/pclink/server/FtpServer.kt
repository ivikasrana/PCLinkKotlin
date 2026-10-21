package com.vik.pclink.server

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.Channels
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class FtpServer(
    private val host: String,
    private val port: Int,
    rootPath: String,
    private val user: String,
    private val password: String,
    private val cacheDir: File,
    private val onApk: (File) -> Unit,
    private val log: (String) -> Unit,
) {
    private val root = File(rootPath).canonicalFile
    private val pool = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    private var listener: ServerSocket? = null

    fun start() {
        val server = ServerSocket(port, 50, InetAddress.getByName(host))
        listener = server
        pool.execute {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: IOException) { break }
                val session = Session(client)
                sessions += session
                try {
                    pool.execute(session)
                } catch (e: Exception) {
                    session.close()
                }
            }
        }
    }

    fun stop() {
        runCatching { listener?.close() }
        sessions.forEach { it.close() }
        pool.shutdownNow()
    }

    private inner class Session(private val control: Socket) : Runnable {
        private lateinit var input: BufferedReader
        private lateinit var output: OutputStream
        private var name = ""
        private var authed = false
        private var cwd = "/"
        @Volatile private var passive: ServerSocket? = null
        private var renameFrom: File? = null
        private var rest = 0L

        private fun say(line: String) {
            output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        override fun run() {
            try {
                input = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.UTF_8))
                output = control.getOutputStream()
                control.soTimeout = 300000
                say("220 PCLink ready")
                while (true) {
                    val line = input.readLine() ?: break
                    val cmd = line.substringBefore(' ').uppercase()
                    val arg = line.substringAfter(' ', "").trim()
                    if (!handle(cmd, arg)) break
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }

        fun close() {
            sessions -= this
            closePassive()
            runCatching { control.close() }
        }

        private fun closePassive() {
            runCatching { passive?.close() }
            passive = null
        }

        private fun virtual(arg: String): String {
            val parts = ArrayDeque<String>()
            val base = if (arg.startsWith("/")) arg else "$cwd/$arg"
            base.split('/').forEach {
                when (it) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(it)
                }
            }
            return "/" + parts.joinToString("/")
        }

        private fun file(v: String): File? = root.child(v)

        private fun openPassive(): ServerSocket? {
            for (p in port + 1..port + 20) {
                try { return ServerSocket(p, 1, control.localAddress) } catch (_: IOException) { }
            }
            return null
        }

        private fun openData(): Socket? {
            val server = passive ?: return null
            return try {
                server.soTimeout = 15000
                val deadline = System.currentTimeMillis() + 15000
                var data = server.accept()
                while (data.inetAddress != control.inetAddress) {
                    runCatching { data.close() }
                    server.soTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt()
                    data = server.accept()
                }
                data.apply { soTimeout = 60000 }
            } catch (e: Exception) {
                null
            } finally {
                closePassive()
            }
        }

        private fun listLine(f: File): String {
            val old = System.currentTimeMillis() - f.lastModified() > 180L * 86400000
            val date = SimpleDateFormat(if (old) "MMM dd  yyyy" else "MMM dd HH:mm", Locale.US).format(Date(f.lastModified()))
            val kind = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
            val size = if (f.isDirectory) 0 else f.length()
            return "$kind 1 pclink pclink ${size.toString().padStart(12)} $date ${f.name}"
        }

        private fun handle(cmd: String, arg: String): Boolean {
            if (!authed && cmd !in setOf("USER", "PASS", "QUIT", "FEAT", "OPTS", "SYST", "AUTH", "NOOP")) {
                say("530 Not logged in")
                return true
            }
            when (cmd) {
                "USER" -> {
                    name = arg
                    say("331 Password required")
                }
                "PASS" -> {
                    val ok = MessageDigest.isEqual(name.toByteArray(), user.toByteArray()) && MessageDigest.isEqual(arg.toByteArray(), password.toByteArray())
                    authed = ok
                    say(if (ok) "230 Logged in" else "530 Login incorrect")
                }
                "QUIT" -> {
                    say("221 Bye")
                    return false
                }
                "SYST" -> say("215 UNIX Type: L8")
                "FEAT" -> say("211-Features:\r\n UTF8\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n211 End")
                "OPTS" -> say("200 OK")
                "NOOP", "TYPE", "MODE", "STRU" -> say("200 OK")
                "PWD", "XPWD" -> say("257 \"$cwd\" is the current directory")
                "CWD", "XCWD", "CDUP", "XCUP" -> {
                    val v = if (cmd.endsWith("UP")) virtual("..") else virtual(arg)
                    if (v == "/_install" || file(v)?.isDirectory == true) {
                        cwd = v
                        say("250 OK")
                    } else say("550 No such directory")
                }
                "PASV", "EPSV" -> {
                    closePassive()
                    val server = openPassive()
                    val ip = control.localAddress.address
                    if (server == null) say("425 No passive port available")
                    else {
                        passive = server
                        val p = server.localPort
                        if (cmd == "EPSV") say("229 Entering Extended Passive Mode (|||$p|)")
                        else if (ip.size != 4) {
                            closePassive()
                            say("522 Use EPSV")
                        }
                        else say("227 Entering Passive Mode (${ip.joinToString(",") { (it.toInt() and 255).toString() }},${p shr 8},${p and 255})")
                    }
                }
                "REST" -> {
                    rest = arg.toLongOrNull() ?: 0
                    say("350 Restarting at $rest")
                }
                "LIST", "NLST" -> list(cmd == "NLST", arg)
                "RETR" -> retrieve(arg)
                "STOR", "APPE" -> store(cmd == "APPE", arg)
                "DELE" -> {
                    val v = virtual(arg)
                    val f = file(v)
                    if (v.startsWith("/_install/")) say("250 OK")
                    else if (f != null && f.isFile && f.delete()) {
                        log("Deleted ${f.name}")
                        say("250 Deleted")
                    } else say("550 Cannot delete")
                }
                "MKD", "XMKD" -> {
                    val v = virtual(arg)
                    val f = file(v)
                    if (f != null && !f.exists() && f.parentFile?.isDirectory == true && f.mkdir()) say("257 \"$v\" created")
                    else say("550 Cannot create directory")
                }
                "RMD", "XRMD" -> {
                    val f = file(virtual(arg))
                    if (f != null && f != root && f.isDirectory && f.delete()) say("250 Removed") else say("550 Cannot remove directory")
                }
                "RNFR" -> {
                    val f = file(virtual(arg))
                    if (f != null && f != root && f.exists()) {
                        renameFrom = f
                        say("350 Ready for RNTO")
                    } else say("550 Not found")
                }
                "RNTO" -> {
                    val from = renameFrom
                    val to = file(virtual(arg))
                    renameFrom = null
                    if (from == null) say("503 RNFR first")
                    else if (to == null || to.exists() || to.parentFile?.isDirectory != true) say("553 Cannot rename")
                    else {
                        val ok = transferFile(from, to, move = true)
                        if (ok) log("Renamed ${from.name}")
                        say(if (ok) "250 Renamed" else "553 Cannot rename")
                    }
                }
                "SIZE" -> {
                    val f = file(virtual(arg))
                    if (f != null && f.isFile) say("213 ${f.length()}") else say("550 Not a file")
                }
                "MDTM" -> {
                    val f = file(virtual(arg))
                    if (f != null && f.exists()) {
                        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(f.lastModified()))
                        say("213 $stamp")
                    } else say("550 Not found")
                }
                else -> say("502 Not implemented")
            }
            return true
        }

        private fun list(namesOnly: Boolean, arg: String) {
            val target = arg.split(' ').filterNot { it.startsWith("-") }.joinToString(" ")
            val v = if (target.isEmpty()) cwd else virtual(target)
            val f = file(v)
            val lines = mutableListOf<String>()
            if (v != "/_install") {
                if (f == null || !f.exists()) {
                    say("550 Not found")
                    return
                }
                if (f.isDirectory) {
                    (f.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }.forEach { lines += if (namesOnly) it.name else listLine(it) }
                    if (v == "/") lines += if (namesOnly) "_install" else "drwxr-xr-x 1 pclink pclink ${"0".padStart(12)} Jan 01  2024 _install"
                } else lines += if (namesOnly) f.name else listLine(f)
            }
            withData("226 Done") { data ->
                data.getOutputStream().use { out -> lines.forEach { out.write((it + "\r\n").toByteArray(Charsets.UTF_8)) } }
            }
        }

        private fun withData(success: String, block: (Socket) -> Unit): Boolean {
            try {
                if (passive == null) {
                    say("425 Use PASV first")
                    return false
                }
                say("150 Opening data connection")
                val data = openData()
                if (data == null) {
                    say("425 Cannot open data connection")
                    return false
                }
                try {
                    data.use(block)
                } catch (e: Exception) {
                    say("426 Transfer aborted")
                    return false
                }
                say(success)
                return true
            } finally {
                rest = 0
            }
        }

        private fun retrieve(arg: String) {
            val f = file(virtual(arg))
            if (f == null || !f.isFile) {
                say("550 Not a file")
                return
            }
            val offset = rest
            withData("226 Transfer complete") { data ->
                FileInputStream(f).use { stream ->
                    if (offset > 0) stream.channel.position(offset)
                    data.getOutputStream().use { stream.copyTo(it, 65536) }
                }
            }
        }

        private fun store(append: Boolean, arg: String) {
            val v = virtual(arg)
            val install = v.startsWith("/_install/")
            val target: File
            if (install) {
                if (!v.endsWith(".apk", ignoreCase = true)) {
                    say("553 Only .apk files")
                    return
                }
                target = incomingApk(cacheDir)
            } else {
                val f = file(v)
                if (f == null || f == root || f.isDirectory || f.parentFile?.isDirectory != true) {
                    say("550 Cannot write there")
                    return
                }
                target = f
            }
            val offset = if (install) 0L else rest
            val ok = withData("226 Transfer complete") { data ->
                val sink: OutputStream = if (offset > 0) {
                    val raf = RandomAccessFile(target, "rw")
                    raf.setLength(offset)
                    raf.seek(offset)
                    Channels.newOutputStream(raf.channel)
                } else FileOutputStream(target, append && !install)
                sink.use { out -> data.getInputStream().copyTo(out, 65536) }
            }
            when {
                install && ok -> {
                    log("Received ${v.substringAfterLast('/')}")
                    Thread { onApk(target) }.start()
                }
                install -> target.delete()
                ok -> log("Uploaded ${target.name}")
            }
        }
    }
}
