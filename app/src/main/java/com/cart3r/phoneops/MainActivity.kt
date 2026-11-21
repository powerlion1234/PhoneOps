package com.cart3r.phoneops

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val BgBlack = Color(0xFF060608)
private val CardBg = Color(0xFF0F1116)
private val SoftBg = Color(0xFF171A22)
private val TextLight = Color(0xFFF3F5F8)
private val TextDim = Color(0xFF8B93A3)
private val LineColor = Color(0xFF232734)

enum class AccentTheme(val label: String, val color: Color) {
    CRIMSON("Crimson", Color(0xFFFF1E38)),
    CYAN("Cyan", Color(0xFF00D8FF)),
    AMBER("Amber", Color(0xFFFFA000))
}

enum class StopMode(val label: String) {
    INFINITE("Endlos"),
    CYCLES("Zyklen"),
    DURATION("Sekunden")
}

sealed class MacroStep(val label: String) {
    data class WaitStep(val ms: Long) : MacroStep("Warten ${ms} ms")
    data class TapStep(val x: Float, val y: Float) : MacroStep("Klick (${x.toInt()},${y.toInt()})")
    data class SwipeStep(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ms: Long) :
        MacroStep("Wischen (${x1.toInt()},${y1.toInt()}) -> (${x2.toInt()},${y2.toInt()})")
    data class TorchStep(val enabled: Boolean) : MacroStep(if (enabled) "Taschenlampe AN" else "Taschenlampe AUS")
    data class SettingsStep(val title: String, val action: String) : MacroStep("Einstellung: $title")
    data object HomeStep : MacroStep("System: Home")
    data object BackStep : MacroStep("System: Zurück")
    data object LockScreenStep : MacroStep("System: Sperren")
}

data class CustomMacro(
    val id: String,
    val name: String,
    val repeatTimes: Int = 1,
    val steps: List<MacroStep>
)

data class AppEntry(val name: String, val packageName: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OverrideApp(activity = this)
        }
    }
}

