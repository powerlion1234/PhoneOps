package com.cart3r.phoneops

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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
    val swipeDurationMs: Long = 220L
)

class AutoTapService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var loopRunnable: Runnable? = null

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
            point.swipeDurationMs.coerceIn(70L, 2500L)
        } else {
            tapDurationMs.coerceIn(15L, 400L)
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
        onFinished: () -> Unit
    ) {
        stopAutoTap()
        if (points.isEmpty()) {
            onFinished()
            return
        }

        isRunningAutoTap = true
        var totalActions = 0
        var completedCycles = 0
        var pointIdx = 0
        val startTime = System.currentTimeMillis()
        val safeInterval = intervalMs.coerceAtLeast(25L)

        val runner = object : Runnable {
            override fun run() {
                if (!isRunningAutoTap) return
                val elapsed = System.currentTimeMillis() - startTime
                if (maxCycles != null && completedCycles >= maxCycles) {
                    stopAutoTap()
                    handler.post(onFinished)
                    return
                }
                if (maxDurationMs != null && elapsed >= maxDurationMs) {
                    stopAutoTap()
                    handler.post(onFinished)
                    return
                }

                val currentPoint = points[pointIdx]
                totalActions += 1
                val stepIndexForUi = pointIdx
                performPointGesture(currentPoint, jitterPx = jitterPx) { _ ->
                    handler.post {
                        if (isRunningAutoTap) onStepTick(totalActions, stepIndexForUi)
                    }
                }

                pointIdx += 1
                if (pointIdx >= points.size) {
                    pointIdx = 0
                    completedCycles += 1
                }

                if (maxCycles != null && completedCycles >= maxCycles) {
                    stopAutoTap()
                    handler.post(onFinished)
                    return
                }

                handler.postDelayed(this, safeInterval)
            }
        }
        loopRunnable = runner
        handler.post(runner)
    }

    fun stopAutoTap() {
        isRunningAutoTap = false
        loopRunnable?.let { handler.removeCallbacks(it) }
        loopRunnable = null
    }

    fun lockScreenNow(): Boolean = try {
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
