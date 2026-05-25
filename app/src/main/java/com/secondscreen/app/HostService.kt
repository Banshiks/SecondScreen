package com.secondscreen.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class HostService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val STREAM_PORT = 8765
        private const val CHANNEL_ID = "ss_host"
        private const val NOTIF_ID = 1
        private const val MAX_DIM = 1920
        private const val AUDIO_RATE = 44100
        private fun align16(n: Int) = (n / 16) * 16
    }

    inner class LocalBinder : Binder() { fun get() = this@HostService }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    private var streamServer: StreamServer? = null
    private var udpSender: UdpVideoSender? = null
    private var nsdHelper: NsdHelper? = null
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var clipManager: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private var streamW = 1920
    private var streamH = 1080

    var onClientCount: ((Int) -> Unit)? = null

    private val projCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        startForeground(NOTIF_ID, buildNotification())
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)!!
        mediaProjection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(code, data)
        mediaProjection?.registerCallback(projCallback, mainHandler)
        nsdHelper = NsdHelper(this).also { it.registerService(STREAM_PORT) }
        udpSender = UdpVideoSender()
        streamServer = StreamServer(
            port = STREAM_PORT,
            onClientChange = { count -> onClientCount?.invoke(count) },
            onClientConnect = { ip, w, h -> udpSender?.setViewer(ip); onViewerConnected(w, h) },
            onTouchEvent = { action, x, y ->
                TouchAccessibilityService.instance?.handleTouch(action, x, y)
            },
            onClipboard = { text ->
                mainHandler.post {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("", text))
                }
            }
        )
        streamServer?.start()
        scope.launch { startCapture(streamW, streamH) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startClipboardSync()
        return START_NOT_STICKY
    }

    private fun onViewerConnected(viewerW: Int, viewerH: Int) {
        val w = align16(viewerW.coerceIn(320, MAX_DIM))
        val h = align16(viewerH.coerceIn(240, MAX_DIM))
        if (w == streamW && h == streamH) {
            videoCodec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            return
        }
        streamW = w; streamH = h
        scope.launch { startCapture(w, h) }
    }

    private suspend fun startCapture(w: Int, h: Int) {
        captureJob?.cancelAndJoin()
        videoCodec?.stop(); videoCodec?.release(); videoCodec = null
        virtualDisplay?.release(); virtualDisplay = null
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = videoCodec!!.createInputSurface()
        videoCodec!!.start()
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SecondScreen", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        captureJob = scope.launch { encodeLoop() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && audioRecord == null) {
            startAudioCapture()
        }
    }

    private suspend fun encodeLoop() = withContext(Dispatchers.IO) {
        val info = MediaCodec.BufferInfo()
        while (isActive) {
            val idx = videoCodec?.dequeueOutputBuffer(info, 10_000) ?: break
            if (idx >= 0) {
                val buf = videoCodec!!.getOutputBuffer(idx) ?: continue
                val data = ByteArray(info.size); buf.get(data)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    udpSender?.sendConfig(data)
                } else if (info.size > 0) {
                    udpSender?.sendFrame(data)
                }
                videoCodec!!.releaseOutputBuffer(idx, false)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        try {
            val mp = mediaProjection ?: return
            val audioCfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val minBuf = AudioRecord.getMinBufferSize(AUDIO_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(audioCfg)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setBufferSizeInBytes(minBuf * 4).build()
            val encFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 4)
            }
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start()
            }
            audioRecord!!.startRecording()
            scope.launch { audioLoop(minBuf) }
        } catch (e: Exception) { }
    }

    private suspend fun audioLoop(bufSize: Int) = withContext(Dispatchers.IO) {
        val pcm = ByteArray(bufSize)
        val info = MediaCodec.BufferInfo()
        while (isActive) {
            val read = audioRecord?.read(pcm, 0, bufSize) ?: break
            if (read > 0) {
                val inIdx = audioCodec?.dequeueInputBuffer(5_000) ?: continue
                if (inIdx >= 0) {
                    audioCodec!!.getInputBuffer(inIdx)!!.apply { clear(); put(pcm, 0, read) }
                    audioCodec!!.queueInputBuffer(inIdx, 0, read, System.nanoTime() / 1000, 0)
                }
            }
            val outIdx = audioCodec?.dequeueOutputBuffer(info, 0) ?: continue
            if (outIdx >= 0) {
                val data = ByteArray(info.size)
                audioCodec!!.getOutputBuffer(outIdx)!!.get(data)
                streamServer?.sendAudio(data, info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
                audioCodec!!.releaseOutputBuffer(outIdx, false)
            }
        }
    }

    private fun startClipboardSync() {
        clipManager = getSystemService(ClipboardManager::class.java)
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val text = clipManager?.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrEmpty()) streamServer?.sendClipboard(text)
            } catch (e: Exception) { }
        }
        clipManager?.addPrimaryClipChangedListener(clipListener!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        clipListener?.let { clipManager?.removePrimaryClipChangedListener(it) }
        nsdHelper?.unregisterService()
        mediaProjection?.unregisterCallback(projCallback)
        audioRecord?.stop(); audioRecord?.release()
        audioCodec?.stop(); audioCodec?.release()
        videoCodec?.stop(); videoCodec?.release()
        virtualDisplay?.release(); mediaProjection?.stop()
        udpSender?.stop(); streamServer?.stop()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Трансляция", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SecondScreen")
            .setContentText("Экран транслируется по Wi-Fi")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
}
