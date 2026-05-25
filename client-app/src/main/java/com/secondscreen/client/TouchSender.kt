package com.secondscreen.client

import android.view.MotionEvent
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Перехватывает touch-события на Nothing Phone,
 * масштабирует координаты под разрешение стрима
 * и отправляет хосту по UDP.
 *
 * Протокол: 10 байт
 * [1 байт action][4 байта x (float BE)][4 байта y (float BE)][1 байт pointerCount]
 */
class TouchSender(
    private val hostIp: String,
    private val port: Int,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val streamWidth: Int,
    private val streamHeight: Int
) {
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var hostAddress: InetAddress? = null

    private val scaleX get() = streamWidth.toFloat() / screenWidth
    private val scaleY get() = streamHeight.toFloat() / screenHeight

    init {
        scope.launch(Dispatchers.IO) {
            try {
                socket = DatagramSocket()
                hostAddress = InetAddress.getByName(hostIp)
            } catch (e: Exception) {
                android.util.Log.e("TouchSender", "Init failed: ${e.message}")
            }
        }
    }

    fun sendEvent(event: MotionEvent) {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> 2
            else -> return
        }

        // Масштабируем координаты
        val x = event.x * scaleX
        val y = event.y * scaleY

        scope.launch(Dispatchers.IO) {
            sendPacket(action.toByte(), x, y, event.pointerCount.toByte())
        }
    }

    private fun sendPacket(action: Byte, x: Float, y: Float, pointerCount: Byte) {
        val socket = socket ?: return
        val host = hostAddress ?: return

        val buf = ByteArray(10)
        buf[0] = action
        floatToBytes(x, buf, 1)
        floatToBytes(y, buf, 5)
        buf[9] = pointerCount

        try {
            val packet = DatagramPacket(buf, buf.size, host, port)
            socket.send(packet)
        } catch (e: Exception) {
            android.util.Log.e("TouchSender", "Send failed: ${e.message}")
        }
    }

    private fun floatToBytes(value: Float, buf: ByteArray, offset: Int) {
        val bits = java.lang.Float.floatToIntBits(value)
        buf[offset]     = (bits shr 24 and 0xFF).toByte()
        buf[offset + 1] = (bits shr 16 and 0xFF).toByte()
        buf[offset + 2] = (bits shr 8  and 0xFF).toByte()
        buf[offset + 3] = (bits        and 0xFF).toByte()
    }

    fun stop() {
        scope.cancel()
        runCatching { socket?.close() }
        socket = null
    }
}
