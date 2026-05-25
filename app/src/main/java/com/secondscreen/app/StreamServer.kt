package com.secondscreen.app

import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class StreamServer(
    private val port: Int,
    private val onClientChange: (Int) -> Unit,
    private val onClientConnect: (ip: InetAddress, w: Int, h: Int) -> Unit = { _, _, _ -> },
    private val onTouchEvent: (action: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
    private val onClipboard: (String) -> Unit = {}
) {
    companion object {
        // Server → Client frame types (over TCP)
        const val TYPE_AUDIO_CONFIG: Byte = 2
        const val TYPE_AUDIO: Byte = 3
        const val TYPE_CLIPBOARD: Byte = 4
        // Client → Server messages
        const val MSG_TOUCH: Byte = 5
        const val MSG_CLIPBOARD: Byte = 6
    }

    private var serverSocket: ServerSocket? = null
    private val clients = mutableListOf<DataOutputStream>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            serverSocket = ServerSocket(port)
            while (isActive) {
                try { handleClient(serverSocket!!.accept()) } catch (e: Exception) { break }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            try {
                val input = DataInputStream(socket.getInputStream())
                val out = DataOutputStream(socket.getOutputStream())
                val w = input.readInt()
                val h = input.readInt()
                onClientConnect(socket.inetAddress, w, h)
                synchronized(clients) { clients.add(out); onClientChange(clients.size) }
                try {
                    while (isActive) {
                        when (input.readByte()) {
                            MSG_TOUCH -> {
                                val action = input.readByte().toInt()
                                val x = Float.fromBits(input.readInt())
                                val y = Float.fromBits(input.readInt())
                                onTouchEvent(action, x, y)
                            }
                            MSG_CLIPBOARD -> {
                                val len = input.readInt()
                                if (len in 1..100_000) {
                                    val bytes = ByteArray(len)
                                    input.readFully(bytes)
                                    onClipboard(String(bytes, Charsets.UTF_8))
                                }
                            }
                        }
                    }
                } finally {
                    synchronized(clients) { clients.remove(out); onClientChange(clients.size) }
                    socket.close()
                }
            } catch (e: Exception) { socket.close() }
        }
    }

    fun sendAudio(data: ByteArray, isConfig: Boolean) =
        broadcast(if (isConfig) TYPE_AUDIO_CONFIG else TYPE_AUDIO, data)

    fun sendClipboard(text: String) =
        broadcast(TYPE_CLIPBOARD, text.toByteArray(Charsets.UTF_8))

    private fun broadcast(type: Byte, data: ByteArray) {
        val dead = mutableListOf<DataOutputStream>()
        synchronized(clients) {
            for (out in clients) {
                try { out.writeByte(type.toInt()); out.writeInt(data.size); out.write(data); out.flush() }
                catch (e: Exception) { dead.add(out) }
            }
            clients.removeAll(dead)
        }
        if (dead.isNotEmpty()) onClientChange(clients.size)
    }

    fun stop() {
        scope.cancel(); serverSocket?.close()
        synchronized(clients) { clients.clear() }
    }
}
