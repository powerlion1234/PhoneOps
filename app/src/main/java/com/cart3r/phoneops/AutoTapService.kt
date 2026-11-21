package com.cart3r.phoneops

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

data class ClickPoint(
    val id: Int,
    val x: Float,
    val y: Float,
    val isSwipe: Boolean = false,
    val endX: Float? = null,
    val endY: Float? = null,
    val swipeDurationMs: Long = 200L
)

class AutoTapService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var stepRunnable: Runnable? = null
    private var onStateChange: ((running: Boolean, reason: String?) -> Unit)? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                stopAutoTap(reason = "Bildschirm aus — gestoppt")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceConnected = true
        registerScreenReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopAutoTap(reason = "Dienst unterbrochen")
    }

    override fun onDestroy() {
        stopAutoTap(reason = "Dienst beendet")
        unregisterScreenReceiverSafe()
        isServiceConnected = false
        instance = null
        super.onDestroy()
    }

    private fun registerScreenReceiver() {
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    private fun unregisterScreenReceiverSafe() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
    }

    fun setStateListener(listener: ((running: Boolean, reason: String?) -> Unit)?) {
        onStateChange = listener
    }

    fun performPointGesture(
        point: ClickPoint,
        tapDurationMs: Long = 35L,
        jitterPx: Int = 0,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        val jx = if (jitterPx > 0) (-jitterPx..jitterPx).random().toFloat() else 0f
        val jy = if (jitterPx > 0) (-jitterPx..jitterPx).random().toFloat() else 0f
        val sx = (point.x + jx).coerceAtLeast(1f)
        val sy = (point.y + jy).coerceAtLeast(1f)

        val path = Path().apply {
            moveTo(sx, sy)
            if (point.isSwipe && point.endX != null && point.endY != null) {
                val ex = (point.endX + jx).coerceAtLeast(1f)
                val ey = (point.endY + jy).coerceAtLeast(1f)
                lineTo(ex, ey)
            }
        }

        val duration = if (point.isSwipe) {
            point.swipeDurationMs.coerceIn(70L, 2000L)
        } else {
            tapDurationMs.coerceIn(15L, 250L)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
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

    fun startSequence(
        points: List<ClickPoint>,
        intervalMs: Long,
        maxCycles: Int?,
        maxDurationMs: Long?,
        jitterPx: Int = 0,
        onStepTick: (totalActions: Int, activePointIndex: Int) -> Unit,
        onFinished: (reason: String) -> Unit
    ) {
        stopAutoTap(reason = null)
        if (points.isEmpty()) {
            handler.post { onFinished("Keine Punkte") }
            return
        }

        isRunningAutoTap = true
        var totalActions = 0
        var completedCycles = 0
        var pointIdx = 0
        val startTime = System.currentTimeMillis()
        val safeInterval = intervalMs.coerceAtLeast(30L)

        val tickRunner = object : Runnable {
            override fun run() {
                if (!isRunningAutoTap) return

                val elapsed = System.currentTimeMillis() - startTime
                if (maxCycles != null && completedCycles >= maxCycles) {
                    stopAutoTap(reason = null)
                    handler.post { onFinished("Zyklen beendet") }
                    return
                }
                if (maxDurationMs != null && elapsed >= maxDurationMs) {
                    stopAutoTap(reason = null)
                    handler.post { onFinished("Zeit abgelaufen") }
                    return
                }

                val currentPoint = points[pointIdx]
                val currentIdx = pointIdx
                totalActions += 1

                performPointGesture(currentPoint, jitterPx = jitterPx) { _ ->
                    handler.post {
                        if (isRunningAutoTap) onStepTick(totalActions, currentIdx)
                    }
                }

                pointIdx += 1
                if (pointIdx >= points.size) {
                    pointIdx = 0
                    completedCycles += 1
                }

                if (maxCycles != null && completedCycles >= maxCycles) {
                    stopAutoTap(reason = null)
                    handler.post { onFinished("Zyklen beendet") }
                    return
                }

                if (isRunningAutoTap) {
                    handler.postDelayed(this, safeInterval)
                }
            }
        }

        stepRunnable = tickRunner
        handler.post(tickRunner)
    }

    fun stopAutoTap(reason: String? = null) {
        val wasRunning = isRunningAutoTap
        isRunningAutoTap = false
        stepRunnable?.let { handler.removeCallbacks(it) }
        stepRunnable = null
        if (wasRunning && reason != null) {
            handler.post { onStateChange?.invoke(false, reason) }
        }
    }

    fun lockScreenNow(): Boolean = try {
        stopAutoTap(reason = null)
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    } catch (_: Exception) {
        false
    }

    fun goHomeNow(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_HOME)
    } catch (_: Exception) {
        false
    }

    fun goBackNow(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_BACK)
    } catch (_: Exception) {
        false
    }

    fun openRecentsNow(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    } catch (_: Exception) {
        false
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
