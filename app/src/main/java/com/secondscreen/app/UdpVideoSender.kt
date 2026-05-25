package com.secondscreen.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpVideoSender {
    companion object {
        const val UDP_PORT = 8766
        const val TYPE_FRAME: Byte = 0
        const val TYPE_CONFIG: Byte = 1
        private const val MAX_CHUNK = 60_000
        private const val HDR = 9 // frameId(4) + type(1) + totalChunks(2) + chunkIdx(2)
    }

    private val socket = DatagramSocket()
    @Volatile private var viewerAddr: InetAddress? = null
    @Volatile private var frameId = 0

    fun setViewer(ip: InetAddress) { viewerAddr = ip }
    fun clearViewer() { viewerAddr = null }

    fun sendConfig(data: ByteArray) = send(data, TYPE_CONFIG)
    fun sendFrame(data: ByteArray) = send(data, TYPE_FRAME)

    private fun send(data: ByteArray, type: Byte) {
        val addr = viewerAddr ?: return
        val fid = frameId++
        val totalChunks = ((data.size - 1) / MAX_CHUNK) + 1
        for (i in 0 until totalChunks) {
            val off = i * MAX_CHUNK
            val len = minOf(MAX_CHUNK, data.size - off)
            val pkt = ByteArray(HDR + len).also { p ->
                p[0] = (fid shr 24).toByte(); p[1] = (fid shr 16).toByte()
                p[2] = (fid shr 8).toByte();  p[3] = fid.toByte()
                p[4] = type
                p[5] = (totalChunks shr 8).toByte(); p[6] = totalChunks.toByte()
                p[7] = (i shr 8).toByte();           p[8] = i.toByte()
                data.copyInto(p, HDR, off, off + len)
            }
            try { socket.send(DatagramPacket(pkt, pkt.size, addr, UDP_PORT)) }
            catch (e: Exception) { break }
        }
    }

    fun stop() { try { socket.close() } catch (e: Exception) { } }
}
