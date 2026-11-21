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
import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val BgBlack = Color(0xFF07080C)
private val CardBg = Color(0xFF12151F)
private val SoftBg = Color(0xFF1B202E)
private val TextLight = Color(0xFFF6F8FB)
private val TextDim = Color(0xFF9CA6B9)
private val LineColor = Color(0xFF2A3144)

enum class AccentTheme(val label: String, val color: Color) {
    CRIMSON("Crimson", Color(0xFFFF2844)),
    CYAN("Cyan", Color(0xFF00E0FF)),
    AMBER("Amber", Color(0xFFFFB000))
}

enum class StopMode(val label: String) {
    INFINITE("Endlos"),
    CYCLES("Zyklen"),
    DURATION("Sekunden")
}

sealed class MacroStep(val label: String) {
    data class WaitStep(val ms: Long) : MacroStep("Warten ${ms} ms")
    data class TapStep(val x: Float, val y: Float) : MacroStep("Klick (${x.toInt()}, ${y.toInt()})")
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

    override fun onStop() {
        super.onStop()
        if (!OverlayManager.isOverlayVisible() && AutoTapService.isRunningAutoTap) {
            AutoTapService.instance?.stopAutoTap(reason = "App verlassen — gestoppt")
        }
    }
}

@Composable
fun OverrideApp(activity: ComponentActivity) {
    val context = LocalContext.current
    val view = LocalView.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var tab by remember { mutableIntStateOf(0) }
    var accentTheme by remember { mutableStateOf(AccentTheme.CRIMSON) }
    var compactCards by remember { mutableStateOf(false) }
    var keepScreenOn by remember { mutableStateOf(false) }
    var hapticOn by remember { mutableStateOf(true) }

    var torchOn by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("Bereit") }
    var macroRunning by remember { mutableStateOf(false) }

    var points by remember {
        mutableStateOf(
            listOf(
                ClickPoint(1, 540f, 1100f),
                ClickPoint(2, 540f, 1450f, isSwipe = true, endX = 540f, endY = 900f, swipeDurationMs = 200L)
            )
        )
    }
    var selectedPointIdx by remember { mutableIntStateOf(0) }
    var intervalValue by remember { mutableStateOf("250") }
    var useSeconds by remember { mutableStateOf(false) }
    var stopMode by remember { mutableStateOf(StopMode.INFINITE) }
    var stopValue by remember { mutableStateOf("10") }
    var jitterPx by remember { mutableStateOf("0") }
    var actionCount by remember { mutableIntStateOf(0) }
    var overlayActive by remember { mutableStateOf(OverlayManager.isOverlayVisible()) }
    var autoTapRunning by remember { mutableStateOf(AutoTapService.isRunningAutoTap) }

    var macros by remember {
        mutableStateOf(
            listOf(
                CustomMacro(
                    id = "m_1",
                    name = "Scroll & Tap Ablauf",
                    repeatTimes = 2,
                    steps = listOf(
                        MacroStep.SwipeStep(540f, 1500f, 540f, 750f, 200L),
                        MacroStep.WaitStep(300L),
                        MacroStep.TapStep(540f, 1100f),
                        MacroStep.WaitStep(250L)
                    )
                ),
                CustomMacro(
                    id = "m_2",
                    name = "Licht aus & Sperren",
                    repeatTimes = 1,
                    steps = listOf(
                        MacroStep.TorchStep(false),
                        MacroStep.WaitStep(500L),
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
    var stepValA by remember { mutableStateOf("350") }
    var stepValB by remember { mutableStateOf("1100") }
    var stepValC by remember { mutableStateOf("540") }
    var stepValD by remember { mutableStateOf("800") }

    var appQuery by remember { mutableStateOf("") }
    val userApps = remember { loadApps(context) }
    val filteredApps = remember(appQuery, userApps) {
        if (appQuery.isBlank()) userApps
        else userApps.filter { app ->
            app.name.contains(appQuery, ignoreCase = true) ||
                app.packageName.contains(appQuery, ignoreCase = true)
        }
    }

    val accent = accentTheme.color
    val pad = if (compactCards) 12.dp else 16.dp
    val gap = if (compactCards) 10.dp else 14.dp

    fun buzz() {
        if (hapticOn) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(keepScreenOn) {
        if (keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun computeIntervalMs(): Long {
        val raw = intervalValue.toLongOrNull() ?: 250L
        return if (useSeconds) (raw * 1000L).coerceAtLeast(100L) else raw.coerceAtLeast(30L)
    }

    fun computeMaxCycles(): Int? =
        if (stopMode == StopMode.CYCLES) (stopValue.toIntOrNull() ?: 10).coerceAtLeast(1) else null

    fun computeMaxDurationMs(): Long? =
        if (stopMode == StopMode.DURATION) ((stopValue.toLongOrNull() ?: 10L) * 1000L).coerceAtLeast(500L) else null

    fun syncOverlayNow() {
        if (overlayActive) {
            OverlayManager.updateFromApp(
                context = context,
                newPoints = points,
                selectedIndex = selectedPointIdx,
                intervalMs = computeIntervalMs(),
                maxCycles = computeMaxCycles(),
                maxDurationMs = computeMaxDurationMs(),
                jitterPx = jitterPx.toIntOrNull() ?: 0,
                accentHex = accent.toArgb()
            )
        }
    }

    fun stopMacroExecution(msg: String = "Macro gestoppt") {
        macroRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        statusMsg = msg
    }

    fun runMacro(macro: CustomMacro) {
        if (macroRunning) {
            stopMacroExecution("Macro abgebrochen")
            return
        }
        buzz()
        macroRunning = true
        val expanded = buildList {
            repeat(macro.repeatTimes.coerceAtLeast(1)) { addAll(macro.steps) }
        }
        if (expanded.isEmpty()) {
            stopMacroExecution("Macro hat keine Schritte")
            return
        }

        fun runIdx(i: Int) {
            if (!macroRunning) return
            if (i >= expanded.size) {
                macroRunning = false
                statusMsg = "Fertig: ${macro.name}"
                return
            }
            when (val st = expanded[i]) {
                is MacroStep.WaitStep -> {
                    statusMsg = "${i + 1}/${expanded.size}: ${st.label}"
                    mainHandler.postDelayed({ runIdx(i + 1) }, st.ms.coerceAtLeast(30L))
                }
                is MacroStep.TapStep -> {
                    val svc = AutoTapService.instance
                    if (svc == null) {
                        stopMacroExecution("Service deaktiviert")
                        openAccessibilitySettings(context)
                    } else {
                        statusMsg = "${i + 1}/${expanded.size}: ${st.label}"
                        svc.performPointGesture(ClickPoint(0, st.x, st.y, isSwipe = false)) {
                            mainHandler.post { if (macroRunning) runIdx(i + 1) }
                        }
                    }
                }
                is MacroStep.SwipeStep -> {
                    val svc = AutoTapService.instance
                    if (svc == null) {
                        stopMacroExecution("Service deaktiviert")
                        openAccessibilitySettings(context)
                    } else {
                        statusMsg = "${i + 1}/${expanded.size}: ${st.label}"
                        svc.performPointGesture(
                            ClickPoint(0, st.x1, st.y1, isSwipe = true, endX = st.x2, endY = st.y2, swipeDurationMs = st.ms)
                        ) {
                            mainHandler.post { if (macroRunning) runIdx(i + 1) }
                        }
                    }
                }
                is MacroStep.TorchStep -> {
                    if (setTorchState(context, st.enabled)) torchOn = st.enabled
                    statusMsg = "${i + 1}/${expanded.size}: ${st.label}"
                    mainHandler.postDelayed({ runIdx(i + 1) }, 120L)
                }
                is MacroStep.SettingsStep -> {
                    openSettingsAction(context, st.action)
                    statusMsg = "${i + 1}/${expanded.size}: ${st.label}"
                    mainHandler.postDelayed({ runIdx(i + 1) }, 200L)
                }
                is MacroStep.HomeStep -> {
                    AutoTapService.instance?.goHomeNow()
                    statusMsg = "${i + 1}/${expanded.size}: ${st.label}"
                    mainHandler.postDelayed({ runIdx(i + 1) }, 180L)
                }
                is MacroStep.BackStep -> {
                    AutoTapService.instance?.goBackNow()
                    statusMsg = "${i + 1}/${expanded.size}: ${st.label}"
                    mainHandler.postDelayed({ runIdx(i + 1) }, 180L)
                }
                is MacroStep.LockScreenStep -> {
                    AutoTapService.instance?.lockScreenNow()
                    macroRunning = false
                    statusMsg = st.label
                }
            }
        }
        runIdx(0)
    }

    fun startEditor(macro: CustomMacro?) {
        if (macro != null) {
            editingMacroId = macro.id
            draftName = macro.name
            draftRepeat = macro.repeatTimes.toString()
            draftSteps = macro.steps
        } else {
            editingMacroId = "NEW_${System.currentTimeMillis()}"
            draftName = "Neues Macro ${macros.size + 1}"
            draftRepeat = "1"
            draftSteps = listOf(MacroStep.WaitStep(350L), MacroStep.TapStep(540f, 1100f))
        }
    }

    Scaffold(
        containerColor = BgBlack,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(11.dp).clip(CircleShape).background(accent))
                        Spacer(Modifier.width(10.dp))
                        Text("OVERRIDE", color = TextLight, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(
                        color = SoftBg,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                        modifier = Modifier.padding(start = 10.dp)
                    ) {
                        Text(
                            text = statusMsg,
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBg, RoundedCornerShape(14.dp))
                        .border(1.dp, LineColor, RoundedCornerShape(14.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Home", "Auto Tap", "Macros", "Tools & UI").forEachIndexed { idx, title ->
                        val active = tab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) accent else Color.Transparent)
                                .clickable {
                                    buzz()
                                    tab = idx
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                color = if (active) Color.Black else TextDim,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            when (tab) {
                0 -> {
                    val a11yOk = AutoTapService.isServiceConnected
                    val overlayOk = Settings.canDrawOverlays(context)
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        contentPadding = PaddingValues(bottom = 28.dp)
                    ) {
                        item {
                            SimpleCard(pad) {
                                Text("Steuerzentrale", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("Auto-Stopp bei Bildschirm aus & schnelles System-Menü.", color = TextDim, fontSize = 12.sp)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionPill(
                                        text = if (overlayOk) "Overlay: Aktiv" else "Overlay aus",
                                        active = overlayOk,
                                        accent = accent,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        buzz()
                                        openOverlaySettings(context)
                                    }
                                    ActionPill(
                                        text = if (a11yOk) "Service: Aktiv" else "Service aus",
                                        active = a11yOk,
                                        accent = accent,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        buzz()
                                        openAccessibilitySettings(context)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionPill(
                                        text = if (torchOn) "Licht: AN" else "Licht: AUS",
                                        active = torchOn,
                                        accent = accent,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        buzz()
                                        val next = !torchOn
                                        if (setTorchState(context, next)) {
                                            torchOn = next
                                            statusMsg = if (next) "Licht AN" else "Licht AUS"
                                        }
                                    }
                                    ActionPill(
                                        text = if (macroRunning) "Macro Stopp" else "Sperren",
                                        active = macroRunning,
                                        accent = accent,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        buzz()
                                        if (macroRunning) {
                                            stopMacroExecution()
                                        } else {
                                            AutoTapService.instance?.lockScreenNow()
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionPill("Home", false, accent, Modifier.weight(1f)) {
                                        buzz()
                                        AutoTapService.instance?.goHomeNow()
                                    }
                                    ActionPill("Zurück", false, accent, Modifier.weight(1f)) {
                                        buzz()
                                        AutoTapService.instance?.goBackNow()
                                    }
                                    ActionPill("Verlauf", false, accent, Modifier.weight(1f)) {
                                        buzz()
                                        AutoTapService.instance?.openRecentsNow()
                                    }
                                }
                            }
                        }

                        items(macros, key = { it.id }) { m ->
                            SimpleCard(pad) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                        Text(m.name, color = TextLight, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                        Text("${m.steps.size} Schritte • ${m.repeatTimes}x Wiederholung", color = TextDim, fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = { runMacro(m) },
                                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                                        shape = RoundedCornerShape(10.dp)
                                    ) { Text("Start", fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    val safeIdx = selectedPointIdx.coerceIn(0, (points.size - 1).coerceAtLeast(0))
                    val activePt = points[safeIdx]
                    val a11yOk = AutoTapService.isServiceConnected
                    val overlayOk = Settings.canDrawOverlays(context)

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        contentPadding = PaddingValues(bottom = 28.dp)
                    ) {
                        item {
                            SimpleCard(pad) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Auto-Clicker & Swipe", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                    Text("Aktionen: $actionCount", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                if (!a11yOk || !overlayOk) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Hinweis: Overlay & Bedienungshilfen (inkl. Eingeschränkte Einstellungen) aktivieren.",
                                        color = TextDim,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(Modifier.height(10.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    points.forEachIndexed { idx, pt ->
                                        val sel = idx == safeIdx
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (sel) accent else SoftBg)
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                                .clickable {
                                                    buzz()
                                                    selectedPointIdx = idx
                                                    syncOverlayNow()
                                                }
                                        ) {
                                            Text(
                                                text = "P${pt.id}${if (pt.isSwipe) "↔" else ""}",
                                                color = if (sel) Color.Black else TextLight,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    if (points.size < 8) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(SoftBg)
                                                .border(1.dp, accent, RoundedCornerShape(10.dp))
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                                .clickable {
                                                    buzz()
                                                    val nextId = points.size + 1
                                                    points = points + ClickPoint(nextId, 540f, 1000f + nextId * 90f)
                                                    selectedPointIdx = points.lastIndex
                                                    syncOverlayNow()
                                                }
                                        ) {
                                            Text("+ Punkt", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (points.size > 1) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(SoftBg)
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                                .clickable {
                                                    buzz()
                                                    points = points.dropLast(1)
                                                    selectedPointIdx = (points.size - 1).coerceAtLeast(0)
                                                    syncOverlayNow()
                                                }
                                        ) {
                                            Text("- Pkt", color = TextDim, fontSize = 12.sp)
                                        }
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = activePt.x.toInt().toString(),
                                        onValueChange = { txt ->
                                            val v = txt.filter { it.isDigit() }.toFloatOrNull() ?: 0f
                                            points = points.mapIndexed { i, p -> if (i == safeIdx) p.copy(x = v) else p }
                                            syncOverlayNow()
                                        },
                                        label = { Text("X", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors(accent)
                                    )
                                    OutlinedTextField(
                                        value = activePt.y.toInt().toString(),
                                        onValueChange = { txt ->
                                            val v = txt.filter { it.isDigit() }.toFloatOrNull() ?: 0f
                                            points = points.mapIndexed { i, p -> if (i == safeIdx) p.copy(y = v) else p }
                                            syncOverlayNow()
                                        },
                                        label = { Text("Y", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors(accent)
                                    )
                                    ActionPill(
                                        text = if (activePt.isSwipe) "Swipe: AN" else "Tap: AN",
                                        active = activePt.isSwipe,
                                        accent = accent,
                                        modifier = Modifier.weight(1.1f)
                                    ) {
                                        buzz()
                                        points = points.mapIndexed { i, p ->
                                            if (i == safeIdx) {
                                                val toSwipe = !p.isSwipe
                                                p.copy(
                                                    isSwipe = toSwipe,
                                                    endX = p.endX ?: p.x,
                                                    endY = p.endY ?: (p.y - 420f)
                                                )
                                            } else p
                                        }
                                        syncOverlayNow()
                                    }
                                }

                                if (activePt.isSwipe) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        fun setDir(dx: Float, dy: Float) {
                                            buzz()
                                            points = points.mapIndexed { i, p ->
                                                if (i == safeIdx) p.copy(endX = (p.x + dx).coerceAtLeast(1f), endY = (p.y + dy).coerceAtLeast(1f))
                                                else p
                                            }
                                            syncOverlayNow()
                                        }
                                        ActionPill("↑ Hoch", false, accent, Modifier.weight(1f)) { setDir(0f, -450f) }
                                        ActionPill("↓ Runter", false, accent, Modifier.weight(1f)) { setDir(0f, 450f) }
                                        ActionPill("← Links", false, accent, Modifier.weight(1f)) { setDir(-350f, 0f) }
                                        ActionPill("→ Rechts", false, accent, Modifier.weight(1f)) { setDir(350f, 0f) }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = (activePt.endX ?: activePt.x).toInt().toString(),
                                            onValueChange = { txt ->
                                                val v = txt.filter { it.isDigit() }.toFloatOrNull() ?: 0f
                                                points = points.mapIndexed { i, p -> if (i == safeIdx) p.copy(endX = v) else p }
                                                syncOverlayNow()
                                            },
                                            label = { Text("Ziel X", color = TextDim) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                            colors = fieldColors(accent)
                                        )
                                        OutlinedTextField(
                                            value = (activePt.endY ?: activePt.y).toInt().toString(),
                                            onValueChange = { txt ->
                                                val v = txt.filter { it.isDigit() }.toFloatOrNull() ?: 0f
                                                points = points.mapIndexed { i, p -> if (i == safeIdx) p.copy(endY = v) else p }
                                                syncOverlayNow()
                                            },
                                            label = { Text("Ziel Y", color = TextDim) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                            colors = fieldColors(accent)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = intervalValue,
                                        onValueChange = {
                                            intervalValue = it.filter { c -> c.isDigit() }
                                            syncOverlayNow()
                                        },
                                        label = { Text(if (useSeconds) "Intervall (s)" else "Intervall (ms)", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors(accent)
                                    )
                                    ActionPill(
                                        text = if (useSeconds) "Einheit: s" else "Einheit: ms",
                                        active = useSeconds,
                                        accent = accent,
                                        modifier = Modifier.weight(0.8f)
                                    ) {
                                        buzz()
                                        useSeconds = !useSeconds
                                        syncOverlayNow()
                                    }
                                    OutlinedTextField(
                                        value = jitterPx,
                                        onValueChange = {
                                            jitterPx = it.filter { c -> c.isDigit() }
                                            syncOverlayNow()
                                        },
                                        label = { Text("Jitter px", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.75f),
                                        colors = fieldColors(accent)
                                    )
                                }

                                Spacer(Modifier.height(10.dp))
                                Text("Stopp-Bedingung", color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StopMode.entries.forEach { m ->
                                        val sel = stopMode == m
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (sel) accent.copy(alpha = 0.22f) else SoftBg)
                                                .border(1.dp, if (sel) accent else LineColor, RoundedCornerShape(10.dp))
                                                .clickable {
                                                    buzz()
                                                    stopMode = m
                                                    syncOverlayNow()
                                                }
                                                .padding(vertical = 9.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(m.label, color = TextLight, fontSize = 11.sp, maxLines = 1)
                                        }
                                    }
                                }

                                if (stopMode != StopMode.INFINITE) {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = stopValue,
                                        onValueChange = {
                                            stopValue = it.filter { c -> c.isDigit() }
                                            syncOverlayNow()
                                        },
                                        label = { Text(if (stopMode == StopMode.CYCLES) "Anzahl Zyklen" else "Dauer in Sekunden", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = fieldColors(accent)
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            buzz()
                                            if (!overlayOk) {
                                                openOverlaySettings(context)
                                                statusMsg = "Overlay-Recht aktivieren"
                                                return@OutlinedButton
                                            }
                                            if (overlayActive) {
                                                OverlayManager.hideOverlay(stopService = true)
                                                overlayActive = false
                                                autoTapRunning = false
                                                statusMsg = "Overlay ausgeblendet"
                                            } else {
                                                actionCount = 0
                                                val cfg = OverlayConfigState(
                                                    points = points,
                                                    selectedIndex = safeIdx,
                                                    intervalMs = computeIntervalMs(),
                                                    maxCycles = computeMaxCycles(),
                                                    maxDurationMs = computeMaxDurationMs(),
                                                    jitterPx = jitterPx.toIntOrNull() ?: 0,
                                                    accentHex = accent.toArgb()
                                                )
                                                OverlayManager.showOverlay(context, cfg) { syncedCfg, running, count ->
                                                    points = syncedCfg.points
                                                    selectedPointIdx = syncedCfg.selectedIndex
                                                    autoTapRunning = running
                                                    overlayActive = OverlayManager.isOverlayVisible()
                                                    if (count > 0) actionCount = count
                                                    statusMsg = if (running) "Overlay aktiv • #$actionCount" else "Overlay bereit"
                                                }
                                                overlayActive = true
                                                statusMsg = "Großes Overlay aktiv (⚙ für Optionen)"
                                            }
                                        },
                                        border = BorderStroke(1.dp, accent),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (overlayActive) "Overlay aus" else "Overlay zeigen", color = accent, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            buzz()
                                            val svc = AutoTapService.instance
                                            if (svc == null) {
                                                openAccessibilitySettings(context)
                                                statusMsg = "Service aktivieren!"
                                                return@Button
                                            }
                                            if (autoTapRunning) {
                                                svc.stopAutoTap(reason = null)
                                                autoTapRunning = false
                                                statusMsg = "Gestoppt"
                                            } else {
                                                actionCount = 0
                                                autoTapRunning = true
                                                statusMsg = "Läuft..."
                                                svc.startSequence(
                                                    points = points,
                                                    intervalMs = computeIntervalMs(),
                                                    maxCycles = computeMaxCycles(),
                                                    maxDurationMs = computeMaxDurationMs(),
                                                    jitterPx = jitterPx.toIntOrNull() ?: 0,
                                                    onStepTick = { total, _ -> actionCount = total },
                                                    onFinished = { reason ->
                                                        autoTapRunning = false
                                                        statusMsg = reason
                                                    }
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (autoTapRunning) "Stopp" else "Start", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        contentPadding = PaddingValues(bottom = 28.dp)
                    ) {
                        item {
                            if (editingMacroId == null) {
                                Button(
                                    onClick = {
                                        buzz()
                                        startEditor(null)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("+ Neues Macro erstellen", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                SimpleCard(pad) {
                                    Text("Macro-Editor", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = draftName,
                                            onValueChange = { draftName = it },
                                            label = { Text("Name", color = TextDim) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1.4f),
                                            colors = fieldColors(accent)
                                        )
                                        OutlinedTextField(
                                            value = draftRepeat,
                                            onValueChange = { draftRepeat = it.filter { c -> c.isDigit() } },
                                            label = { Text("Wdh.", color = TextDim) },
                                            singleLine = true,
                                            modifier = Modifier.weight(0.6f),
                                            colors = fieldColors(accent)
                                        )
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("WAIT" to "Warten", "TAP" to "Klick", "SWIPE" to "Swipe", "TORCH" to "Licht", "HOME" to "Home", "LOCK" to "Sperren").forEach { (key, title) ->
                                            val active = stepType == key
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (active) accent else SoftBg)
                                                    .clickable {
                                                        buzz()
                                                        stepType = key
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 7.dp)
                                            ) {
                                                Text(title, color = if (active) Color.Black else TextLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    when (stepType) {
                                        "WAIT" -> OutlinedTextField(
                                            value = stepValA,
                                            onValueChange = { stepValA = it.filter { c -> c.isDigit() } },
                                            label = { Text("Wartezeit (ms)", color = TextDim) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = fieldColors(accent)
                                        )
                                        "TAP" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(stepValC, { stepValC = it.filter { c -> c.isDigit() } }, label = { Text("X", color = TextDim) }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors(accent))
                                            OutlinedTextField(stepValB, { stepValB = it.filter { c -> c.isDigit() } }, label = { Text("Y", color = TextDim) }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors(accent))
                                        }
                                        "SWIPE" -> {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                ActionPill("↑", false, accent, Modifier.weight(1f)) { stepValC = "540"; stepValB = "1450"; stepValA = "540"; stepValD = "750" }
                                                ActionPill("↓", false, accent, Modifier.weight(1f)) { stepValC = "540"; stepValB = "750"; stepValA = "540"; stepValD = "1450" }
                                                ActionPill("←", false, accent, Modifier.weight(1f)) { stepValC = "850"; stepValB = "1100"; stepValA = "220"; stepValD = "1100" }
                                                ActionPill("→", false, accent, Modifier.weight(1f)) { stepValC = "220"; stepValB = "1100"; stepValA = "850"; stepValD = "1100" }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                OutlinedTextField(stepValC, { stepValC = it.filter { c -> c.isDigit() } }, label = { Text("X1", color = TextDim) }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors(accent))
                                                OutlinedTextField(stepValB, { stepValB = it.filter { c -> c.isDigit() } }, label = { Text("Y1", color = TextDim) }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors(accent))
                                                OutlinedTextField(stepValA, { stepValA = it.filter { c -> c.isDigit() } }, label = { Text("X2", color = TextDim) }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors(accent))
                                                OutlinedTextField(stepValD, { stepValD = it.filter { c -> c.isDigit() } }, label = { Text("Y2", color = TextDim) }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors(accent))
                                            }
                                        }
                                        "TORCH" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ActionPill("Licht AN", stepValA == "1", accent, Modifier.weight(1f)) { stepValA = "1" }
                                            ActionPill("Licht AUS", stepValA != "1", accent, Modifier.weight(1f)) { stepValA = "0" }
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            buzz()
                                            val added: MacroStep = when (stepType) {
                                                "TAP" -> MacroStep.TapStep(stepValC.toFloatOrNull() ?: 540f, stepValB.toFloatOrNull() ?: 1100f)
                                                "SWIPE" -> MacroStep.SwipeStep(
                                                    stepValC.toFloatOrNull() ?: 540f,
                                                    stepValB.toFloatOrNull() ?: 1400f,
                                                    stepValA.toFloatOrNull() ?: 540f,
                                                    stepValD.toFloatOrNull() ?: 700f,
                                                    200L
                                                )
                                                "TORCH" -> MacroStep.TorchStep(stepValA == "1")
                                                "HOME" -> MacroStep.HomeStep
                                                "LOCK" -> MacroStep.LockScreenStep
                                                else -> MacroStep.WaitStep((stepValA.toLongOrNull() ?: 350L).coerceAtLeast(30L))
                                            }
                                            draftSteps = draftSteps + added
                                        },
                                        border = BorderStroke(1.dp, accent),
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("+ Schritt hinzufügen", color = accent) }

                                    Spacer(Modifier.height(6.dp))
                                    draftSteps.forEachIndexed { idx, st ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("${idx + 1}. ${st.label}", color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                            Text(
                                                "Entf.",
                                                color = accent,
                                                fontSize = 11.sp,
                                                modifier = Modifier.clickable {
                                                    buzz()
                                                    draftSteps = draftSteps.filterIndexed { i, _ -> i != idx }
                                                }
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { editingMacroId = null },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Abbrechen", color = TextDim) }
                                        Button(
                                            onClick = {
                                                buzz()
                                                val id = editingMacroId ?: "m_${System.currentTimeMillis()}"
                                                val updated = CustomMacro(
                                                    id = id,
                                                    name = draftName.trim().ifEmpty { "Macro" },
                                                    repeatTimes = (draftRepeat.toIntOrNull() ?: 1).coerceAtLeast(1),
                                                    steps = draftSteps
                                                )
                                                macros = if (macros.any { it.id == id }) {
                                                    macros.map { if (it.id == id) updated else it }
                                                } else {
                                                    macros + updated
                                                }
                                                editingMacroId = null
                                                statusMsg = "Macro gespeichert"
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Speichern", fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }

                        items(macros, key = { it.id }) { m ->
                            SimpleCard(pad) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(m.name, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text("${m.steps.size} Schritte • ${m.repeatTimes}x", color = TextDim, fontSize = 12.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(onClick = { startEditor(m) }, border = BorderStroke(1.dp, LineColor)) {
                                            Text("Edit", color = TextLight, fontSize = 12.sp)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                buzz()
                                                macros = macros.filterNot { it.id == m.id }
                                                statusMsg = "Macro gelöscht"
                                            },
                                            border = BorderStroke(1.dp, LineColor)
                                        ) {
                                            Text("Löschen", color = accent, fontSize = 12.sp)
                                        }
                                        Button(
                                            onClick = { runMacro(m) },
                                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)
                                        ) { Text("Run", fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        contentPadding = PaddingValues(bottom = 28.dp)
                    ) {
                        item {
                            SimpleCard(pad) {
                                Text("Design & Komfort", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AccentTheme.entries.forEach { theme ->
                                        val selected = theme == accentTheme
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (selected) theme.color.copy(alpha = 0.22f) else SoftBg)
                                                .border(1.dp, if (selected) theme.color else LineColor, RoundedCornerShape(10.dp))
                                                .clickable {
                                                    buzz()
                                                    accentTheme = theme
                                                    statusMsg = "Akzent: ${theme.label}"
                                                    syncOverlayNow()
                                                }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(theme.label, color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Tastatur-/Touch-Vibration", color = TextLight, fontSize = 13.sp)
                                    Switch(checked = hapticOn, onCheckedChange = { hapticOn = it }, colors = SwitchDefaults.colors(checkedTrackColor = accent))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Display wach halten (in App)", color = TextLight, fontSize = 13.sp)
                                    Switch(checked = keepScreenOn, onCheckedChange = { keepScreenOn = it }, colors = SwitchDefaults.colors(checkedTrackColor = accent))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Kompakte Karten", color = TextLight, fontSize = 13.sp)
                                    Switch(checked = compactCards, onCheckedChange = { compactCards = it }, colors = SwitchDefaults.colors(checkedTrackColor = accent))
                                }
                            }
                        }

                        item {
                            OutlinedTextField(
                                value = appQuery,
                                onValueChange = { appQuery = it },
                                placeholder = { Text("Apps suchen...", color = TextDim) },
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = accent) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = fieldColors(accent)
                            )
                        }

                        items(filteredApps, key = { it.packageName }) { app ->
                            SimpleCard(12.dp) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(app.name, color = TextLight, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(app.packageName, color = TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        IconButton(onClick = {
                                            buzz()
                                            val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                            if (launch != null) {
                                                context.startActivity(launch)
                                                statusMsg = "Gestartet: ${app.name}"
                                            }
                                        }) {
                                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Starten", tint = accent)
                                        }
                                        IconButton(onClick = {
                                            buzz()
                                            val del = Intent(Intent.ACTION_DELETE).apply {
                                                data = Uri.parse("package:${app.packageName}")
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(del)
                                            statusMsg = "Deinstallieren: ${app.name}"
                                        }) {
                                            Icon(Icons.Outlined.Delete, contentDescription = "Deinstallieren", tint = TextDim)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleCard(
    padding: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBg,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, LineColor.copy(alpha = 0.9f))
    ) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

@Composable
private fun ActionPill(
    text: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) accent.copy(alpha = 0.22f) else SoftBg)
            .border(1.dp, if (active) accent else LineColor, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = TextLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun fieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accent,
    unfocusedBorderColor = LineColor,
    focusedTextColor = TextLight,
    unfocusedTextColor = TextLight,
    cursorColor = accent,
    focusedContainerColor = CardBg,
    unfocusedContainerColor = CardBg
)

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    )
}

private fun openOverlaySettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    )
}

private fun openSettingsAction(context: Context, action: String) {
    try {
        context.startActivity(Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}

private fun setTorchState(context: Context, enabled: Boolean): Boolean {
    return try {
        val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cam.cameraIdList.firstOrNull {
            cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return false
        cam.setTorchMode(id, enabled)
        true
    } catch (_: Exception) {
        false
    }
}

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val pkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
    }
    return pkgs
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != context.packageName }
        .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName) }
        .sortedBy { it.name.lowercase(Locale.getDefault()) }
}
