package com.secondscreen.host

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

class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIF_ID = 1

        const val STREAM_WIDTH = 1280
        const val STREAM_HEIGHT = 720
        const val STREAM_FPS = 30
        const val STREAM_BITRATE = 2_000_000
        const val WS_PORT = 8765
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var streamingServer: StreamingServer? = null
    private var touchReceiver: TouchReceiver? = null

    var onClientConnected: ((Int) -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)!!

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        // Обязательно для Android 14+
        mediaProjection?.registerCallback(projectionCallback, mainHandler)

        serviceScope.launch {
            startStreaming()
        }

        return START_STICKY
    }

    private suspend fun startStreaming() {
        streamingServer = StreamingServer(WS_PORT) { clientCount ->
            onClientConnected?.invoke(clientCount)
        }
        streamingServer?.start()

        touchReceiver = TouchReceiver(WS_PORT + 1)
        touchReceiver?.start()

        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        val density = metrics.densityDpi

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, STREAM_WIDTH, STREAM_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, STREAM_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, STREAM_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val inputSurface = mediaCodec!!.createInputSurface()
        mediaCodec!!.start()

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SecondScreenCapture",
            STREAM_WIDTH, STREAM_HEIGHT, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        encodeLoop()
    }

    private suspend fun encodeLoop() = withContext(Dispatchers.IO) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isActive) {
            val outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10_000) ?: break

            if (outputIndex >= 0) {
                val outputBuffer = mediaCodec!!.getOutputBuffer(outputIndex) ?: continue

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    val spsData = ByteArray(bufferInfo.size)
                    outputBuffer.get(spsData)
                    streamingServer?.sendSpsData(spsData)
                } else if (bufferInfo.size > 0) {
                    val frameData = ByteArray(bufferInfo.size)
                    outputBuffer.get(frameData)
                    val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    streamingServer?.sendFrame(frameData, isKeyFrame)
                }

                mediaCodec!!.releaseOutputBuffer(outputIndex, false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaCodec?.stop()
        mediaCodec?.release()
        virtualDisplay?.release()
        mediaProjection?.stop()
        streamingServer?.stop()
        touchReceiver?.stop()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SecondScreen активен")
            .setContentText("Стриминг экрана на Nothing Phone")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
}
