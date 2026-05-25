package com.secondscreen.client

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

/**
 * Аппаратный H.264 декодер на базе MediaCodec.
 * Декодированные кадры выводятся напрямую на Surface (нулевое копирование).
 */
class VideoDecoder(private val surface: Surface) {

    private var codec: MediaCodec? = null
    private var spsData: ByteArray? = null
    private var initialized = false

    companion object {
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val TIMEOUT_US = 10_000L
    }

    fun init() {
        // Создаём декодер без SPS/PPS (настроим при получении)
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    /**
     * Получаем SPS/PPS данные — теперь можем сконфигурировать декодер.
     */
    fun feedSpsData(data: ByteArray) {
        spsData = data
        configureDecoder(data)
    }

    private fun configureDecoder(spsData: ByteArray) {
        val codec = codec ?: return

        if (initialized) {
            codec.stop()
            initialized = false
        }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            // SPS/PPS как csd-0
            val csdBuffer = java.nio.ByteBuffer.wrap(spsData)
            setByteBuffer("csd-0", csdBuffer)
        }

        codec.configure(format, surface, null, 0)
        codec.start()
        initialized = true

        // Запускаем цикл отрисовки
        Thread { renderLoop() }.apply { isDaemon = true; start() }
    }

    /**
     * Подаём H.264 NALU на декодирование.
     */
    fun feedFrame(data: ByteArray, isKeyFrame: Boolean) {
        val codec = codec ?: return
        if (!initialized) return

        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex < 0) return

        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(data)

        val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        codec.queueInputBuffer(inputIndex, 0, data.size, System.nanoTime() / 1000, flags)
    }

    /**
     * Цикл вывода кадров на Surface.
     * Запускается в отдельном потоке.
     */
    private fun renderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        val codec = codec ?: return

        while (initialized) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

            when {
                outputIndex >= 0 -> {
                    // render=true — кадр отображается на Surface автоматически
                    codec.releaseOutputBuffer(outputIndex, true)
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    android.util.Log.d("VideoDecoder", "Format changed: $newFormat")
                }
            }
        }
    }

    fun release() {
        initialized = false
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
    }
}
