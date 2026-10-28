package com.vik.pclink.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class Note(val time: Long, val app: String, val title: String, val text: String)

object ServerState {
    val running = MutableStateFlow(false)
    val urls = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val error = MutableStateFlow("")
    val log = MutableStateFlow<List<String>>(emptyList())
    val inbox = MutableStateFlow<List<String>>(emptyList())

    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault())
    private val outbox = ArrayDeque<String>()
    private val notes = ArrayDeque<Note>()

    fun add(message: String) {
        val line = "${stamp.format(Instant.now())}  $message"
        log.update { (listOf(line) + it).take(60) }
    }

    fun addInbox(text: String) {
        inbox.update { (listOf(text) + it).take(20) }
    }

    fun pushOutbox(text: String) {
        synchronized(outbox) {
            outbox.addLast(text)
            while (outbox.size > 50) outbox.removeFirst()
        }
        add("Text queued for PC")
    }

    fun drainOutbox(): List<String> = synchronized(outbox) {
        val items = outbox.toList()
        outbox.clear()
        items
    }

    fun addNote(note: Note) {
        synchronized(notes) {
            notes.addLast(note)
            while (notes.size > 100) notes.removeFirst()
        }
    }

    fun notesSince(time: Long): List<Note> = synchronized(notes) { notes.filter { it.time > time } }
}