@Composable
fun OverrideApp(activity: ComponentActivity) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var tab by remember { mutableIntStateOf(0) }
    var accentTheme by remember { mutableStateOf(AccentTheme.CRIMSON) }
    var compactCards by remember { mutableStateOf(false) }
    var keepScreenOn by remember { mutableStateOf(false) }

    var torchOn by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("Bereit") }
    var macroRunning by remember { mutableStateOf(false) }

    var points by remember {
        mutableStateOf(
            listOf(
                ClickPoint(1, 540f, 1100f),
                ClickPoint(2, 540f, 1450f, isSwipe = true, endX = 540f, endY = 850f, swipeDurationMs = 250L)
            )
        )
    }
    var selectedPointIdx by remember { mutableIntStateOf(0) }
    var intervalValue by remember { mutableStateOf("500") }
    var useSeconds by remember { mutableStateOf(false) }
    var stopMode by remember { mutableStateOf(StopMode.INFINITE) }
    var stopValue by remember { mutableStateOf("10") }
    var jitterPx by remember { mutableStateOf("0") }
    var actionCount by remember { mutableIntStateOf(0) }
    var overlayActive by remember { mutableStateOf(OverlayManager.isOverlayVisible()) }
    var autoTapRunning by remember { mutableStateOf(false) }

    var macros by remember {
        mutableStateOf(
            listOf(
                CustomMacro(
                    id = "m_1",
                    name = "Scroll & Tap Ablauf",
                    repeatTimes = 2,
                    steps = listOf(
                        MacroStep.SwipeStep(540f, 1500f, 540f, 700f, 250L),
                        MacroStep.WaitStep(600L),
                        MacroStep.TapStep(540f, 1100f),
                        MacroStep.WaitStep(500L)
                    )
                ),
                CustomMacro(
                    id = "m_2",
                    name = "Licht aus & Sperren",
                    repeatTimes = 1,
                    steps = listOf(
                        MacroStep.TorchStep(false),
                        MacroStep.WaitStep(800L),
                        MacroStep.LockScreenStep
                    )
                )
            )
        )
    }

    var editingMacroId by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftRepeat by remember { mutableStateOf("1") }
    var draftSteps by remember { mutableStateOf<List<MacroStep>>(emptyList()) }
    var stepType by remember { mutableStateOf("WAIT") }
    var stepValA by remember { mutableStateOf("600") }
    var stepValB by remember { mutableStateOf("1100") }
    var stepValC by remember { mutableStateOf("540") }
    var stepValD by remember { mutableStateOf("800") }

    var appQuery by remember { mutableStateOf("") }
    val userApps = remember { loadApps(context) }
    val filteredApps = remember(appQuery, userApps) {
        if (appQuery.isBlank()) userApps
        else userApps.filter {
            it.name.contains(appQuery, ignoreCase = true) ||
                it.packageName.contains(appQuery, ignoreCase = true)
        }
    }

    val accent = accentTheme.color
    val pad = if (compactCards) 10.dp else 14.dp
    val gap = if (compactCards) 8.dp else 12.dp

    LaunchedEffect(keepScreenOn) {
        if (keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun computeIntervalMs(): Long {
        val raw = intervalValue.toLongOrNull() ?: 500L
        return if (useSeconds) (raw * 1000L).coerceAtLeast(100L) else raw.coerceAtLeast(40L)
    }

    fun computeMaxCycles(): Int? =
        if (stopMode == StopMode.CYCLES) (stopValue.toIntOrNull() ?: 10).coerceAtLeast(1) else null

    fun computeMaxDurationMs(): Long? =
        if (stopMode == StopMode.DURATION) ((stopValue.toLongOrNull() ?: 10L) * 1000L).coerceAtLeast(500L) else null

    fun runMacro(macro: CustomMacro) {
        if (macroRunning) {
            statusMsg = "Macro läuft bereits"
            return
        }
        macroRunning = true
        val expanded = buildList {
            repeat(macro.repeatTimes.coerceAtLeast(1)) { addAll(macro.steps) }
        }
        if (expanded.isEmpty()) {
            macroRunning = false
            statusMsg = "Macro hat keine Schritte"
            return
        }

        fun runIdx(i: Int) {
            if (!macroRunning || i >= expanded.size) {
                macroRunning = false
                statusMsg = "Fertig: ${macro.name}"
                return
            }
            when (val st = expanded[i]) {
                is MacroStep.WaitStep -> {
                    statusMsg = st.label
                    mainHandler.postDelayed({ runIdx(i + 1) }, st.ms.coerceAtLeast(40L))
                }
                is MacroStep.TapStep -> {
                    val svc = AutoTapService.instance
                    if (svc == null) {
                        macroRunning = false
                        statusMsg = "Service deaktiviert"
                        openAccessibilitySettings(context)
                    } else {
                        svc.performPointGesture(ClickPoint(0, st.x, st.y, isSwipe = false)) {
                            mainHandler.post {
                                statusMsg = st.label
                                runIdx(i + 1)
                            }
                        }
                    }
                }
                is MacroStep.SwipeStep -> {
                    val svc = AutoTapService.instance
                    if (svc == null) {
                        macroRunning = false
                        statusMsg = "Service deaktiviert"
                        openAccessibilitySettings(context)
                    } else {
                        svc.performPointGesture(
                            ClickPoint(0, st.x1, st.y1, isSwipe = true, endX = st.x2, endY = st.y2, swipeDurationMs = st.ms)
                        ) {
                            mainHandler.post {
                                statusMsg = st.label
                                runIdx(i + 1)
                            }
                        }
                    }
                }
                is MacroStep.TorchStep -> {
                    if (setTorchState(context, st.enabled)) torchOn = st.enabled
                    statusMsg = st.label
                    mainHandler.postDelayed({ runIdx(i + 1) }, 150L)
                }
                is MacroStep.SettingsStep -> {
                    openSettingsAction(context, st.action)
                    statusMsg = st.label
                    mainHandler.postDelayed({ runIdx(i + 1) }, 250L)
                }
                is MacroStep.HomeStep -> {
                    AutoTapService.instance?.goHomeNow()
                    statusMsg = st.label
                    mainHandler.postDelayed({ runIdx(i + 1) }, 200L)
                }
                is MacroStep.BackStep -> {
                    AutoTapService.instance?.goBackNow()
                    statusMsg = st.label
                    mainHandler.postDelayed({ runIdx(i + 1) }, 200L)
                }
                is MacroStep.LockScreenStep -> {
                    AutoTapService.instance?.lockScreenNow()
                    macroRunning = false
                    statusMsg = st.label
                }
            }
        }
        runIdx(0)
    
