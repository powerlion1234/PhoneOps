package com.cart3r.phoneops

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class AutoTapService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var tapRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopAutoTap()
    }

    override fun onDestroy() {
        stopAutoTap()
        isServiceConnected = false
        instance = null
        super.onDestroy()
    }

    fun tapAt(x: Float, y: Float, durationMs: Long = 60L, onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(10L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onDone?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onDone?.invoke(false)
                }
            },
            null
        )
        if (!dispatched) onDone?.invoke(false)
    }

    fun startAutoTap(
        x: Float,
        y: Float,
        intervalMs: Long,
        maxCount: Int?,
        onTick: (Int) -> Unit,
        onFinished: () -> Unit
    ) {
        stopAutoTap()
        isRunningAutoTap = true
        var count = 0
        val safeInterval = intervalMs.coerceAtLeast(80L)

        val runner = object : Runnable {
            override fun run() {
                if (!isRunningAutoTap) return
                if (maxCount != null && count >= maxCount) {
                    stopAutoTap()
                    onFinished()
                    return
                }
                count += 1
                tapAt(x, y) {
                    handler.post { onTick(count) }
                }
                handler.postDelayed(this, safeInterval)
            }
        }
        tapRunnable = runner
        handler.post(runner)
    }

    fun stopAutoTap() {
        isRunningAutoTap = false
        tapRunnable?.let { handler.removeCallbacks(it) }
        tapRunnable = null
    }

    fun lockScreenNow(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        var instance: AutoTapService? = null
            private set

        @Volatile
        var isServiceConnected: Boolean = false
            private set

        @Volatile
        var isRunningAutoTap: Boolean = false
            private set
    }
}
