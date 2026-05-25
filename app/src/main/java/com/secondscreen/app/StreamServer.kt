package com.secondscreen.app

import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

class StreamServer(
    private val port: Int,
    private val onClientChange: (Int) -> Unit,
    private val onViewerDimensions: (Int, Int) -> Unit = { _, _ -> },
    private val onTouchEvent: (action: Int, x: Float, y: Float) -> Unit = { _, _, _ -> }
) {
    companion object {
        const val TYPE_FRAME: Byte = 0   // server → client
        const val TYPE_CONFIG: Byte = 1  // server → client
        const val MSG_TOUCH: Byte = 2    // client → server
    }

    private var serverSocket: ServerSocket? = null
    private val clients = mutableListOf<DataOutputStream>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var lastSpsData: ByteArray? = null

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

                // Handshake: read viewer screen dimensions
                val viewerW = input.readInt()
                val viewerH = input.readInt()
                onViewerDimensions(viewerW, viewerH)

                // Send cached SPS/PPS so decoder initializes immediately
                lastSpsData?.let { sps ->
                    out.writeByte(TYPE_CONFIG.toInt())
                    out.writeInt(sps.size)
                    out.write(sps)
                    out.flush()
                }

                synchronized(clients) { clients.add(out); onClientChange(clients.size) }
                try {
                    // Read touch events from client
                    while (isActive) {
                        val msgType = input.readByte()
                        if (msgType == MSG_TOUCH) {
                            val action = input.readByte().toInt()
                            val x = Float.fromBits(input.readInt())
                            val y = Float.fromBits(input.readInt())
                            onTouchEvent(action, x, y)
                        }
                    }
                } finally {
                    synchronized(clients) { clients.remove(out); onClientChange(clients.size) }
                    socket.close()
                }
            } catch (e: Exception) {
                socket.close()
            }
        }
    }

    fun sendSpsData(data: ByteArray) {
        lastSpsData = data
        sendRaw(data, TYPE_CONFIG)
    }

    fun sendFrame(data: ByteArray, isKey: Boolean) = sendRaw(data, TYPE_FRAME)

    fun clearSps() { lastSpsData = null }

    private fun sendRaw(data: ByteArray, type: Byte) {
        val dead = mutableListOf<DataOutputStream>()
        synchronized(clients) {
            for (out in clients) {
                try {
                    out.writeByte(type.toInt())
                    out.writeInt(data.size)
                    out.write(data)
                    out.flush()
                } catch (e: Exception) { dead.add(out) }
            }
            clients.removeAll(dead)
        }
        if (dead.isNotEmpty()) onClientChange(clients.size)
    }

    fun stop() {
        scope.cancel()
        serverSocket?.close()
        synchronized(clients) { clients.clear() }
    }
}
