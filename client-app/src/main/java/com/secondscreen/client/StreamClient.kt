package com.secondscreen.client

import android.view.Surface
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.Socket

/**
 * Подключается к StreamingServer на планшете, читает length-prefixed фреймы
 * и передаёт их в VideoDecoder для отображения на Surface.
 */
class StreamClient(
    private val hostIp: String,
    private val port: Int,
    private val surface: Surface,
    private val onStatus: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: Socket? = null
    private var videoDecoder: VideoDecoder? = null

    fun connect() {
        scope.launch {
            try {
                onStatus("Подключение...")
                socket = Socket(hostIp, port).apply {
                    soTimeout = 5000
                    tcpNoDelay = true
                }

                videoDecoder = VideoDecoder(surface)
                videoDecoder?.init()

                onConnected()
                onStatus("Получение потока...")

                receiveLoop(socket!!.getInputStream())

            } catch (e: Exception) {
                onStatus("Ошибка: ${e.message}")
            } finally {
                cleanup()
                onDisconnected()
            }
        }
    }

    private suspend fun receiveLoop(inputStream: InputStream) = withContext(Dispatchers.IO) {
        val lengthBuf = ByteArray(4)

        while (isActive) {
            try {
                // Читаем 4-байтную длину фрейма
                if (!readFully(inputStream, lengthBuf, 4)) break

                val frameLen = ((lengthBuf[0].toInt() and 0xFF) shl 24) or
                               ((lengthBuf[1].toInt() and 0xFF) shl 16) or
                               ((lengthBuf[2].toInt() and 0xFF) shl 8) or
                               (lengthBuf[3].toInt() and 0xFF)

                if (frameLen <= 0 || frameLen > 10_000_000) {
                    // Некорректный размер — переподключение
                    break
                }

                val frameBuf = ByteArray(frameLen)
                if (!readFully(inputStream, frameBuf, frameLen)) break

                // Первый байт — флаги
                val flags = frameBuf[0].toInt() and 0xFF
                val isSps = flags and 0x02 != 0
                val isKeyFrame = flags and 0x01 != 0

                val payload = frameBuf.copyOfRange(1, frameBuf.size)

                if (isSps) {
                    videoDecoder?.feedSpsData(payload)
                } else {
                    videoDecoder?.feedFrame(payload, isKeyFrame)
                }

            } catch (e: java.net.SocketTimeoutException) {
                // таймаут — продолжаем ждать
                continue
            } catch (e: Exception) {
                if (isActive) onStatus("Потеря соединения: ${e.message}")
                break
            }
        }
    }

    private fun readFully(stream: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = stream.read(buf, read, len - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun cleanup() {
        videoDecoder?.release()
        videoDecoder = null
        runCatching { socket?.close() }
        socket = null
    }

    fun disconnect() {
        scope.cancel()
        cleanup()
    }
}
