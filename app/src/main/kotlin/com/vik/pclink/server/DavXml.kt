package com.vik.pclink.server

import android.net.Uri
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object DavXml {
    private const val OK = "<D:status>HTTP/1.1 200 OK</D:status>"
    private val HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)
    private val ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).withZone(ZoneOffset.UTC)

    fun document(body: String) = "<?xml version=\"1.0\" encoding=\"utf-8\"?>$body"

    fun multistatus(responses: String) = "<D:multistatus xmlns:D=\"DAV:\">$responses</D:multistatus>"

    fun entry(href: String, f: File, root: File, dir: Boolean = f.isDirectory): String =
        if (dir) collection(href, if (f == root) "" else f.name, f.lastModified())
        else item(href, f.name, f.length(), f.lastModified(), true)

    fun collection(href: String, name: String, modified: Long = System.currentTimeMillis()): String =
        "<D:response><D:href>${escape(href)}</D:href><D:propstat><D:prop><D:displayname>${escape(name)}</D:displayname>" +
            "<D:resourcetype><D:collection/></D:resourcetype><D:getlastmodified>${httpDate(modified)}</D:getlastmodified>" +
            "<D:creationdate>${isoDate(modified)}</D:creationdate></D:prop>$OK</D:propstat></D:response>"

    fun item(href: String, name: String, size: Long, modified: Long, typed: Boolean): String =
        "<D:response><D:href>${escape(href)}</D:href><D:propstat><D:prop><D:displayname>${escape(name)}</D:displayname><D:resourcetype/>" +
            "<D:getcontentlength>$size</D:getcontentlength><D:getlastmodified>${httpDate(modified)}</D:getlastmodified>" +
            "<D:creationdate>${isoDate(modified)}</D:creationdate>" +
            (if (typed) "<D:getcontenttype>${escape(mimeOf(name))}</D:getcontenttype>" else "") +
            "</D:prop>$OK</D:propstat></D:response>"

    fun proppatched(href: String) =
        multistatus("<D:response><D:href>${escape(href)}</D:href><D:propstat><D:prop/>$OK</D:propstat></D:response>")

    fun lock(token: String) =
        "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>" +
            "<D:depth>infinity</D:depth><D:owner>pclink</D:owner><D:timeout>Second-3600</D:timeout><D:locktoken><D:href>$token</D:href></D:locktoken></D:activelock></D:lockdiscovery></D:prop>"

    fun href(path: String, dir: Boolean): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val base = "/" + segments.joinToString("/") { Uri.encode(it) }
        return if (dir && segments.isNotEmpty()) "$base/" else base
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun httpDate(ms: Long): String = HTTP_DATE.format(Instant.ofEpochMilli(ms))

    private fun isoDate(ms: Long): String = ISO_DATE.format(Instant.ofEpochMilli(ms))
}
