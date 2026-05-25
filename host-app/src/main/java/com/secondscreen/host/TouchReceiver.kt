package com.secondscreen.host

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Принимает touch-события от клиента по UDP и симулирует их на хосте.
 *
 * Протокол UDP (12 байт):
 * [1 байт action][4 байта x float][4 байта y float][1 байт pointerCount][1 байт padding]
 *
 * Action: 0=DOWN, 1=MOVE, 2=UP
 *
 * ВАЖНО: для полной симуляции касаний нужен root или AccessibilityService.
 * Без root используется только API доступности для навигации.
 * Для root-устройств: используйте /dev/input/event* через InputManager.injectInputEvent.
 */
class TouchReceiver(private val port: Int) {
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        socket = DatagramSocket(port)
        scope.launch { receiveLoop() }
    }

    private suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(64)
        val packet = DatagramPacket(buf, buf.size)

        while (isActive) {
            try {
                socket?.receive(packet)
                processTouch(packet.data)
            } catch (e: Exception) {
                if (isActive) e.printStackTrace()
            }
        }
    }

    private fun processTouch(data: ByteArray) {
        if (data.size < 10) return

        val action = data[0].toInt() and 0xFF
        val x = bytesToFloat(data, 1)
        val y = bytesToFloat(data, 5)

        // Логируем событие (в production — инжектируем в систему)
        val actionName = when (action) {
            0 -> "DOWN"
            1 -> "MOVE"
            2 -> "UP"
            else -> "UNKNOWN"
        }

        android.util.Log.d("TouchReceiver", "Touch $actionName at ($x, $y)")

        // -----------------------------------------------------------
        // Для инжекции без root: используйте Accessibility Service
        // Для root: раскомментируйте код ниже
        // -----------------------------------------------------------
        // injectTouchEvent(action, x, y)
    }

    // Только для root-устройств через reflection
    @Suppress("unused")
    private fun injectTouchEvent(action: Int, x: Float, y: Float) {
        try {
            val motionAction = when (action) {
                0 -> MotionEvent.ACTION_DOWN
                1 -> MotionEvent.ACTION_MOVE
                2 -> MotionEvent.ACTION_UP
                else -> return
            }

            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(
                now, now, motionAction, x, y, 0
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }

            // Через InputManager (требует root/signature permission)
            val inputManagerClass = Class.forName("android.hardware.input.InputManager")
            val instanceMethod = inputManagerClass.getMethod("getInstance")
            val instance = instanceMethod.invoke(null)
            val injectMethod = inputManagerClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.java
            )
            injectMethod.invoke(instance, event, 0)
            event.recycle()

        } catch (e: Exception) {
            android.util.Log.w("TouchReceiver", "Inject failed (no root?): ${e.message}")
        }
    }

    private fun bytesToFloat(data: ByteArray, offset: Int): Float {
        val bits = ((data[offset].toInt() and 0xFF) shl 24) or
                   ((data[offset + 1].toInt() and 0xFF) shl 16) or
                   ((data[offset + 2].toInt() and 0xFF) shl 8) or
                   (data[offset + 3].toInt() and 0xFF)
        return java.lang.Float.intBitsToFloat(bits)
    }

    fun stop() {
        scope.cancel()
        socket?.close()
    }
}
