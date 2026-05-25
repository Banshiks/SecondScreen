package com.secondscreen.app

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket

class UdpVideoReceiver(
    private val onFrame: (ByteArray, isConfig: Boolean) -> Unit
) {
    private companion object {
        const val HDR = 9
        const val BUF = 65536
    }

    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // frameId -> Pair(totalChunks, chunks)
    private val pending = mutableMapOf<Int, Pair<Int, Array<ByteArray?>>>()
    private var lastDone = -1

    fun start() {
        socket = DatagramSocket(UdpVideoSender.UDP_PORT)
        scope.launch { loop() }
    }

    private suspend fun loop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(BUF)
        val pkt = DatagramPacket(buf, BUF)
        while (isActive) {
            try {
                socket!!.receive(pkt)
                onPacket(buf, pkt.length)
            } catch (e: Exception) { break }
        }
    }

    private fun onPacket(raw: ByteArray, len: Int) {
        if (len < HDR) return
        val fid  = ((raw[0].toInt() and 0xFF) shl 24) or ((raw[1].toInt() and 0xFF) shl 16) or
                   ((raw[2].toInt() and 0xFF) shl 8)  or  (raw[3].toInt() and 0xFF)
        val type = raw[4]
        val tot  = ((raw[5].toInt() and 0xFF) shl 8) or (raw[6].toInt() and 0xFF)
        val idx  = ((raw[7].toInt() and 0xFF) shl 8) or (raw[8].toInt() and 0xFF)
        if (tot <= 0 || idx >= tot || fid < lastDone - 120) return

        val (_, chunks) = pending.getOrPut(fid) { Pair(tot, arrayOfNulls(tot)) }
        chunks[idx] = raw.copyOfRange(HDR, len)

        if (chunks.all { it != null }) {
            pending.remove(fid)
            lastDone = maxOf(lastDone, fid)
            val total = chunks.sumOf { it!!.size }
            val frame = ByteArray(total)
            var pos = 0
            chunks.forEach { c -> c!!.copyInto(frame, pos); pos += c.size }
            onFrame(frame, type == UdpVideoSender.TYPE_CONFIG)
        }
        // Evict stale frames
        if (pending.size > 60) pending.keys.filter { it < fid - 60 }.forEach { pending.remove(it) }
    }

    fun stop() {
        scope.cancel()
        try { socket?.close() } catch (e: Exception) { }
    }
}
