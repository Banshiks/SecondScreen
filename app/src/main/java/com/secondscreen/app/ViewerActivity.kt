package com.secondscreen.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ViewerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_HOST_IP = "host_ip"
        const val EXTRA_HOST_PORT = "host_port"
        const val EXTRA_DEVICE_NAME = "device_name"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView
    private var streamClient: StreamClient? = null
    private var decoder: MediaCodec? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        setContentView(R.layout.activity_viewer)

        surfaceView = findViewById(R.id.surfaceView)
        tvStatus = findViewById(R.id.tvStatus)

        val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: ""
        tvStatus.text = "Подключение${if (name.isNotEmpty()) " к $name" else ""}..."

        surfaceView.holder.addCallback(this)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun startStreaming(holder: SurfaceHolder) {
        val ip = intent.getStringExtra(EXTRA_HOST_IP) ?: return
        val port = intent.getIntExtra(EXTRA_HOST_PORT, HostService.STREAM_PORT)

        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                    HostService.STREAM_WIDTH, HostService.STREAM_HEIGHT),
                holder.surface, null, 0
            )
            start()
        }

        streamClient = StreamClient(
            hostIp = ip, port = port,
            onFrame = { data -> decode(data) },
            onStatus = { s -> runOnUiThread { tvStatus.text = s } },
            onConnected = { runOnUiThread { tvStatus.visibility = View.GONE } },
            onDisconnected = { runOnUiThread { tvStatus.visibility = View.VISIBLE; tvStatus.text = "Соединение потеряно" } }
        )
        streamClient?.connect()
    }

    private fun decode(data: ByteArray) {
        val codec = decoder ?: return
        try {
            val idx = codec.dequeueInputBuffer(5_000)
            if (idx >= 0) {
                codec.getInputBuffer(idx)!!.apply { clear(); put(data) }
                codec.queueInputBuffer(idx, 0, data.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            val outIdx = codec.dequeueOutputBuffer(info, 0)
            if (outIdx >= 0) codec.releaseOutputBuffer(outIdx, true)
        } catch (e: Exception) { /* ignore */ }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { startStreaming(holder) }
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        streamClient?.disconnect(); streamClient = null
        decoder?.stop(); decoder?.release(); decoder = null
    }

    override fun onDestroy() {
        super.onDestroy()
        streamClient?.disconnect()
    }
}
