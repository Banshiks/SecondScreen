package com.secondscreen.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer

class ViewerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_HOST_IP = "host_ip"
        const val EXTRA_HOST_PORT = "host_port"
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val FILE_PICK_REQUEST = 2001
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView
    private var streamClient: StreamClient? = null
    private var udpReceiver: UdpVideoReceiver? = null

    private var decoder: MediaCodec? = null
    private var decoderStarted = false
    private var pendingSurface: SurfaceHolder? = null
    private var surfaceW = 0; private var surfaceH = 0

    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var audioReady = false

    private var clipManager: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var ignoreNextClip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        setContentView(R.layout.activity_viewer)
        surfaceView = findViewById(R.id.surfaceView)
        tvStatus = findViewById(R.id.tvStatus)
        val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: ""
        tvStatus.text = "Подключение${if (name.isNotEmpty()) " к $name" else ""}..."
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { _, event ->
            val client = streamClient ?: return@setOnTouchListener false
            val action = when (event.action) {
                MotionEvent.ACTION_DOWN -> StreamClient.ACTION_DOWN
                MotionEvent.ACTION_MOVE -> StreamClient.ACTION_MOVE
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> StreamClient.ACTION_UP
                else -> return@setOnTouchListener false
            }
            client.sendTouch(action, event.x, event.y)
            true
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSendFile).setOnClickListener { openFilePicker() }
        setupClipboardSync()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Выбрать файл"), FILE_PICK_REQUEST)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICK_REQUEST && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            sendFile(uri)
        }
    }

    private fun sendFile(uri: Uri) {
        val client = streamClient ?: run {
            Toast.makeText(this, "Нет подключения", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return@Thread
                val bytes = inputStream.readBytes()
                inputStream.close()
                if (bytes.size > MAX_FILE_SIZE) {
                    runOnUiThread {
                        Toast.makeText(this, "Файл слишком большой (макс 50MB)", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val cursor = contentResolver.query(uri, null, null, null, null)
                var fileName = "file"
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) fileName = it.getString(nameIdx) ?: "file"
                    }
                }
                client.sendFile(fileName, bytes)
                runOnUiThread {
                    Toast.makeText(this, "Файл отправлен: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка отправки файла", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun setupClipboardSync() {
        clipManager = getSystemService(ClipboardManager::class.java)
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            if (ignoreNextClip) { ignoreNextClip = false; return@OnPrimaryClipChangedListener }
            val text = clipManager?.primaryClip?.getItemAt(0)?.text?.toString() ?: return@OnPrimaryClipChangedListener
            if (text.isNotEmpty()) streamClient?.sendClipboard(text)
        }
        clipManager?.addPrimaryClipChangedListener(clipListener!!)
    }

    private fun startStreaming(holder: SurfaceHolder, w: Int, h: Int) {
        val ip = intent.getStringExtra(EXTRA_HOST_IP) ?: return
        val port = intent.getIntExtra(EXTRA_HOST_PORT, HostService.STREAM_PORT)
        pendingSurface = holder; surfaceW = w; surfaceH = h

        udpReceiver = UdpVideoReceiver { data, isConfig -> decodeVideo(data, isConfig) }
        udpReceiver?.start()

        streamClient = StreamClient(
            hostIp = ip, port = port, viewerWidth = w, viewerHeight = h,
            onAudioFrame = { data, isConfig -> handleAudio(data, isConfig) },
            onClipboard = { text ->
                runOnUiThread {
                    ignoreNextClip = true
                    clipManager?.setPrimaryClip(ClipData.newPlainText("SecondScreen", text))
                }
            },
            onStatus = { s -> runOnUiThread { tvStatus.text = s } },
            onConnected = { runOnUiThread { tvStatus.visibility = View.GONE } },
            onConnectionLost = { resetDecoder() },
            onDisconnected = {}
        )
        streamClient?.connect()
    }

    private fun resetDecoder() {
        decoderStarted = false
        decoder?.run { try { stop(); release() } catch (e: Exception) { } }
        decoder = null
        runOnUiThread { tvStatus.visibility = View.VISIBLE; tvStatus.text = "Переподключение..." }
    }

    private fun decodeVideo(data: ByteArray, isConfig: Boolean) {
        if (!decoderStarted) {
            if (!isConfig) return
            val surface = pendingSurface?.surface ?: return
            val w = if (surfaceW > 0) surfaceW else 1920
            val h = if (surfaceH > 0) surfaceH else 1080
            decoder?.run { try { stop(); release() } catch (e: Exception) { } }
            try {
                decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h),
                        surface, null, 0)
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

    private fun handleAudio(data: ByteArray, isConfig: Boolean) {
        if (!audioReady) {
            if (!isConfig) return
            try {
                audioDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(data))
                    configure(fmt, null, null, 0); start()
                }
                val minBuf = AudioTrack.getMinBufferSize(44100,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBuf * 4).build()
                audioTrack!!.play()
                audioReady = true
            } catch (e: Exception) { }
            return
        }
        val codec = audioDecoder ?: return
        try {
            val idx = codec.dequeueInputBuffer(5_000)
            if (idx >= 0) {
                codec.getInputBuffer(idx)!!.apply { clear(); put(data) }
                codec.queueInputBuffer(idx, 0, data.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, 0)
                if (outIdx < 0) break
                val pcm = ByteArray(info.size)
                codec.getOutputBuffer(outIdx)!!.get(pcm)
                audioTrack?.write(pcm, 0, pcm.size)
                codec.releaseOutputBuffer(outIdx, false)
            }
        } catch (e: Exception) { }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
        if (streamClient == null) startStreaming(holder, w, h)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        streamClient?.disconnect(); streamClient = null
        udpReceiver?.stop(); udpReceiver = null
        decoder?.run { try { stop(); release() } catch (e: Exception) { } }; decoder = null
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        audioDecoder?.run { try { stop(); release() } catch (e: Exception) { } }; audioDecoder = null
        decoderStarted = false; audioReady = false
        pendingSurface = null; surfaceW = 0; surfaceH = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        clipListener?.let { clipManager?.removePrimaryClipChangedListener(it) }
        streamClient?.disconnect()
    }
}
