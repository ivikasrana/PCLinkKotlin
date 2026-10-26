package com.vik.pclink.server

import android.util.Base64
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.IStatus
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.security.MessageDigest

internal class Code(private val code: Int, private val text: String) : IStatus {
    override fun getRequestStatus() = code
    override fun getDescription() = "$code $text"
}

internal const val TEXT = "text/plain; charset=utf-8"
internal const val JSON = "application/json; charset=utf-8"

internal fun reply(code: Int, text: String, body: String = "", mime: String = TEXT): Response =
    newFixedLengthResponse(Code(code, text), mime, body)

internal fun credentialsMatch(user: String, password: String, expectedUser: String, expectedPassword: String): Boolean {
    val userOk = MessageDigest.isEqual(user.toByteArray(), expectedUser.toByteArray())
    val passOk = MessageDigest.isEqual(password.toByteArray(), expectedPassword.toByteArray())
    return userOk && passOk
}

internal fun basicCredentials(header: String?): Pair<String, String>? {
    if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return null
    val decoded = runCatching { String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return null
    val i = decoded.indexOf(':')
    return if (i < 0) null else decoded.substring(0, i) to decoded.substring(i + 1)
}

internal fun byteRange(header: String?, length: Long): LongRange? {
    if (header == null || !header.startsWith("bytes=") || length <= 0) return null
    val spec = header.substring(6).substringBefore(',')
    val a = spec.substringBefore('-')
    val b = spec.substringAfter('-')
    val start: Long
    val end: Long
    if (a.isEmpty()) {
        start = (length - (b.toLongOrNull() ?: 0)).coerceAtLeast(0)
        end = length - 1
    } else {
        start = a.toLongOrNull() ?: 0
        end = (b.toLongOrNull() ?: (length - 1)).coerceAtMost(length - 1)
    }
    return if (start >= length || start > end) LongRange.EMPTY else start..end
}
