package com.secondscreen.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class TouchAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: TouchAccessibilityService? = null

        const val ACTION_DOWN = 0
        const val ACTION_MOVE = 1
        const val ACTION_UP = 2
    }

    private val gesturePath = Path()
    private var gestureStarted = false
    private var gestureStartTime = 0L

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); instance = null }

    fun handleTouch(action: Int, x: Float, y: Float) {
        when (action) {
            ACTION_DOWN -> {
                gesturePath.reset()
                gesturePath.moveTo(x, y)
                gestureStartTime = SystemClock.uptimeMillis()
                gestureStarted = true
            }
            ACTION_MOVE -> {
                if (gestureStarted) gesturePath.lineTo(x, y)
            }
            ACTION_UP -> {
                if (!gestureStarted) return
                gestureStarted = false
                gesturePath.lineTo(x, y)
                val duration = maxOf(SystemClock.uptimeMillis() - gestureStartTime, 1L)
                val stroke = GestureDescription.StrokeDescription(Path(gesturePath), 0, duration)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, null, null)
            }
        }
    }
}
