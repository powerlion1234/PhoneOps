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
            minHeight = 0
            minimumHeight = 0
            setPadding(14, 8, 14, 8)
        }

        val gearBtn = Button(appCtx).apply {
            text = "⚙"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(34, 38, 52))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(12, 8, 12, 8)
        }

        val closeBtn = Button(appCtx).apply {
            text = "✕"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(62, 20, 26))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(12, 8, 12, 8)
        }

        fun pushSync(actionCount: Int = 0) {
            val snap = config ?: return
            mainHandler.post { onStateSync(snap, isRunning, actionCount) }
        }

        fun rebuildCircles() {
            circleViews.forEach { (v, _) ->
                try {
                    wm.removeView(v)
                } catch (_: Exception) {
                }
            }
            circleViews.clear()

            val snap = config ?: return
            snap.points.forEachIndexed { index, pt ->
                val isSelected = index == snap.selectedIndex
                val tv = TextView(appCtx).apply {
                    text = if (pt.isSwipe) "${pt.id}↔" else "${pt.id}"
                    setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (isSelected) accent else Color.argb(155, 16, 18, 25))
                        setStroke((3 * metrics.density).toInt(), accent)
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
                var moved = false

                tv.setOnTouchListener { v, event ->
                    val curCfg = config ?: return@setOnTouchListener false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            moved = false
                            dragOffsetX = p.x - event.rawX
                            dragOffsetY = p.y - event.rawY
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            moved = true
                            val nx = (event.rawX + dragOffsetX).toInt()
                                .coerceIn(0, (metrics.widthPixels - circleSizePx).coerceAtLeast(0))
                            val ny = (event.rawY + dragOffsetY).toInt()
                                .coerceIn(0, (metrics.heightPixels - circleSizePx).coerceAtLeast(0))
                            p.x = nx
                            p.y = ny
                            val cx = nx + circleSizePx / 2f
                            val cy = ny + circleSizePx / 2f
                            val mutable = curCfg.points.toMutableList()
                            if (index in mutable.indices) {
                                mutable[index] = mutable[index].copy(x = cx, y = cy)
                                config = curCfg.copy(points = mutable, selectedIndex = index)
                                pushSync()
                            }
                            try {
                                wm.updateViewLayout(v, p)
                            } catch (_: Exception) {
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) {
                                config = curCfg.copy(selectedIndex = index)
                                statusLabel.text = "P${index + 1}/${curCfg.points.size}"
                                rebuildCircles()
                                pushSync()
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
            val currentSnap = config ?: return
            statusLabel.text = "P${currentSnap.selectedIndex + 1}/${currentSnap.points.size}"
        }

        fun startSwipeRecording() {
            val curCfg = config ?: return
            val idx = curCfg.selectedIndex.coerceIn(0, curCfg.points.lastIndex)
            removeSettingsDialog()
            removeSwipeCapture()

            var downX = 0f
            var downY = 0f

            val hintBox = TextView(appCtx).apply {
                text = "Jetzt auf dem Bildschirm wischen (Start -> Ziel)..."
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(24, 24, 24, 24)
                setBackgroundColor(Color.argb(140, 10, 12, 18))
                setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = ev.rawX
                            downY = ev.rawY
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val upX = ev.rawX
                            val upY = ev.rawY
                            val mutable = curCfg.points.toMutableList()
                            if (idx in mutable.indices) {
                                mutable[idx] = mutable[idx].copy(
                                    x = downX,
                                    y = downY,
                                    isSwipe = true,
                                    endX = upX,
                                    endY = upY,
                                    swipeDurationMs = 240L
                                )
                                config = curCfg.copy(points = mutable)
                                removeSwipeCapture()
                                rebuildCircles()
                                pushSync()
                            }
                            true
                        }
                        else -> true
                    }
                }
            }

            val sParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                typeFlag,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            try {
                wm.addView(hintBox, sParams)
                swipeCaptureView = hintBox
                swipeCaptureParams = sParams
            } catch (_: Exception) {
                removeSwipeCapture()
            }
        }

        fun openOverlaySettings() {
            if (settingsDialogView != null) {
                removeSettingsDialog()
                return
            }
            val curCfg = config ?: return
            val idx = curCfg.selectedIndex.coerceIn(0, curCfg.points.lastIndex)
            val pt = curCfg.points[idx]

            val panel = LinearLayout(appCtx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(22, 18, 22, 18)
                background = GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(Color.argb(245, 15, 17, 24))
                    setStroke((2 * metrics.density).toInt(), accent)
                }
            }

            val titleTv = TextView(appCtx).apply {
                text = "Overlay-Einstellungen • Punkt ${idx + 1}"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 0, 0, 10)
            }
            panel.addView(titleTv)

            val intervalTv = TextView(appCtx).apply {
                text = "Intervall: ${curCfg.intervalMs} ms"
                setTextColor(Color.LTGRAY)
                textSize = 12f
                setPadding(0, 0, 0, 6)
            }
            panel.addView(intervalTv)

            val intervalRow = LinearLayout(appCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 10)
            }
            listOf(100L to "100ms", 500L to "500ms", 1000L to "1s", 3000L to "3s").forEach { (ms, label) ->
                val b = Button(appCtx).apply {
                    text = label
                    textSize = 11f
                    setTextColor(if (curCfg.intervalMs == ms) Color.BLACK else Color.WHITE)
                    setBackgroundColor(if (curCfg.intervalMs == ms) accent else Color.rgb(34, 38, 52))
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(12, 8, 12, 8)
                    setOnClickListener {
                        val latest = config ?: return@setOnClickListener
                        config = latest.copy(intervalMs = ms)
                        removeSettingsDialog()
                        openOverlaySettings()
                        pushSync()
                    }
                }
                intervalRow.addView(b)
            }
            panel.addView(intervalRow)

            val modeRow = LinearLayout(appCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 10)
            }
            val tapModeBtn = Button(appCtx).apply {
                text = if (pt.isSwipe) "Zu Tap wechseln" else "Tap aktiv"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(34, 38, 52))
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    val latest = config ?: return@setOnClickListener
                    val mutable = latest.points.toMutableList()
                    mutable[idx] = mutable[idx].copy(isSwipe = false)
                    config = latest.copy(points = mutable)
                    rebuildCircles()
                    removeSettingsDialog()
                    openOverlaySettings()
                    pushSync()
                }
            }
            val recordSwipeBtn = Button(appCtx).apply {
                text = "Selbst swipen"
                textSize = 11f
                setTextColor(Color.BLACK)
                setBackgroundColor(accent)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(14, 8, 14, 8)
                setOnClickListener { startSwipeRecording() }
            }
            modeRow.addView(tapModeBtn)
            modeRow.addView(recordSwipeBtn)
            panel.addView(modeRow)

            val dirRow = LinearLayout(appCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 12)
            }
            fun applyDirection(dx: Float, dy: Float) {
                val latest = config ?: return
                val mutable = latest.points.toMutableList()
                val curP = mutable[idx]
                mutable[idx] = curP.copy(
                    isSwipe = true,
                    endX = (curP.x + dx).coerceIn(20f, (metrics.widthPixels - 20).toFloat()),
                    endY = (curP.y + dy).coerceIn(40f, (metrics.heightPixels - 40).toFloat()),
                    swipeDurationMs = 240L
                )
                config = latest.copy(points = mutable)
                rebuildCircles()
                pushSync()
            }
            listOf(
                "↑ Hoch" to (0f to -450f),
                "↓ Runter" to (0f to 450f),
                "← Links" to (-350f to 0f),
                "→ Rechts" to (350f to 0f)
            ).forEach { (lbl, delta) ->
                val db = Button(appCtx).apply {
                    text = lbl
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(42, 46, 62))
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(10, 8, 10, 8)
                    setOnClickListener { applyDirection(delta.first, delta.second) }
                }
                dirRow.addView(db)
            }
            panel.addView(dirRow)

            val bottomRow = LinearLayout(appCtx).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val addBtn = Button(appCtx).apply {
                text = "+ Punkt"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(34, 38, 52))
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    val latest = config ?: return@setOnClickListener
                    if (latest.points.size >= 8) return@setOnClickListener
                    val nextId = latest.points.size + 1
                    val updatedList = latest.points + ClickPoint(
                        id = nextId,
                        x = metrics.widthPixels / 2f,
                        y = (metrics.heightPixels / 2f) + nextId * 70f
                    )
                    config = latest.copy(points = updatedList, selectedIndex = updatedList.lastIndex)
                    rebuildCircles()
                    removeSettingsDialog()
                    openOverlaySettings()
                    pushSync()
                }
            }
            val doneBtn = Button(appCtx).apply {
                text = "Fertig"
                textSize = 11f
                setTextColor(Color.BLACK)
                setBackgroundColor(accent)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(16, 8, 16, 8)
                setOnClickListener { removeSettingsDialog() }
            }
            bottomRow.addView(addBtn)
            bottomRow.addView(doneBtn)
            panel.addView(bottomRow)

            val dParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                typeFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            try {
                wm.addView(panel, dParams)
                settingsDialogView = panel
                settingsDialogParams = dParams
            } catch (_: Exception) {
                removeSettingsDialog()
            }
        }

        val barLayout = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 8)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.argb(242, 14, 16, 22))
                setStroke((1.5f * metrics.density).toInt(), accent)
            }
            addView(statusLabel)
            addView(toggleBtn)
            addView(gearBtn)
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
            val svc = AutoTapService.instance
            val latest = config ?: return@setOnClickListener
            if (svc == null) {
                statusLabel.text = "Service aus!"
                return@setOnClickListener
            }
            if (isRunning) {
                svc.stopAutoTap()
                isRunning = false
                toggleBtn.text = "START"
                statusLabel.text = "P${latest.selectedIndex + 1}/${latest.points.size}"
                pushSync()
            } else {
                isRunning = true
                toggleBtn.text = "STOP"
                pushSync(0)
                svc.startSequence(
                    points = latest.points,
                    intervalMs = latest.intervalMs,
                    maxCycles = latest.maxCycles,
                    maxDurationMs = latest.maxDurationMs,
                    jitterPx = latest.jitterPx,
                    onStepTick = { total, idx ->
                        statusLabel.text = "#$total P${idx + 1}"
                        pushSync(total)
                    },
                    onFinished = {
                        isRunning = false
                        toggleBtn.text = "START"
                        statusLabel.text = "P${latest.selectedIndex + 1}/${latest.points.size}"
                        pushSync()
                    }
                )
            }
        }

        gearBtn.setOnClickListener { openOverlaySettings() }

        closeBtn.setOnClickListener {
            hideOverlay(stopService = true)
            pushSync()
        }

        try {
            wm.addView(barLayout, cParams)
            controlView = barLayout
            controlParams = cParams
            isShowing = true
            rebuildCircles()
        } catch (_: Exception) {
            hideOverlay(stopService = true)
        }
    }

    fun hideOverlay(stopService: Boolean = true) {
        if (stopService) {
            AutoTapService.instance?.stopAutoTap()
        }
        isRunning = false
        val wm = windowManager
        removeSettingsDialog()
        removeSwipeCapture()
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
