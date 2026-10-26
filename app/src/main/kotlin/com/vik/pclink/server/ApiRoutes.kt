package com.vik.pclink.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vik.pclink.MainActivity
import com.vik.pclink.R
import com.vik.pclink.server.RequestBody.Companion.MAX_TEXT
import com.vik.pclink.server.RequestBody.Companion.PART_SUFFIX
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ApiRoutes(
    private val context: Context,
    private val root: File,
    private val info: () -> JSONObject,
    private val log: (String) -> Unit,
) {
    private fun json(body: Any, code: Int = 200, text: String = "OK"): Response =
        reply(code, text, body.toString(), JSON)

    private fun fail(code: Int, text: String): Response = json(JSONObject().put("error", text), code, text)

    private fun resolve(path: String): File? = root.child(path)

    private fun param(s: IHTTPSession, name: String): String? = s.parameters[name]?.firstOrNull()

    fun handle(s: IHTTPSession, method: Method, path: String, body: RequestBody): Response = try {
        when {
            path == "/api/info" && method == Method.GET -> json(info())
            path == "/api/manifest" && method == Method.GET -> manifest(s)
            path == "/api/chunk" && method == Method.GET -> chunkStatus(s)
            path == "/api/chunk" && method == Method.PUT -> chunkPut(s, body)
            path == "/api/text" && method == Method.POST -> text(body)
            path == "/api/outbox" && method == Method.GET -> json(JSONArray(ServerState.drainOutbox()))
            path == "/api/notifications" && method == Method.GET -> notifications(s)
            else -> fail(404, "Not Found")
        }
    } catch (e: Exception) {
        fail(500, e.message ?: "error")
    }

    private fun manifest(s: IHTTPSession): Response {
        val dir = resolve(param(s, "path") ?: "/") ?: return fail(403, "Forbidden")
        val items = JSONArray()
        if (dir.isDirectory) {
            dir.walkTopDown().drop(1).filter { !it.name.endsWith(PART_SUFFIX) }.take(50000).forEach {
                items.put(
                    JSONObject()
                        .put("path", it.relativeTo(dir).path.replace(File.separatorChar, '/'))
                        .put("dir", it.isDirectory)
                        .put("size", if (it.isDirectory) 0 else it.length())
                        .put("mtime", it.lastModified()),
                )
            }
        }
        return json(JSONObject().put("items", items))
    }

    private class Upload(val target: File, val total: Long, val mtime: Long) {
        val part = File(target.parentFile, "${target.name}.$total-$mtime$PART_SUFFIX")

        fun dropStale() {
            val pattern = Regex(Regex.escape(target.name) + """(\.-?\d+--?\d+)?""" + Regex.escape(PART_SUFFIX))
            target.parentFile?.listFiles { f -> f != part && pattern.matches(f.name) }?.forEach { it.delete() }
        }
    }

    private fun upload(s: IHTTPSession): Upload? {
        val target = resolve(param(s, "path") ?: return null) ?: return null
        return Upload(target, param(s, "total")?.toLongOrNull() ?: -1L, param(s, "mtime")?.toLongOrNull() ?: 0L)
    }

    private fun chunkStatus(s: IHTTPSession): Response {
        val u = upload(s) ?: return fail(400, "path required")
        val part = u.part
        return json(JSONObject().put("part", if (part.exists()) part.length() else 0).put("size", if (u.target.isFile) u.target.length() else -1))
    }

    private fun chunkPut(s: IHTTPSession, body: RequestBody): Response {
        val u = upload(s) ?: return fail(400, "path required")
        val target = u.target
        if (target == root || target.isDirectory) return fail(405, "Method Not Allowed")
        val offset = param(s, "offset")?.toLongOrNull() ?: 0L
        target.parentFile?.mkdirs()
        val part = u.part
        if (offset == 0L) {
            part.delete()
            u.dropStale()
        }
        val have = if (part.exists()) part.length() else 0L
        if (offset != have) return json(JSONObject().put("error", "offset mismatch").put("part", have), 409, "Conflict")
        body.saveTo(part, append = true)
        val size = part.length()
        if (u.total in 0 until size) {
            part.delete()
            return fail(400, "more data than total")
        }
        if (u.total == size) {
            target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            if (u.mtime > 0) target.setLastModified(u.mtime)
            log("Uploaded ${target.name}")
            return json(JSONObject().put("done", true).put("size", size))
        }
        return json(JSONObject().put("done", false).put("size", size))
    }

    private fun text(body: RequestBody): Response {
        val text = body.text(MAX_TEXT) ?: return fail(413, "Payload Too Large")
        if (text.isBlank()) return fail(400, "empty")
        ServerState.addInbox(text)
        log("Text from PC")
        alert(text)
        return json(JSONObject().put("ok", true))
    }

    private fun alert(text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("messages", "Messages from PC", NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (text.length <= MAX_EXTRA) open.putExtra(MainActivity.EXTRA_COPY, text)
        val preview = text.take(MAX_PREVIEW)
        val tap = PendingIntent.getActivity(context, text.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, "messages")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Text from PC")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(3, n)
    }

    private fun notifications(s: IHTTPSession): Response {
        val since = param(s, "since")?.toLongOrNull() ?: 0L
        val items = JSONArray()
        ServerState.notesSince(since).forEach {
            items.put(JSONObject().put("time", it.time).put("app", it.app).put("title", it.title).put("text", it.text))
        }
        return json(items)
    }

    private companion object {
        const val MAX_EXTRA = 100_000
        const val MAX_PREVIEW = 2_000
    }
}
