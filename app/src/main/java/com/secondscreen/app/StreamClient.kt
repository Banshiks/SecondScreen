package com.secondscreen.app

import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.Socket

class StreamClient(
    private val hostIp: String,
    private val port: Int,
    private val onFrame: (ByteArray) -> Unit,
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
                onConnected()
                onStatus("Подключено")
                val input = DataInputStream(socket!!.getInputStream())
                while (isActive) {
                    val size = input.readInt()
                    if (size <= 0 || size > 20_000_000) break
                    val data = ByteArray(size)
                    input.readFully(data)
                    onFrame(data)
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
