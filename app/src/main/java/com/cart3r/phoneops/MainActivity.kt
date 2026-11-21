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
    CYCLES("Klick-Anzahl"),
    DURATION("Zeitdauer (s)")
}

sealed class MacroStep(val description: String) {
    data class WaitStep(val ms: Long) : MacroStep("Warte ${ms} ms")
    data class TapStep(val x: Float, val y: Float) : MacroStep("Klick auf (${x.toInt()}, ${y.toInt()})")
    data class TorchStep(val enabled: Boolean) : MacroStep(if (enabled) "Taschenlampe AN" else "Taschenlampe AUS")
    data class SettingsStep(val title: String, val action: String) : MacroStep("Öffne $title")
    data object LockScreenStep : MacroStep("Bildschirm sperren")
}

data class AutomationMacro(
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
            OverrideApp()
        }
    }
}

@Composable
fun OverrideApp() {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var tab by remember { mutableIntStateOf(0) }
    var accentTheme by remember { mutableStateOf(AccentTheme.CRIMSON) }
    var compactCards by remember { mutableStateOf(false) }

    var torchOn by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("Bereit") }
    var macroRunning by remember { mutableStateOf(false) }

    // Auto-Clicker state (synchron mit ziehbarem Zielkreis)
    var tapX by remember { mutableStateOf("540") }
    var tapY by remember { mutableStateOf("1100") }
    var intervalValue by remember { mutableStateOf("500") }
    var useSeconds by remember { mutableStateOf(false) }
    var stopMode by remember { mutableStateOf(StopMode.INFINITE) }
    var stopValue by remember { mutableStateOf("20") }
    var tapCount by remember { mutableIntStateOf(0) }
    var overlayActive by remember { mutableStateOf(OverlayManager.isOverlayVisible()) }
    var autoTapRunning by remember { mutableStateOf(false) }

    // Echte Mehr-Schritt-Macros
    var customMacros by remember {
        mutableStateOf(
            listOf(
                AutomationMacro(
                    id = "m_focus_lock",
                    name = "Nacht-Ablauf (Licht aus -> 1s -> Sperren)",
                    repeatTimes = 1,
                    steps = listOf(
                        MacroStep.TorchStep(false),
                        MacroStep.WaitStep(1000L),
                        MacroStep.LockScreenStep
                    )
                ),
                AutomationMacro(
                    id = "m_double_tap_flow",
                    name = "Doppelklick-Kette mit Pause",
                    repeatTimes = 2,
                    steps = listOf(
                        MacroStep.TapStep(540f, 1100f),
                        MacroStep.WaitStep(400L),
                        MacroStep.TapStep(540f, 1100f),
                        MacroStep.WaitStep(800L)
                    )
                ),
                AutomationMacro(
                    id = "m_net_check",
                    name = "WLAN-Schnellcheck",
                    repeatTimes = 1,
                    steps = listOf(
                        MacroStep.WaitStep(300L),
                        MacroStep.SettingsStep("WLAN", Settings.ACTION_WIFI_SETTINGS)
                    )
                )
            )
        )
    }

    var newMacroName by remember { mutableStateOf("") }
    var newMacroWaitMs by remember { mutableStateOf("600") }
    var newMacroTapX by remember { mutableStateOf("540") }
    var newMacroTapY by remember { mutableStateOf("1100") }

    var appQuery by remember { mutableStateOf("") }
    val userApps = remember { loadApps(context) }

    val accent = accentTheme.color
    val pad = if (compactCards) 10.dp else 14.dp
    val gap = if (compactCards) 8.dp else 12.dp

    val filteredApps = remember(appQuery, userApps) {
        if (appQuery.isBlank()) userApps
        else userApps.filter {
            it.name.contains(appQuery, ignoreCase = true) ||
                it.packageName.contains(appQuery, ignoreCase = true)
        }
    }

    fun computeIntervalMs(): Long {
        val raw = intervalValue.toLongOrNull() ?: 500L
        return if (useSeconds) (raw * 1000L).coerceAtLeast(100L) else raw.coerceAtLeast(40L)
    }

    fun computeMaxCount(): Int? =
        if (stopMode == StopMode.CYCLES) (stopValue.toIntOrNull() ?: 10).coerceAtLeast(1) else null

    fun computeMaxDurationMs(): Long? =
        if (stopMode == StopMode.DURATION) ((stopValue.toLongOrNull() ?: 10L) * 1000L).coerceAtLeast(500L) else null

    fun runMacro(macro: AutomationMacro) {
        if (macroRunning) {
            statusMsg = "Macro läuft bereits"
            return
        }
        macroRunning = true
        statusMsg = "Starte: ${macro.name}"

        val flatSteps = buildList {
            repeat(macro.repeatTimes.coerceAtLeast(1)) {
                addAll(macro.steps)
            }
        }

        fun executeAt(index: Int) {
            if (index >= flatSteps.size) {
                macroRunning = false
                statusMsg = "Fertig: ${macro.name}"
                return
            }
            when (val step = flatSteps[index]) {
                is MacroStep.WaitStep -> {
                    statusMsg = step.description
                    mainHandler.postDelayed({ executeAt(index + 1) }, step.ms.coerceAtLeast(50L))
                }
                is MacroStep.TapStep -> {
                    val svc = AutoTapService.instance
                    if (svc == null) {
                        macroRunning = false
                        statusMsg = "Bedienungshilfe für Klick aktivieren"
                        openAccessibilitySettings(context)
                    } else {
                        svc.tapAt(step.x, step.y) {
                            mainHandler.post {
                                statusMsg = step.description
                                executeAt(index + 1)
                            }
                        }
                    }
                }
                is MacroStep.TorchStep -> {
                    if (setTorchState(context, step.enabled)) {
                        torchOn = step.enabled
                    }
                    statusMsg = step.description
                    mainHandler.postDelayed({ executeAt(index + 1) }, 150L)
                }
                is MacroStep.SettingsStep -> {
                    openSettingsAction(context, step.action)
                    statusMsg = step.description
                    mainHandler.postDelayed({ executeAt(index + 1) }, 250L)
                }
                is MacroStep.LockScreenStep -> {
                    val locked = AutoTapService.instance?.lockScreenNow() == true
                    statusMsg = if (locked) "Bildschirm gesperrt" else "Sperren fehlgeschlagen"
                    macroRunning = false
                }
            }
        }

        executeAt(0)
    }

    Scaffold(
        containerColor = BgBlack,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "OVERRIDE",
                            color = TextLight,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = statusMsg,
                        color = accent,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBg, RoundedCornerShape(10.dp))
                        .border(1.dp, LineColor, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val tabs = listOf("Home", "Auto Tap", "Macros", "Tools & UI")
                    tabs.forEachIndexed { idx, title ->
                        val active = tab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) accent else Color.Transparent)
                                .clickable { tab = idx }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                color = if (active) Color.Black else TextDim,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
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
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            SimpleCard(pad) {
                                Text("Rechte & Schnellstart", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("Für schwebendes Zielkreuz & Taps beide Rechte aktivieren.", color = TextDim, fontSize = 12.sp)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionPill(
                                        text = if (overlayOk) "Overlay: AN" else "Overlay erlauben",
                                        active = overlayOk,
                                        accent = accent,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        openOverlaySettings(context)
                                        statusMsg = "Overlay-Einstellung geöffnet"
                                    }
                                    ActionPill(
                                        text = if (a11yOk) "Service: AN" else "Service erlauben",
                                        active = a11yOk,
                                        accent = accent,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        openAccessibilitySettings(context)
                                        statusMsg = "Bedienungshilfen geöffnet"
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
                                        val next = !torchOn
                                        if (setTorchState(context, next)) {
                                            torchOn = next
                                            statusMsg = if (next) "Taschenlampe AN" else "Taschenlampe AUS"
                                        }
                                    }
                                    ActionPill(
                                        text = "Sperren",
                                        active = false,
                                        accent = accent,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (AutoTapService.instance?.lockScreenNow() == true) {
                                            statusMsg = "Bildschirm gesperrt"
                                        } else {
                                            openAccessibilitySettings(context)
                                            statusMsg = "Service für Sperren aktivieren"
                                        }
                                    }
                                }
                            }
                        }

                        items(customMacros, key = { it.id }) { macro ->
                            SimpleCard(pad) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                        Text(macro.name, color = TextLight, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text(
                                            text = "${macro.steps.size} Schritte • ${macro.repeatTimes}x",
                                            color = TextDim,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Button(
                                        onClick = { runMacro(macro) },
                                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Start", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    val connected = AutoTapService.isServiceConnected
                    val overlayAllowed = Settings.canDrawOverlays(context)
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            SimpleCard(pad) {
                                Text("Schwebender Auto-Clicker", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(
                                    text = if (connected && overlayAllowed)
                                        "Bereit • Klicks: $tapCount • Ziel: (${tapX.toIntOrNull() ?: 0}, ${tapY.toIntOrNull() ?: 0})"
                                    else
                                        "Bitte Overlay & Bedienungshilfen aktivieren",
                                    color = if (connected && overlayAllowed) accent else TextDim,
                                    fontSize = 12.sp
                                )
                                Spacer(Modifier.height(10.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = tapX,
                                        onValueChange = { tapX = it.filter { c -> c.isDigit() } },
                                        label = { Text("X (px)", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors(accent)
                                    )
                                    OutlinedTextField(
                                        value = tapY,
                                        onValueChange = { tapY = it.filter { c -> c.isDigit() } },
                                        label = { Text("Y (px)", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors(accent)
                                    )
                                }

                                Spacer(Modifier.height(10.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = intervalValue,
                                        onValueChange = { intervalValue = it.filter { c -> c.isDigit() } },
                                        label = { Text(if (useSeconds) "Intervall (s)" else "Intervall (ms)", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors(accent)
                                    )
                                    ActionPill(
                                        text = if (useSeconds) "Einheit: s" else "Einheit: ms",
                                        active = useSeconds,
                                        accent = accent,
                                        modifier = Modifier.weight(0.75f)
                                    ) {
                                        useSeconds = !useSeconds
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                Text("Stopp-Bedingung", color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StopMode.entries.forEach { mode ->
                                        val sel = stopMode == mode
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) accent.copy(alpha = 0.2f) else SoftBg)
                                                .border(1.dp, if (sel) accent else LineColor, RoundedCornerShape(8.dp))
                                                .clickable { stopMode = mode }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(mode.label, color = TextLight, fontSize = 11.sp, maxLines = 1)
                                        }
                                    }
                                }

                                if (stopMode != StopMode.INFINITE) {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = stopValue,
                                        onValueChange = { stopValue = it.filter { c -> c.isDigit() } },
                                        label = {
                                            Text(
                                                if (stopMode == StopMode.CYCLES) "Anzahl Klicks" else "Dauer in Sekunden",
                                                color = TextDim
                                            )
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = fieldColors(accent)
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            if (!overlayAllowed) {
                                                openOverlaySettings(context)
                                                statusMsg = "Overlay-Recht aktivieren"
                                                return@OutlinedButton
                                            }
                                            if (!connected) {
                                                openAccessibilitySettings(context)
                                                statusMsg = "Bedienungshilfe aktivieren"
                                                return@OutlinedButton
                                            }
                                            if (overlayActive) {
                                                OverlayManager.hideOverlay()
                                                overlayActive = false
                                                autoTapRunning = false
                                                statusMsg = "Overlay ausgeblendet"
                                            } else {
                                                val x = tapX.toFloatOrNull() ?: 540f
                                                val y = tapY.toFloatOrNull() ?: 1100f
                                                tapCount = 0
                                                OverlayManager.showOverlay(
                                                    context = context,
                                                    initialX = x,
                                                    initialY = y,
                                                    intervalMs = computeIntervalMs(),
                                                    maxCount = computeMaxCount(),
                                                    maxDurationMs = computeMaxDurationMs(),
                                                    accentHex = accent.toArgb(),
                                                    onCoordinatesChanged = { nx, ny ->
                                                        tapX = nx.toInt().toString()
                                                        tapY = ny.toInt().toString()
                                                    },
                                                    onRunningChanged = { running ->
                                                        autoTapRunning = running
                                                        statusMsg = if (running) "Auto-Klick läuft" else "Overlay bereit"
                                                    },
                                                    onTapTick = { tick -> tapCount = tick }
                                                )
                                                overlayActive = true
                                                statusMsg = "Kreis ziehen & START drücken"
                                            }
                                        },
                                        border = BorderStroke(1.dp, accent),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = if (overlayActive) "Overlay aus" else "Overlay zeigen",
                                            color = accent,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            val svc = AutoTapService.instance
                                            if (svc == null) {
                                                openAccessibilitySettings(context)
                                                statusMsg = "Bedienungshilfe aktivieren"
                                                return@Button
                                            }
                                            if (autoTapRunning) {
                                                svc.stopAutoTap()
                                                autoTapRunning = false
                                                statusMsg = "Gestoppt"
                                            } else {
                                                val x = tapX.toFloatOrNull() ?: 540f
                                                val y = tapY.toFloatOrNull() ?: 1100f
                                                tapCount = 0
                                                autoTapRunning = true
                                                statusMsg = "Läuft..."
                                                svc.startAutoTap(
                                                    x = x,
                                                    y = y,
                                                    intervalMs = computeIntervalMs(),
                                                    maxCount = computeMaxCount(),
                                                    maxDurationMs = computeMaxDurationMs(),
                                                    onTick = { tapCount = it },
                                                    onFinished = {
                                                        autoTapRunning = false
                                                        statusMsg = "Beendet"
                                                    }
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (autoTapRunning) "Stopp" else "Direkt-Start", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            SimpleCard(pad) {
                                Text("Neues Macro (Warten + Klick)", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = newMacroName,
                                    onValueChange = { newMacroName = it },
                                    label = { Text("Macro-Name", color = TextDim) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = fieldColors(accent)
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = newMacroWaitMs,
                                        onValueChange = { newMacroWaitMs = it.filter { c -> c.isDigit() } },
                                        label = { Text("Pause (ms)", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors(accent)
                                    )
                                    OutlinedTextField(
                                        value = newMacroTapX,
                                        onValueChange = { newMacroTapX = it.filter { c -> c.isDigit() } },
                                        label = { Text("X", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.7f),
                                        colors = fieldColors(accent)
                                    )
                                    OutlinedTextField(
                                        value = newMacroTapY,
                                        onValueChange = { newMacroTapY = it.filter { c -> c.isDigit() } },
                                        label = { Text("Y", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.7f),
                                        colors = fieldColors(accent)
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        val title = newMacroName.trim().ifEmpty { "Eigenes Macro ${customMacros.size + 1}" }
                                        val wait = (newMacroWaitMs.toLongOrNull() ?: 500L).coerceAtLeast(50L)
                                        val x = newMacroTapX.toFloatOrNull() ?: 540f
                                        val y = newMacroTapY.toFloatOrNull() ?: 1100f
                                        customMacros = customMacros + AutomationMacro(
                                            id = "m_${System.currentTimeMillis()}",
                                            name = title,
                                            repeatTimes = 1,
                                            steps = listOf(
                                                MacroStep.WaitStep(wait),
                                                MacroStep.TapStep(x, y),
                                                MacroStep.WaitStep(wait)
                                            )
                                        )
                                        newMacroName = ""
                                        statusMsg = "Macro gespeichert"
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Macro hinzufügen", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        items(customMacros, key = { it.id }) { macro ->
                            SimpleCard(pad) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(macro.name, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Spacer(Modifier.height(4.dp))
                                        macro.steps.forEach { step ->
                                            Text("• ${step.description}", color = TextDim, fontSize = 12.sp)
                                        }
                                    }
                                    Button(
                                        onClick = { runMacro(macro) },
                                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Run", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            SimpleCard(pad) {
                                Text("Design & Layout", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AccentTheme.entries.forEach { theme ->
                                        val selected = theme == accentTheme
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (selected) theme.color.copy(alpha = 0.2f) else SoftBg)
                                                .border(1.dp, if (selected) theme.color else LineColor, RoundedCornerShape(8.dp))
                                                .clickable {
                                                    accentTheme = theme
                                                    statusMsg = "Akzent: ${theme.label}"
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
                                    Text("Kompakte Karten", color = TextLight, fontSize = 13.sp)
                                    Switch(
                                        checked = compactCards,
                                        onCheckedChange = { compactCards = it },
                                        colors = SwitchDefaults.colors(checkedTrackColor = accent)
                                    )
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
                            SimpleCard(10.dp) {
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
                                            val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                            if (launch != null) {
                                                context.startActivity(launch)
                                                statusMsg = "Gestartet: ${app.name}"
                                            }
                                        }) {
                                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Starten", tint = accent)
                                        }
                                        IconButton(onClick = {
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
    padding: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBg,
        shape = RoundedCornerShape(12.dp),
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
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) accent.copy(alpha = 0.2f) else SoftBg)
            .border(1.dp, if (active) accent else LineColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
