package com.cart3r.phoneops

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

data class OverlayConfigState(
    val points: List<ClickPoint>,
    val selectedIndex: Int,
    val intervalMs: Long,
    val maxCycles: Int?,
    val maxDurationMs: Long?,
    val jitterPx: Int,
    val accentHex: Int
)

object OverlayManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private val circleViews = mutableListOf<Pair<TextView, WindowManager.LayoutParams>>()
    private var controlView: View? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var settingsDialogView: View? = null
    private var settingsDialogParams: WindowManager.LayoutParams? = null
    private var swipeCaptureView: View? = null
    private var swipeCaptureParams: WindowManager.LayoutParams? = null

    private var config: OverlayConfigState? = null
    private var isShowing: Boolean = false
    private var isRunning: Boolean = false

    private var onStateSync: ((OverlayConfigState, Boolean, Int) -> Unit)? = null

    fun isOverlayVisible(): Boolean = isShowing

    private fun removeSettingsDialog() {
        val wm = windowManager
        settingsDialogView?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        settingsDialogView = null
        settingsDialogParams = null
    }

    private fun removeSwipeCapture() {
        val wm = windowManager
        swipeCaptureView?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        swipeCaptureView = null
        swipeCaptureParams = null
    }

    fun updateFromApp(
        context: Context,
        newPoints: List<ClickPoint>,
        selectedIndex: Int,
        intervalMs: Long,
        maxCycles: Int?,
        maxDurationMs: Long?,
        jitterPx: Int,
        accentHex: Int
    ) {
        val current = config ?: return
        val updated = current.copy(
            points = newPoints.ifEmpty { current.points },
            selectedIndex = selectedIndex.coerceIn(0, (newPoints.size - 1).coerceAtLeast(0)),
            intervalMs = intervalMs.coerceAtLeast(25L),
            maxCycles = maxCycles,
            maxDurationMs = maxDurationMs,
            jitterPx = jitterPx.coerceAtLeast(0),
            accentHex = accentHex
        )
        config = updated
        if (isShowing) {
            showOverlay(
                context = context,
                initialConfig = updated,
                onStateSync = onStateSync ?: { _, _, _ -> }
            )
        }
    }

    fun showOverlay(
        context: Context,
        initialConfig: OverlayConfigState,
        onStateSync: (OverlayConfigState, Boolean, Int) -> Unit
    ) {
        if (!Settings.canDrawOverlays(context)) return
        hideOverlay(stopService = true)

        this.onStateSync = onStateSync
        val appCtx = context.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val metrics = appCtx.resources.displayMetrics

        val sanitizedPoints = if (initialConfig.points.isNotEmpty()) {
            initialConfig.points.mapIndexed { idx, p ->
                ClickPoint(
                    id = idx + 1,
                    x = p.x.coerceIn(36f, (metrics.widthPixels - 36).toFloat()),
                    y = p.y.coerceIn(72f, (metrics.heightPixels - 72).toFloat()),
                    isSwipe = p.isSwipe,
                    endX = p.endX,
                    endY = p.endY,
                    swipeDurationMs = p.swipeDurationMs
                )
            }
        } else {
            listOf(ClickPoint(1, metrics.widthPixels / 2f, metrics.heightPixels / 2f))
        }

        val activeConfig = initialConfig.copy(
            points = sanitizedPoints,
            selectedIndex = initialConfig.selectedIndex.coerceIn(0, sanitizedPoints.lastIndex)
        )
        config = activeConfig

        val typeFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val circleSizePx = (48 * metrics.density).toInt()
        val accent = activeConfig.accentHex

        val statusLabel = TextView(appCtx).apply {
            text = "P${activeConfig.selectedIndex + 1}/${activeConfig.points.size}"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(8, 0, 8, 0)
        }

        val toggleBtn = Button(appCtx).apply {
            text = "START"
            textSize = 11f
            setTextColor(Color.BLACK)
            setBackgroundColor(accent)
            minWidth = 0
            minimumWidth = 0
            
