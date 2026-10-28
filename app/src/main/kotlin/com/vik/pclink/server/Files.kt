package com.vik.pclink.server

import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

fun incomingApk(cacheDir: File): File = File(File(cacheDir, "incoming").apply { mkdirs() }, "${UUID.randomUUID()}.apk")

fun File.isInside(dir: File): Boolean = path.startsWith(dir.path + File.separator)

fun File.child(path: String): File? {
    val f = File(this, path.trimStart('/')).canonicalFile
    return if (f == this || f.isInside(this)) f else null
}

fun transferFile(src: File, dest: File, move: Boolean): Boolean {
    if (dest == src || dest.isInside(src) || src.isInside(dest)) return false
    if (move && src.renameTo(dest)) return true
    val copied = runCatching { src.copyRecursively(dest, overwrite = true) }.getOrDefault(false)
    return copied && (!move || src.deleteRecursively())
}

fun mimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
