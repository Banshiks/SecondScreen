package com.secondscreen.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class HostService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val STREAM_PORT = 8765
        const val STREAM_WIDTH = 1280
        const val STREAM_HEIGHT = 720
        private const val CHANNEL_ID = "ss_host"
        private const val NOTIF_ID = 1
    }

    inner class LocalBinder : Binder() { fun get() = this@HostService }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var streamServer: StreamServer? = null
    private var nsdHelper: NsdHelper? = null

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

        scope.launch { startCapture() }
        return START_NOT_STICKY
    }

    private suspend fun startCapture() {
        streamServer = StreamServer(STREAM_PORT) { count -> onClientCount?.invoke(count) }
        streamServer?.start()

        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, STREAM_WIDTH, STREAM_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = mediaCodec!!.createInputSurface()
        mediaCodec!!.start()

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SecondScreen", STREAM_WIDTH, STREAM_HEIGHT, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        encodeLoop()
    }

    private suspend fun encodeLoop() = withContext(Dispatchers.IO) {
        val info = MediaCodec.BufferInfo()
        while (isActive) {
            val idx = mediaCodec?.dequeueOutputBuffer(info, 10_000) ?: break
            if (idx >= 0) {
                val buf = mediaCodec!!.getOutputBuffer(idx) ?: continue
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    val sps = ByteArray(info.size); buf.get(sps); streamServer?.sendSpsData(sps)
                } else if (info.size > 0) {
                    val frame = ByteArray(info.size); buf.get(frame)
                    streamServer?.sendFrame(frame, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }
                mediaCodec!!.releaseOutputBuffer(idx, false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        nsdHelper?.unregisterService()
        mediaProjection?.unregisterCallback(projCallback)
        mediaCodec?.stop(); mediaCodec?.release()
        virtualDisplay?.release()
        mediaProjection?.stop()
        streamServer?.stop()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Трансляция", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SecondScreen")
            .setContentText("Экран транслируется по Wi-Fi")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
}
