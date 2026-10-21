package com.vik.pclink.server

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class Discovery(private val reply: () -> String) {
    private var socket: DatagramSocket? = null

    fun start() {
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: Exception) {
            return
        }
        socket = s
        thread(isDaemon = true) {
            val buffer = ByteArray(256)
            while (!s.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    s.receive(packet)
                    if (String(packet.data, 0, packet.length).startsWith("PCLINK?")) {
                        val bytes = reply().toByteArray()
                        s.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                    }
                } catch (e: Exception) {
                    if (s.isClosed) break
                }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        const val PORT = 1198
    }
}
