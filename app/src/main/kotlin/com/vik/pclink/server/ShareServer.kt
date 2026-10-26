package com.vik.pclink.server

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.IStatus
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import org.json.JSONArray
import org.json.JSONObject
import com.vik.pclink.server.RequestBody.Companion.MAX_TEXT
import com.vik.pclink.server.RequestBody.Companion.STORAGE_BUFFER
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal class Code(private val code: Int, private val text: String) : IStatus {
    override fun getRequestStatus() = code
    override fun getDescription() = "$code $text"
}

class ShareServer(
    private val context: Context,
    host: String?,
    port: Int,
    private val webdav: Boolean,
    rootPath: String,
    private val user: String,
    private val password: String,
    private val onApk: (File) -> Unit,
    private val log: (String) -> Unit,
    private val api: ApiRoutes,
) : NanoHTTPD(host, port) {
    private val root = File(rootPath).canonicalFile
    private val page by lazy { context.assets.open("index.html").bufferedReader().use { it.readText() } }

    private fun reply(code: Int, text: String, body: String = "", mime: String = "text/plain; charset=utf-8"): Response =
        newFixedLengthResponse(Code(code, text), mime, body)

    private fun xml(body: String, code: Int = 207, text: String = "Multi-Status"): Response =
        reply(code, text, "<?xml version=\"1.0\" encoding=\"utf-8\"?>$body", "application/xml; charset=utf-8")

    private fun authorized(s: IHTTPSession): Boolean {
        val header = s.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching { String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return false
        val i = decoded.indexOf(':')
        if (i < 0) return false
        val userOk = MessageDigest.isEqual(decoded.substring(0, i).toByteArray(), user.toByteArray())
        val passOk = MessageDigest.isEqual(decoded.substring(i + 1).toByteArray(), password.toByteArray())
        return userOk && passOk
    }

    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler {
        runCatching { finalAccept.tcpNoDelay = true }
        return super.createClientHandler(finalAccept, inputStream)
    }

    override fun serve(session: IHTTPSession): Response {
        if (!authorized(session)) {
            return reply(401, "Unauthorized", "Unauthorized").apply {
                addHeader("WWW-Authenticate", "Basic realm=\"PCLink\"")
                addHeader("Connection", "close")
            }
        }
        val body = RequestBody(session)
        val response = try {
            route(session, session.method, body)
        } catch (e: Exception) {
            reply(500, "Internal Server Error", e.message ?: "error").apply { closeConnection(true) }
        }
        if (!body.discard(MAX_TEXT)) response.closeConnection(true)
        return response
    }

    private fun resolve(path: String): File? = root.child(path)

    private fun route(s: IHTTPSession, method: Method, body: RequestBody): Response {
        val path = s.uri
        if (method == Method.OPTIONS) return options()
        if (path.startsWith("/api/")) return api.handle(s, method, path, body)
        if (webdav) {
            when (method) {
                Method.LOCK -> return lock()
                Method.UNLOCK -> return reply(204, "No Content")
                Method.PROPPATCH -> return proppatch(path)
                else -> Unit
            }
        }
        if (path == "/_install" || path.startsWith("/_install/")) return installRoute(method, path, body)
        val target = resolve(path) ?: return reply(403, "Forbidden")
        return when (method) {
            Method.GET, Method.HEAD -> get(s, target)
            Method.PUT -> put(target, body)
            Method.DELETE -> delete(target)
            Method.MKCOL -> mkcol(target)
            Method.PROPFIND -> if (webdav) propfind(s, target, path) else reply(405, "Method Not Allowed")
            Method.MOVE -> if (webdav) transfer(s, target, move = true) else reply(405, "Method Not Allowed")
            Method.COPY -> if (webdav) transfer(s, target, move = false) else reply(405, "Method Not Allowed")
            else -> reply(405, "Method Not Allowed")
        }
    }

    private fun options(): Response = reply(200, "OK").apply {
        addHeader("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, PROPPATCH, MOVE, COPY, LOCK, UNLOCK")
        if (webdav) {
            addHeader("DAV", "1, 2")
            addHeader("MS-Author-Via", "DAV")
        }
    }

    private fun installRoute(method: Method, path: String, body: RequestBody): Response {
        val name = path.substringAfterLast('/')
        return when (method) {
            Method.PROPFIND -> if (!webdav) reply(405, "Method Not Allowed") else {
                val sb = StringBuilder("<D:multistatus xmlns:D=\"DAV:\">")
                if (name.isEmpty() || path == "/_install") {
                    sb.append(collection("/_install/", "_install"))
                } else {
                    sb.append(item(href(path, false), name, 0, System.currentTimeMillis(), false))
                }
                sb.append("</D:multistatus>")
                xml(sb.toString())
            }
            Method.PUT -> {
                if (name.isEmpty() || !name.endsWith(".apk", ignoreCase = true)) return reply(415, "Unsupported Media Type", "Only .apk files")
                val dest = incomingApk(context.cacheDir)
                try {
                    body.saveTo(dest)
                } catch (e: Exception) {
                    dest.delete()
                    throw e
                }
                log("Received $name")
                Thread { onApk(dest) }.start()
                reply(201, "Created")
            }
            Method.GET, Method.HEAD -> reply(200, "OK", "Drop .apk files here")
            Method.DELETE -> reply(204, "No Content")
            else -> reply(405, "Method Not Allowed")
        }
    }

    private fun get(s: IHTTPSession, f: File): Response {
        if (!f.exists()) return reply(404, "Not Found")
        if (f.isDirectory) {
            if (webdav) return reply(200, "OK", "PCLink WebDAV")
            return if (s.parameters.containsKey("list")) listing(f) else reply(200, "OK", page, "text/html; charset=utf-8")
        }
        return serveFile(s, f)
    }

    private fun listing(dir: File): Response {
        val items = JSONArray()
        (dir.listFiles() ?: emptyArray())
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach {
                items.put(JSONObject().put("name", it.name).put("dir", it.isDirectory).put("size", if (it.isDirectory) 0 else it.length()).put("mtime", it.lastModified()))
            }
        return reply(200, "OK", JSONObject().put("items", items).toString(), "application/json; charset=utf-8")
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun serveFile(s: IHTTPSession, f: File): Response {
        val len = f.length()
        val mime = mimeOf(f.name)
        val range = s.headers["range"]
        if (range != null && range.startsWith("bytes=") && len > 0) {
            val spec = range.substring(6).substringBefore(',')
            val a = spec.substringBefore('-')
            val b = spec.substringAfter('-')
            val start: Long
            val end: Long
            if (a.isEmpty()) {
                start = (len - (b.toLongOrNull() ?: 0)).coerceAtLeast(0)
                end = len - 1
            } else {
                start = a.toLongOrNull() ?: 0
                end = (b.toLongOrNull() ?: (len - 1)).coerceAtMost(len - 1)
            }
            if (start >= len || start > end) return reply(416, "Range Not Satisfiable").apply { addHeader("Content-Range", "bytes */$len") }
            val stream = FileInputStream(f).also { it.channel.position(start) }.buffered(STORAGE_BUFFER)
            return newFixedLengthResponse(Code(206, "Partial Content"), mime, stream, end - start + 1).apply {
                addHeader("Content-Range", "bytes $start-$end/$len")
                addHeader("Accept-Ranges", "bytes")
            }
        }
        return newFixedLengthResponse(Code(200, "OK"), mime, FileInputStream(f).buffered(STORAGE_BUFFER), len).apply { addHeader("Accept-Ranges", "bytes") }
    }

    private fun put(target: File, body: RequestBody): Response {
        if (target == root || target.isDirectory) return reply(405, "Method Not Allowed")
        val parent = target.parentFile
        if (parent == null || !parent.isDirectory) return reply(409, "Conflict")
        val existed = target.exists()
        body.saveReplacing(target)
        log("Uploaded ${target.name}")
        return if (existed) reply(204, "No Content") else reply(201, "Created")
    }

    private fun delete(target: File): Response {
        if (target == root) return reply(403, "Forbidden")
        if (!target.exists()) return reply(404, "Not Found")
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (ok) log("Deleted ${target.name}")
        return if (ok) reply(204, "No Content") else reply(403, "Forbidden")
    }

    private fun mkcol(target: File): Response {
        if (target.exists()) return reply(405, "Method Not Allowed")
        val parent = target.parentFile
        if (parent == null || !parent.isDirectory) return reply(409, "Conflict")
        return if (target.mkdir()) reply(201, "Created") else reply(403, "Forbidden")
    }

    private fun transfer(s: IHTTPSession, src: File, move: Boolean): Response {
        if (!src.exists()) return reply(404, "Not Found")
        val destPath = s.headers["destination"]?.let { Uri.parse(it).path } ?: return reply(400, "Bad Request")
        val dest = resolve(destPath) ?: return reply(403, "Forbidden")
        if (dest == root || dest == src || dest.isInside(src) || src == root) return reply(403, "Forbidden")
        val parent = dest.parentFile
        if (parent == null || !parent.isDirectory) return reply(409, "Conflict")
        val existed = dest.exists()
        if (existed) {
            if (s.headers["overwrite"].equals("F", ignoreCase = true)) return reply(412, "Precondition Failed")
            dest.deleteRecursively()
        }
        if (!transferFile(src, dest, move)) return reply(500, "Internal Server Error", "Transfer failed")
        log("${if (move) "Moved" else "Copied"} ${src.name}")
        return if (existed) reply(204, "No Content") else reply(201, "Created")
    }

    private fun lock(): Response {
        val token = "opaquelocktoken:" + UUID.randomUUID()
        val body = "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>" +
            "<D:depth>infinity</D:depth><D:owner>pclink</D:owner><D:timeout>Second-3600</D:timeout><D:locktoken><D:href>$token</D:href></D:locktoken></D:activelock></D:lockdiscovery></D:prop>"
        return xml(body, 200, "OK").apply { addHeader("Lock-Token", "<$token>") }
    }

    private fun proppatch(path: String): Response =
        xml("<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>${escape(href(path, false))}</D:href><D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>")

    private fun propfind(s: IHTTPSession, f: File, path: String): Response {
        if (!f.exists()) return reply(404, "Not Found")
        val depth = s.headers["depth"] ?: "1"
        val sb = StringBuilder("<D:multistatus xmlns:D=\"DAV:\">")
        sb.append(entry(href(path, f.isDirectory), f))
        if (f.isDirectory && depth != "0") {
            val base = path.trimEnd('/')
            (f.listFiles() ?: emptyArray()).forEach { sb.append(entry(href("$base/${it.name}", it.isDirectory), it)) }
            if (f == root) sb.append(collection("/_install/", "_install"))
        }
        sb.append("</D:multistatus>")
        return xml(sb.toString())
    }

    private fun entry(href: String, f: File): String =
        if (f.isDirectory) collection(href, if (f == root) "" else f.name, f.lastModified())
        else item(href, f.name, f.length(), f.lastModified(), true)

    private fun collection(href: String, name: String, modified: Long = System.currentTimeMillis()): String =
        "<D:response><D:href>${escape(href)}</D:href><D:propstat><D:prop><D:displayname>${escape(name)}</D:displayname>" +
            "<D:resourcetype><D:collection/></D:resourcetype><D:getlastmodified>${httpDate(modified)}</D:getlastmodified>" +
            "<D:creationdate>${isoDate(modified)}</D:creationdate></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"

    private fun item(href: String, name: String, size: Long, modified: Long, typed: Boolean): String =
        "<D:response><D:href>${escape(href)}</D:href><D:propstat><D:prop><D:displayname>${escape(name)}</D:displayname><D:resourcetype/>" +
            "<D:getcontentlength>$size</D:getcontentlength><D:getlastmodified>${httpDate(modified)}</D:getlastmodified>" +
            "<D:creationdate>${isoDate(modified)}</D:creationdate>" +
            (if (typed) "<D:getcontenttype>${escape(mimeOf(name))}</D:getcontenttype>" else "") +
            "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"

    private fun href(path: String, dir: Boolean): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val base = "/" + segments.joinToString("/") { Uri.encode(it) }
        return if (dir && segments.isNotEmpty()) "$base/" else base
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun httpDate(ms: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(ms))

    private fun isoDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
