package com.secondscreen.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
    private var decoderStarted = false
    private var pendingSurface: SurfaceHolder? = null

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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fitSurfaceToScreen()
    }

    private fun fitSurfaceToScreen() {
        val root = window.decorView
        val screenW = root.width
        val screenH = root.height
        if (screenW == 0 || screenH == 0) return

        val streamRatio = HostService.STREAM_WIDTH.toFloat() / HostService.STREAM_HEIGHT
        val screenRatio = screenW.toFloat() / screenH

        val surfW: Int
        val surfH: Int
        if (streamRatio > screenRatio) {
            surfW = screenW
            surfH = (screenW / streamRatio).toInt()
        } else {
            surfH = screenH
            surfW = (screenH * streamRatio).toInt()
        }

        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        lp.width = surfW
        lp.height = surfH
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
    }

    private fun startStreaming(holder: SurfaceHolder) {
        val ip = intent.getStringExtra(EXTRA_HOST_IP) ?: return
        val port = intent.getIntExtra(EXTRA_HOST_PORT, HostService.STREAM_PORT)
        pendingSurface = holder

        streamClient = StreamClient(
            hostIp = ip, port = port,
            onFrame = { data, isConfig -> decode(data, isConfig) },
            onStatus = { s -> runOnUiThread { tvStatus.text = s } },
            onConnected = { runOnUiThread { tvStatus.visibility = View.GONE } },
            onDisconnected = { runOnUiThread { tvStatus.visibility = View.VISIBLE; tvStatus.text = "Соединение потеряно" } }
        )
        streamClient?.connect()
    }

    private fun decode(data: ByteArray, isConfig: Boolean) {
        if (!decoderStarted) {
            if (!isConfig) return
            val surface = pendingSurface?.surface ?: return
            try {
                decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(
                        MediaFormat.createVideoFormat(
                            MediaFormat.MIMETYPE_VIDEO_AVC,
                            HostService.STREAM_WIDTH, HostService.STREAM_HEIGHT
                        ),
                        surface, null, 0
                    )
                    start()
                }
                decoderStarted = true
            } catch (e: Exception) { return }
        }

        val codec = decoder ?: return
        try {
            val idx = codec.dequeueInputBuffer(5_000)
            if (idx >= 0) {
                codec.getInputBuffer(idx)!!.apply { clear(); put(data) }
                val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                codec.queueInputBuffer(idx, 0, data.size, System.nanoTime() / 1000, flags)
            }
            val info = MediaCodec.BufferInfo()
            val outIdx = codec.dequeueOutputBuffer(info, 0)
            if (outIdx >= 0) codec.releaseOutputBuffer(outIdx, true)
        } catch (e: Exception) { }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { startStreaming(holder) }
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        streamClient?.disconnect(); streamClient = null
        decoder?.stop(); decoder?.release(); decoder = null
        decoderStarted = false; pendingSurface = null
    }

    override fun onDestroy() {
        super.onDestroy()
        streamClient?.disconnect()
    }
}
