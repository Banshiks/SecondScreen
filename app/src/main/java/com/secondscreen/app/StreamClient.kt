package com.secondscreen.app

import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

class StreamClient(
    private val hostIp: String,
    private val port: Int,
    private val viewerWidth: Int,
    private val viewerHeight: Int,
    private val onFrame: (ByteArray, isConfig: Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: Socket? = null

    fun connect() {
        scope.launch {
            try {
                onStatus("Подключение к $hostIp...")
                socket = Socket(hostIp, port)
                // Tell host our screen dimensions so it streams at the right resolution
                val out = DataOutputStream(socket!!.getOutputStream())
                out.writeInt(viewerWidth)
                out.writeInt(viewerHeight)
                out.flush()

                onConnected()
                onStatus("Подключено")
                val input = DataInputStream(socket!!.getInputStream())
                while (isActive) {
                    val type = input.readByte()
                    val size = input.readInt()
                    if (size <= 0 || size > 20_000_000) break
                    val data = ByteArray(size)
                    input.readFully(data)
                    onFrame(data, type == StreamServer.TYPE_CONFIG)
                }
            } catch (e: Exception) {
                onStatus("Ошибка подключения")
            } finally {
                socket?.close()
                onDisconnected()
            }
        }
    }

    fun disconnect() {
        scope.cancel()
        socket?.close()
    }
}
