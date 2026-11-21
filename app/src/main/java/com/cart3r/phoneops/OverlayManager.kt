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
    private val circleViews = mutableListOf<Pair<TextView, WindowManager.LayoutParams>>()
    private var controlView: View? = null
    private var controlParams: WindowManager.LayoutParams? = null

    private var points: MutableList<ClickPoint> = mutableListOf()
    private var isShowing: Boolean = false
    private var isRunning: Boolean = false

    fun isOverlayVisible(): Boolean = isShowing

    fun getCurrentPoints(): List<ClickPoint> = points.toList()

    fun showOverlay(
        context: Context,
        initialPoints: List<ClickPoint>,
        intervalMs: Long,
        maxCycles: Int?,
        maxDurationMs: Long?,
        jitterPx: Int,
        accentHex: Int,
        onPointsChanged: (List<ClickPoint>) -> Unit,
        onRunningChanged: (Boolean) -> Unit,
        onActionTick: (Int) -> Unit
    ) {
        if (!Settings.canDrawOverlays(context)) return
        hideOverlay()

        val appCtx = context.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val metrics = appCtx.resources.displayMetrics

        points = if (initialPoints.isNotEmpty()) {
            initialPoints.mapIndexed { idx, p ->
                ClickPoint(
                    id = idx + 1,
                    x = p.x.coerceIn(40f, (metrics.widthPixels - 40).toFloat()),
                    y = p.y.coerceIn(80f, (metrics.heightPixels - 80).toFloat()),
                    isSwipe = p.isSwipe,
                    endX = p.endX,
                    endY = p.endY,
                    swipeDurationMs = p.swipeDurationMs
                )
            }.toMutableList()
        } else {
            mutableListOf(ClickPoint(1, metrics.widthPixels / 2f, metrics.heightPixels / 2f))
        }

        val typeFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val circleSizePx = (48 * metrics.density).toInt()

        val statusLabel = TextView(appCtx).apply {
            text = "Punkte: ${points.size}"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(8, 0, 8, 0)
        }

        val toggleBtn = Button(appCtx).apply {
            text = "START"
            textSize = 11f
            setTextColor(Color.BLACK)
            setBackgroundColor(accentHex)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(16, 8, 16, 8)
        }

        val addPointBtn = Button(appCtx).apply {
            text = "+Pkt"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(34, 38, 52))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(14, 8, 14, 8)
        }

        val remPointBtn = Button(appCtx).apply {
            text = "-Pkt"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(34, 38, 52))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(14, 8, 14, 8)
        }

        val closeBtn = Button(appCtx).apply {
            text = "✕"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(58, 18, 24))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(14, 8, 14, 8)
        }

        fun rebuildCircles() {
            circleViews.forEach { (v, _) ->
                try {
                    wm.removeView(v)
                } catch (_: Exception) {
                }
            }
            circleViews.clear()

            points.forEachIndexed { index, pt ->
                val tv = TextView(appCtx).apply {
                    text = if (pt.isSwipe) "${pt.id}→" else "${pt.id}"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(145, 16, 18, 25))
                        setStroke((3 * metrics.density).toInt(), accentHex)
                    }
                }

                val p = WindowManager.LayoutParams(
                    circleSizePx,
                    circleSizePx,
                    typeFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (pt.x - circleSizePx / 2f).toInt()
                    y = (pt.y - circleSizePx / 2f).toInt()
                }

                var dragOffsetX = 0f
                var dragOffsetY = 0f

                tv.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            dragOffsetX = p.x - event.rawX
                            dragOffsetY = p.y - event.rawY
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val nx = (event.rawX + dragOffsetX).toInt()
                                .coerceIn(0, (metrics.widthPixels - circleSizePx).coerceAtLeast(0))
                            val ny = (event.rawY + dragOffsetY).toInt()
                                .coerceIn(0, (metrics.heightPixels - circleSizePx).coerceAtLeast(0))
                            p.x = nx
                            p.y = ny
                            val cx = nx + circleSizePx / 2f
                            val cy = ny + circleSizePx / 2f
                            val old = points[index]
                            points[index] = old.copy(x = cx, y = cy)
                            onPointsChanged(points.toList())
                            try {
                                wm.updateViewLayout(v, p)
                            } catch (_: Exception) {
                            }
                            true
                        }
                        else -> false
                    }
                }

                try {
                    wm.addView(tv, p)
                    circleViews.add(tv to p)
                } catch (_: Exception) {
                }
            }
            statusLabel.text = "Pkt:${points.size}"
        }

        addPointBtn.setOnClickListener {
            if (points.size >= 8) return@setOnClickListener
            val offset = points.size * 65f
            val nx = ((metrics.widthPixels / 2f) + offset).coerceIn(50f, (metrics.widthPixels - 50).toFloat())
            val ny = ((metrics.heightPixels / 2f) + offset).coerceIn(100f, (metrics.heightPixels - 100).toFloat())
            points.add(ClickPoint(points.size + 1, nx, ny))
            onPointsChanged(points.toList())
            rebuildCircles()
        }

        remPointBtn.setOnClickListener {
            if (points.size <= 1) return@setOnClickListener
            points.removeAt(points.lastIndex)
            onPointsChanged(points.toList())
            rebuildCircles()
        }

        val barLayout = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 8)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.argb(240, 14, 16, 22))
                setStroke((1.5f * metrics.density).toInt(), accentHex)
            }
            addView(statusLabel)
            addView(toggleBtn)
            addView(addPointBtn)
            addView(remPointBtn)
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
            x = (16 * metrics.density).toInt()
            y = (130 * metrics.density).toInt()
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
                        .coerceIn(0, (metrics.widthPixels - 280).coerceAtLeast(0))
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
            val svc = AutoTapService.instance
            if (svc == null) {
                statusLabel.text = "Service aus!"
                return@setOnClickListener
            }
            if (isRunning) {
                svc.stopAutoTap()
                isRunning = false
                toggleBtn.text = "START"
                statusLabel.text = "Pkt:${points.size}"
                onRunningChanged(false)
            } else {
                isRunning = true
                toggleBtn.text = "STOP"
                onRunningChanged(true)
                svc.startSequence(
                    points = points.toList(),
                    intervalMs = intervalMs,
                    maxCycles = maxCycles,
                    maxDurationMs = maxDurationMs,
                    jitterPx = jitterPx,
                    onStepTick = { total, idx ->
                        statusLabel.text = "#$total (P${idx + 1})"
                        onActionTick(total)
                    },
                    onFinished = {
                        isRunning = false
                        toggleBtn.text = "START"
                        statusLabel.text = "Pkt:${points.size}"
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
            wm.addView(barLayout, cParams)
            controlView = barLayout
            controlParams = cParams
            isShowing = true
            rebuildCircles()
        } catch (_: Exception) {
            hideOverlay()
        }
    }

    fun hideOverlay() {
        AutoTapService.instance?.stopAutoTap()
        isRunning = false
        val wm = windowManager
        circleViews.forEach { (v, _) ->
            try {
                wm?.removeView(v)
            } catch (_: Exception) {
            }
        }
        circleViews.clear()
        controlView?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        controlView = null
        controlParams = null
        isShowing = false
    }
}
