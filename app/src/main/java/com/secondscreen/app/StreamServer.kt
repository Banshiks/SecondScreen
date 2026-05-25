package com.secondscreen.app

import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

class StreamServer(private val port: Int, private val onClientChange: (Int) -> Unit) {

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
            val out = DataOutputStream(socket.getOutputStream())
            synchronized(clients) { clients.add(out); onClientChange(clients.size) }
            try { while (isActive) delay(5000) }
            finally {
                synchronized(clients) { clients.remove(out); onClientChange(clients.size) }
                socket.close()
            }
        }
    }

    fun sendSpsData(data: ByteArray) = sendRaw(data, isConfig = true)
    fun sendFrame(data: ByteArray, isKey: Boolean) = sendRaw(data, isConfig = false)

    private fun sendRaw(data: ByteArray, isConfig: Boolean) {
        val dead = mutableListOf<DataOutputStream>()
        synchronized(clients) {
            for (out in clients) {
                try { out.writeInt(data.size); out.write(data); out.flush() }
                catch (e: Exception) { dead.add(out) }
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
