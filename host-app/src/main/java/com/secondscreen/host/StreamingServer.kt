package com.secondscreen.host

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*

/**
 * Простой WebSocket сервер на базе raw Java ServerSocket + HTTP Upgrade.
 * Для production замените на Ktor или NanoHTTPD.
 *
 * Протокол фреймов (бинарный):
 * [1 байт флаги][4 байта timestamp][N байт H.264 данные]
 * Флаги: 0x01 = keyframe, 0x02 = SPS/PPS init data
 */
class StreamingServer(
    private val port: Int,
    private val onClientCountChanged: (Int) -> Unit
) {
    private val clients = CopyOnWriteArrayList<ClientConnection>()
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var spsData: ByteArray? = null  // кэш SPS/PPS для новых клиентов

    fun start() {
        serverSocket = ServerSocket(port)
        scope.launch { acceptLoop() }
    }

    private suspend fun acceptLoop() = withContext(Dispatchers.IO) {
        while (isActive) {
            try {
                val socket = serverSocket?.accept() ?: break
                val client = ClientConnection(socket) {
                    clients.remove(it)
                    onClientCountChanged(clients.size)
                }
                // Отправляем SPS/PPS новому клиенту сразу
                spsData?.let { client.send(it, isSps = true) }

                clients.add(client)
                onClientCountChanged(clients.size)
                client.start()
            } catch (e: Exception) {
                if (isActive) e.printStackTrace()
            }
        }
    }

    fun sendSpsData(data: ByteArray) {
        spsData = data
        clients.forEach { it.send(data, isSps = true) }
    }

    fun sendFrame(data: ByteArray, isKeyFrame: Boolean) {
        if (clients.isEmpty()) return
        val packet = buildPacket(data, isKeyFrame = isKeyFrame, isSps = false)
        clients.forEach { it.sendRaw(packet) }
    }

    private fun buildPacket(data: ByteArray, isKeyFrame: Boolean, isSps: Boolean): ByteArray {
        val timestamp = System.currentTimeMillis()
        val packet = ByteArray(5 + data.size)
        // 1 байт флагов
        var flags = 0
        if (isKeyFrame) flags = flags or 0x01
        if (isSps) flags = flags or 0x02
        packet[0] = flags.toByte()
        // 4 байта timestamp (big-endian)
        packet[1] = (timestamp shr 24 and 0xFF).toByte()
        packet[2] = (timestamp shr 16 and 0xFF).toByte()
        packet[3] = (timestamp shr 8 and 0xFF).toByte()
        packet[4] = (timestamp and 0xFF).toByte()
        System.arraycopy(data, 0, packet, 5, data.size)
        return packet
    }

    fun stop() {
        scope.cancel()
        clients.forEach { it.close() }
        clients.clear()
        serverSocket?.close()
    }
}

/**
 * Одно WebSocket соединение с клиентом (упрощённый raw-socket стрим).
 * Для полного WebSocket handshake используйте OkHttp или Ktor на сервере.
 */
class ClientConnection(
    private val socket: java.net.Socket,
    private val onDisconnect: (ClientConnection) -> Unit
) {
    private val outputStream = socket.getOutputStream()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch { readLoop() }
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        try {
            val inputStream = socket.getInputStream()
            val buf = ByteArray(1024)
            while (isActive) {
                val n = inputStream.read(buf)
                if (n <= 0) break
                // Клиент прислал touch-данные — обработка в TouchReceiver
            }
        } catch (e: Exception) {
            // disconnect
        } finally {
            onDisconnect(this@ClientConnection)
            socket.close()
        }
    }

    fun send(data: ByteArray, isSps: Boolean) {
        val flags: Byte = if (isSps) 0x02 else 0x00
        val packet = ByteArray(1 + data.size)
        packet[0] = flags
        System.arraycopy(data, 0, packet, 1, data.size)
        sendRaw(packet)
    }

    fun sendRaw(data: ByteArray) {
        try {
            // Простой length-prefixed фрейм: [4 байта длина][данные]
            val frame = ByteArray(4 + data.size)
            frame[0] = (data.size shr 24 and 0xFF).toByte()
            frame[1] = (data.size shr 16 and 0xFF).toByte()
            frame[2] = (data.size shr 8 and 0xFF).toByte()
            frame[3] = (data.size and 0xFF).toByte()
            System.arraycopy(data, 0, frame, 4, data.size)
            synchronized(outputStream) {
                outputStream.write(frame)
                outputStream.flush()
            }
        } catch (e: Exception) {
            // клиент отключился
        }
    }

    fun close() {
        scope.cancel()
        runCatching { socket.close() }
    }
}
