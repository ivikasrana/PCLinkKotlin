package com.vik.pclink.server

import fi.iki.elonen.NanoHTTPD.IHTTPSession
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.Charset

class RequestBody(private val s: IHTTPSession) {
    val length = (s.headers["content-length"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
    var remaining = length
        private set

    fun copyTo(out: OutputStream) {
        val buffer = ByteArray(65536)
        while (remaining > 0) {
            val n = s.inputStream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n < 0) throw EOFException("Upload ended early")
            out.write(buffer, 0, n)
            remaining -= n
        }
    }

    fun saveTo(file: File, append: Boolean = false) = FileOutputStream(file, append).use { copyTo(it) }

    fun saveReplacing(target: File) {
        val tmp = File(target.parentFile, target.name + ".upload" + PART_SUFFIX)
        try {
            saveTo(tmp)
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
        } finally {
            tmp.delete()
        }
    }

    fun text(limit: Long): String? {
        if (length > limit) return null
        val bytes = ByteArrayOutputStream(length.toInt()).also { copyTo(it) }.toByteArray()
        val charset = s.headers["content-type"]?.substringAfter("charset=", "")?.substringBefore(';')?.trim('"', ' ')
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    fun discard(limit: Long): Boolean {
        if (remaining == 0L) return true
        if (remaining > limit) return false
        return runCatching { copyTo(Sink) }.isSuccess
    }

    private object Sink : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    companion object {
        const val PART_SUFFIX = ".pclinkpart"
        const val MAX_TEXT = 1L shl 20
    }
}
