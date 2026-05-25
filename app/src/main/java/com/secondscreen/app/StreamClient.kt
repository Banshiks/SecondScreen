package com.secondscreen.app

import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class StreamClient(
    private val hostIp: String,
    private val port: Int,
    private val viewerWidth: Int,
    private val viewerHeight: Int,
    private val onFrame: (ByteArray, isConfig: Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onConnectionLost: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    companion object {
        const val ACTION_DOWN = TouchAccessibilityService.ACTION_DOWN
        const val ACTION_MOVE = TouchAccessibilityService.ACTION_MOVE
        const val ACTION_UP   = TouchAccessibilityService.ACTION_UP
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: Socket? = null
    private val output = AtomicReference<DataOutputStream?>(null)

    fun connect() {
        scope.launch {
            while (isActive) {
                try {
                    onStatus("Подключение к $hostIp...")
                    val sock = Socket(hostIp, port)
                    socket = sock

                    val out = DataOutputStream(sock.getOutputStream())
                    output.set(out)
                    out.writeInt(viewerWidth)
                    out.writeInt(viewerHeight)
                    out.flush()

                    onConnected()
                    onStatus("Подключено")

                    val inp = DataInputStream(sock.getInputStream())
                    while (isActive) {
                        val type = inp.readByte()
                        val size = inp.readInt()
                        if (size <= 0 || size > 20_000_000) break
                        val data = ByteArray(size)
                        inp.readFully(data)
                        onFrame(data, type == StreamServer.TYPE_CONFIG)
                    }
                } catch (e: Exception) {
                    // ignore CancellationException — coroutine is stopping
                }
                output.set(null)
                socket?.close()
                socket = null
                if (!isActive) break
                onConnectionLost()
                onStatus("Переподключение...")
                delay(3_000)
            }
            onDisconnected()
        }
    }

    fun sendTouch(action: Int, x: Float, y: Float) {
        scope.launch {
            try {
                val out = output.get() ?: return@launch
                out.writeByte(StreamServer.MSG_TOUCH.toInt())
                out.writeByte(action)
                out.writeInt(x.toBits())
                out.writeInt(y.toBits())
                out.flush()
            } catch (e: Exception) { }
        }
    }

    fun disconnect() {
        scope.cancel()
        socket?.close()
    }
}
