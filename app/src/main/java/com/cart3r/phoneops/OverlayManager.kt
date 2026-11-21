package com.cart3r.phoneops

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object OverlayManager {

    private var windowManager: WindowManager? = null
    private var targetView: View? = null
    private var controlView: View? = null

    private var targetParams: WindowManager.LayoutParams? = null
    private var controlParams: WindowManager.LayoutParams? = null

    private var currentX: Float = 540f
    private var currentY: Float = 1100f
    private var isShowing: Boolean = false
    private var isRunning: Boolean = false

    fun isOverlayVisible(): Boolean = isShowing

    fun getTargetCoordinates(): Pair<Float, Float> = currentX to currentY

    fun showOverlay(
        context: Context,
        initialX: Float,
        initialY: Float,
        intervalMs: Long,
        maxCount: Int?,
        maxDurationMs: Long?,
        accentHex: Int,
        onCoordinatesChanged: (Float, Float) -> Unit,
        onRunningChanged: (Boolean) -> Unit,
        onTapTick: (Int) -> Unit
    ) {
        if (!Settings.canDrawOverlays(context)) return
        hideOverlay()

        val appCtx = context.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val metrics = appCtx.resources.displayMetrics
        currentX = initialX.coerceIn(40f, (metrics.widthPixels - 40).toFloat())
        currentY = initialY.coerceIn(80f, (metrics.heightPixels - 80).toFloat())

        val typeFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val circleSizePx = (52 * metrics.density).toInt()

        val circleView = TextView(appCtx).apply {
            text = "1"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(120, 15, 17, 22))
                setStroke((3 * metrics.density).toInt(), accentHex)
            }
        }

        val tParams = WindowManager.LayoutParams(
            circleSizePx,
            circleSizePx,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (currentX - circleSizePx / 2f).toInt()
            y = (currentY - circleSizePx / 2f).toInt()
        }

        var dragOffsetX = 0f
        var dragOffsetY = 0f

        circleView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragOffsetX = tParams.x - event.rawX
                    dragOffsetY = tParams.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (event.rawX + dragOffsetX).toInt()
                        .coerceIn(0, (metrics.widthPixels - circleSizePx).coerceAtLeast(0))
                    val ny = (event.rawY + dragOffsetY).toInt()
                        .coerceIn(0, (metrics.heightPixels - circleSizePx).coerceAtLeast(0))
                    tParams.x = nx
                    tParams.y = ny
                    currentX = (nx + circleSizePx / 2f)
                    currentY = (ny + circleSizePx / 2f)
                    onCoordinatesChanged(currentX, currentY)
                    try {
                        wm.updateViewLayout(v, tParams)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        val statusLabel = TextView(appCtx).apply {
            text = "X:${currentX.toInt()} Y:${currentY.toInt()}"
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(8, 0, 10, 0)
        }

        val toggleBtn = Button(appCtx).apply {
            text = "START"
            textSize = 12f
            setTextColor(Color.BLACK)
            setBackgroundColor(accentHex)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(20, 10, 20, 10)
        }

        val closeBtn = Button(appCtx).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(42, 18, 22))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(16, 10, 16, 10)
        }

        val barLayout = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 10, 12, 10)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.argb(235, 14, 16, 22))
                setStroke((1.5f * metrics.density).toInt(), accentHex)
            }
            addView(statusLabel)
            addView(toggleBtn)
            addView(closeBtn)
        }

        val cParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * metrics.density).toInt()
            y = (140 * metrics.density).toInt()
        }

        var barOffsetX = 0f
        var barOffsetY = 0f
        barLayout.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    barOffsetX = cParams.x - event.rawX
                    barOffsetY = cParams.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    cParams.x = (event.rawX + barOffsetX).toInt()
                        .coerceIn(0, (metrics.widthPixels - 240).coerceAtLeast(0))
                    cParams.y = (event.rawY + barOffsetY).toInt()
                        .coerceIn(0, (metrics.heightPixels - 120).coerceAtLeast(0))
                    try {
                        wm.updateViewLayout(v, cParams)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        toggleBtn.setOnClickListener {
            val svc = AutoTapService.instance ?: return@setOnClickListener
            if (isRunning) {
                svc.stopAutoTap()
                isRunning = false
                toggleBtn.text = "START"
                onRunningChanged(false)
            } else {
                isRunning = true
                toggleBtn.text = "STOP"
                onRunningChanged(true)
                svc.startAutoTap(
                    x = currentX,
                    y = currentY,
                    intervalMs = intervalMs,
                    maxCount = maxCount,
                    maxDurationMs = maxDurationMs,
                    onTick = { tick ->
                        statusLabel.text = "#$tick (${currentX.toInt()},${currentY.toInt()})"
                        onTapTick(tick)
                    },
                    onFinished = {
                        isRunning = false
                        toggleBtn.text = "START"
                        statusLabel.text = "X:${currentX.toInt()} Y:${currentY.toInt()}"
                        onRunningChanged(false)
                    }
                )
            }
        }

        closeBtn.setOnClickListener {
            hideOverlay()
            onRunningChanged(false)
        }

        try {
            wm.addView(circleView, tParams)
            wm.addView(barLayout, cParams)
            targetView = circleView
            controlView = barLayout
            targetParams = tParams
            controlParams = cParams
            isShowing = true
        } catch (_: Exception) {
            hideOverlay()
        }
    }

    fun hideOverlay() {
        AutoTapService.instance?.stopAutoTap()
        isRunning = false
        val wm = windowManager
        targetView?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        controlView?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        targetView = null
        controlView = null
        targetParams = null
        controlParams = null
        isShowing = false
    }
}
