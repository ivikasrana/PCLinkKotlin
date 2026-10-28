package com.vik.pclink.server

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import org.json.JSONArray
import org.json.JSONObject
import com.vik.pclink.server.RequestBody.Companion.MAX_TEXT
import com.vik.pclink.server.RequestBody.Companion.STORAGE_BUFFER
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Socket
import java.util.UUID

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

    private fun xml(body: String, code: Int = 207, text: String = "Multi-Status"): Response =
        reply(code, text, DavXml.document(body), "application/xml; charset=utf-8")

    private fun authorized(s: IHTTPSession): Boolean {
        val (u, p) = basicCredentials(s.headers["authorization"]) ?: return false
        return credentialsMatch(u, p, user, password)
    }

    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler {
        runCatching { finalAccept.tcpNoDelay = true }
        return super.createClientHandler(finalAccept, inputStream)
    }

    override fun useGzipWhenAccepted(r: Response): Boolean = r.requestMethod != Method.HEAD && r.mimeType?.contains("/json") == true

    override fun serve(session: IHTTPSession): Response {
        val body = RequestBody(session)
        val response = when {
            !authorized(session) -> reply(401, "Unauthorized", "Unauthorized").apply {
                addHeader("WWW-Authenticate", "Basic realm=\"PCLink\"")
                addHeader("Connection", "close")
            }
            body.chunked -> reply(411, "Length Required", "Chunked uploads are not supported").apply { closeConnection(true) }
            else -> try {
                route(session, session.method, body)
            } catch (e: Exception) {
                reply(500, "Internal Server Error", e.message ?: "error").apply { closeConnection(true) }
            }
        }
        if (!body.discard(MAX_TEXT)) response.closeConnection(true)
        if (session.method == Method.HEAD) {
            runCatching { response.data?.close() }
            response.data = ByteArrayInputStream(ByteArray(0))
        }
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
            Method.PROPFIND -> if (!webdav) reply(405, "Method Not Allowed") else xml(
                DavXml.multistatus(
                    if (name.isEmpty() || path == "/_install") DavXml.collection("/_install/", "_install")
                    else DavXml.item(DavXml.href(path, false), name, 0, System.currentTimeMillis(), false),
                ),
            )
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
            .map { it to it.isDirectory }
            .sortedWith(compareBy<Pair<File, Boolean>>({ !it.second }).thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.name })
            .forEach { (f, isDir) ->
                items.put(JSONObject().put("name", f.name).put("dir", isDir).put("size", if (isDir) 0 else f.length()).put("mtime", f.lastModified()))
            }
        return reply(200, "OK", JSONObject().put("items", items).toString(), JSON)
    }

    private fun serveFile(s: IHTTPSession, f: File): Response {
        val len = f.length()
        val mime = mimeOf(f.name)
        val range = byteRange(s.headers["range"], len)
        if (range != null) {
            if (range.isEmpty()) return reply(416, "Range Not Satisfiable").apply { addHeader("Content-Range", "bytes */$len") }
            val stream = FileInputStream(f).also { it.channel.position(range.first) }.buffered(STORAGE_BUFFER)
            return newFixedLengthResponse(Code(206, "Partial Content"), mime, stream, range.last - range.first + 1).apply {
                addHeader("Content-Range", "bytes ${range.first}-${range.last}/$len")
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
        if (dest == root || dest == src || dest.isInside(src) || src.isInside(dest) || src == root) return reply(403, "Forbidden")
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
        return xml(DavXml.lock(token), 200, "OK").apply { addHeader("Lock-Token", "<$token>") }
    }

    private fun proppatch(path: String): Response = xml(DavXml.proppatched(DavXml.href(path, false)))

    private fun propfind(s: IHTTPSession, f: File, path: String): Response {
        if (!f.exists()) return reply(404, "Not Found")
        val depth = s.headers["depth"] ?: "1"
        val sb = StringBuilder(DavXml.entry(DavXml.href(path, f.isDirectory), f, root))
        if (f.isDirectory && depth != "0") {
            val base = path.trimEnd('/')
            (f.listFiles() ?: emptyArray()).forEach {
                val isDir = it.isDirectory
                sb.append(DavXml.entry(DavXml.href("$base/${it.name}", isDir), it, root, isDir))
            }
            if (f == root) sb.append(DavXml.collection("/_install/", "_install"))
        }
        return xml(DavXml.multistatus(sb.toString()))
    }
}
