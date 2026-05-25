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
    private val onAudioFrame: (ByteArray, isConfig: Boolean) -> Unit = { _, _ -> },
    private val onClipboard: (String) -> Unit = {},
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
    private val out = AtomicReference<DataOutputStream?>(null)

    fun connect() {
        scope.launch {
            while (isActive) {
                try {
                    onStatus("Подключение к $hostIp...")
                    val sock = Socket(hostIp, port).also { socket = it }
                    val o = DataOutputStream(sock.getOutputStream()).also { out.set(it) }
                    o.writeInt(viewerWidth); o.writeInt(viewerHeight); o.flush()
                    onConnected(); onStatus("Подключено")
                    val inp = DataInputStream(sock.getInputStream())
                    while (isActive) {
                        val type = inp.readByte()
                        val size = inp.readInt()
                        if (size <= 0 || size > 5_000_000) break
                        val data = ByteArray(size); inp.readFully(data)
                        when (type) {
                            StreamServer.TYPE_AUDIO_CONFIG -> onAudioFrame(data, true)
                            StreamServer.TYPE_AUDIO -> onAudioFrame(data, false)
                            StreamServer.TYPE_CLIPBOARD -> onClipboard(String(data, Charsets.UTF_8))
                        }
                    }
                } catch (e: Exception) { /* reconnect */ }
                out.set(null); socket?.close(); socket = null
                if (!isActive) break
                onConnectionLost(); onStatus("Переподключение..."); delay(3_000)
            }
            onDisconnected()
        }
    }

    fun sendTouch(action: Int, x: Float, y: Float) = sendRaw {
        it.writeByte(StreamServer.MSG_TOUCH.toInt())
        it.writeByte(action); it.writeInt(x.toBits()); it.writeInt(y.toBits()); it.flush()
    }

    fun sendClipboard(text: String) = sendRaw {
        val b = text.toByteArray(Charsets.UTF_8)
        it.writeByte(StreamServer.MSG_CLIPBOARD.toInt())
        it.writeInt(b.size); it.write(b); it.flush()
    }

    private fun sendRaw(block: (DataOutputStream) -> Unit) {
        scope.launch { try { out.get()?.let { block(it) } } catch (e: Exception) { } }
    }

    fun disconnect() { scope.cancel(); socket?.close() }
}
