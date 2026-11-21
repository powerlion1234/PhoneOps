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

data class AppEntry(val name: String, val packageName: String)

data class MacroRoutine(
    val id: String,
    val name: String,
    val summary: String,
    val action: (Context, Boolean, (Boolean) -> Unit, (String) -> Unit) -> Unit
)

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
    var tab by remember { mutableIntStateOf(0) }
    var accentTheme by remember { mutableStateOf(AccentTheme.CRIMSON) }
    var compactCards by remember { mutableStateOf(false) }

    var torchOn by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("Ready") }

    var tapX by remember { mutableStateOf("540") }
    var tapY by remember { mutableStateOf("1200") }
    var intervalMs by remember { mutableStateOf("500") }
    var infiniteLoop by remember { mutableStateOf(false) }
    var repeatCount by remember { mutableStateOf("10") }
    var tapCount by remember { mutableIntStateOf(0) }
    var autoTapRunning by remember { mutableStateOf(false) }

    var appQuery by remember { mutableStateOf("") }
    val userApps = remember { loadApps(context) }

    val accent = accentTheme.color
    val pad = if (compactCards) 10.dp else 14.dp
    val gap = if (compactCards) 8.dp else 12.dp

    val macros = remember {
        listOf(
            MacroRoutine(
                id = "m_night",
                name = "Quick Lock & Torch Off",
                summary = "Turns off flashlight and locks screen via Accessibility"
            ) { ctx, currentTorch, setTorch, notify ->
                if (currentTorch && setTorchState(ctx, false)) {
                    setTorch(false)
                }
                val svc = AutoTapService.instance
                if (svc != null && svc.lockScreenNow()) {
                    notify("Locked screen")
                } else {
                    openAccessibilitySettings(ctx)
                    notify("Enable Accessibility for screen lock")
                }
            },
            MacroRoutine(
                id = "m_wifi",
                name = "Wi-Fi & Internet Panel",
                summary = "Opens wireless settings quickly"
            ) { ctx, _, _, notify ->
                openSettingsAction(ctx, Settings.ACTION_WIFI_SETTINGS)
                notify("Opened Wi-Fi settings")
            },
            MacroRoutine(
                id = "m_cleaner",
                name = "Storage Cleanup",
                summary = "Opens device storage settings to free space"
            ) { ctx, _, _, notify ->
                openSettingsAction(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                notify("Opened Storage settings")
            },
            MacroRoutine(
                id = "m_sound",
                name = "Do Not Disturb Access",
                summary = "Opens notification policy & sound controls"
            ) { ctx, _, _, notify ->
                openSettingsAction(ctx, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                notify("Opened Do Not Disturb settings")
            }
        )
    }

    val filteredApps = remember(appQuery, userApps) {
        if (appQuery.isBlank()) userApps
        else userApps.filter {
            it.name.contains(appQuery, ignoreCase = true) ||
                it.packageName.contains(appQuery, ignoreCase = true)
        }
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
                    val tabs = listOf("Home", "Auto Tap", "Macros", "Apps & UI")
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
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            SimpleCard(accent, pad) {
                                Text("Quick Controls", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("One-tap toggles and system shortcuts.", color = TextDim, fontSize = 13.sp)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ActionPill(
                                        text = if (torchOn) "Flashlight: ON" else "Flashlight: OFF",
                                        active = torchOn,
                                        accent = accent,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        val next = !torchOn
                                        if (setTorchState(context, next)) {
                                            torchOn = next
                                            statusMsg = if (next) "Flashlight on" else "Flashlight off"
                                        } else {
                                            statusMsg = "No flash available"
                                        }
                                    }
                                    ActionPill(
                                        text = "Accessibility",
                                        active = AutoTapService.isServiceConnected,
                                        accent = accent,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        openAccessibilitySettings(context)
                                        statusMsg = "Opened Accessibility settings"
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                ActionPill(
                                    text = if (Settings.canDrawOverlays(context)) "Overlay: ALLOWED" else "Allow Overlay",
                                    active = Settings.canDrawOverlays(context),
                                    accent = accent,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    openOverlaySettings(context)
                                    statusMsg = "Opened Overlay settings"
                                }
                            }
                        }

                        items(macros, key = { it.id }) { macro ->
                            SimpleCard(accent, pad) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                        Text(macro.name, color = TextLight, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                        Text(macro.summary, color = TextDim, fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = {
                                            macro.action(context, torchOn, { torchOn = it }) { statusMsg = it }
                                        },
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

                1 -> {
                    val connected = AutoTapService.isServiceConnected
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            SimpleCard(accent, pad) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Auto Clicker", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(
                                            text = if (connected) "Accessibility active • Taps sent: $tapCount" else "Turn on Accessibility Service to tap screen",
                                            color = if (connected) accent else TextDim,
                                            fontSize = 12.sp
                                        )
                                    }
                                    if (!connected) {
                                        OutlinedButton(
                                            onClick = { openAccessibilitySettings(context) },
                                            border = BorderStroke(1.dp, accent)
                                        ) {
                                            Text("Enable", color = accent)
                                        }
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = tapX,
                                        onValueChange = { tapX = it.filter { ch -> ch.isDigit() } },
                                        label = { Text("X (px)", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors(accent)
                                    )
                                    OutlinedTextField(
                                        value = tapY,
                                        onValueChange = { tapY = it.filter { ch -> ch.isDigit() } },
                                        label = { Text("Y (px)", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors(accent)
                                    )
                                }

                                Spacer(Modifier.height(10.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = intervalMs,
                                        onValueChange = { intervalMs = it.filter { ch -> ch.isDigit() } },
                                        label = { Text("Delay (ms)", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors(accent)
                                    )
                                    OutlinedTextField(
                                        value = repeatCount,
                                        onValueChange = { repeatCount = it.filter { ch -> ch.isDigit() } },
                                        enabled = !infiniteLoop,
                                        label = { Text("Taps", color = TextDim) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors(accent)
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Loop until stopped", color = TextLight, fontSize = 13.sp)
                                    Switch(
                                        checked = infiniteLoop,
                                        onCheckedChange = { infiniteLoop = it },
                                        colors = SwitchDefaults.colors(checkedTrackColor = accent)
                                    )
                                }

                                Spacer(Modifier.height(10.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            val x = tapX.toFloatOrNull() ?: 540f
                                            val y = tapY.toFloatOrNull() ?: 1200f
                                            val svc = AutoTapService.instance
                                            if (svc == null) {
                                                openAccessibilitySettings(context)
                                                statusMsg = "Enable Accessibility first"
                                            } else {
                                                Handler(Looper.getMainLooper()).postDelayed({
                                                    svc.tapAt(x, y) { ok ->
                                                        Handler(Looper.getMainLooper()).post {
                                                            statusMsg = if (ok) "Test tap at ($x, $y)" else "Tap cancelled"
                                                        }
                                                    }
                                                }, 1500L)
                                                statusMsg = "Test tap in 1.5s..."
                                            }
                                        },
                                        border = BorderStroke(1.dp, accent),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Test (1.5s)", color = accent)
                                    }

                                    Button(
                                        onClick = {
                                            val svc = AutoTapService.instance
                                            if (autoTapRunning) {
                                                svc?.stopAutoTap()
                                                autoTapRunning = false
                                                statusMsg = "Auto tap stopped"
                                            } else if (svc == null) {
                                                openAccessibilitySettings(context)
                                                statusMsg = "Enable Accessibility first"
                                            } else {
                                                val x = tapX.toFloatOrNull() ?: 540f
                                                val y = tapY.toFloatOrNull() ?: 1200f
                                                val delay = intervalMs.toLongOrNull() ?: 500L
                                                val limit = if (infiniteLoop) null else (repeatCount.toIntOrNull() ?: 10)
                                                tapCount = 0
                                                autoTapRunning = true
                                                statusMsg = "Auto tap running"
                                                svc.startAutoTap(
                                                    x = x,
                                                    y = y,
                                                    intervalMs = delay,
                                                    maxCount = limit,
                                                    onTick = { tapCount = it },
                                                    onFinished = {
                                                        autoTapRunning = false
                                                        statusMsg = "Auto tap finished"
                                                    }
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (autoTapRunning) Color(0xFF3A1016) else accent,
                                            contentColor = if (autoTapRunning) TextLight else Color.Black
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (autoTapRunning) "Stop" else "Start", fontWeight = FontWeight.Bold)
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
                        items(macros, key = { it.id }) { macro ->
                            SimpleCard(accent, pad) {
                                Text(macro.name, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(macro.summary, color = TextDim, fontSize = 13.sp)
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        macro.action(context, torchOn, { torchOn = it }) { statusMsg = it }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Execute Macro", fontWeight = FontWeight.Bold)
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
                            SimpleCard(accent, pad) {
                                Text("Customize Appearance", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                                                    statusMsg = "Theme: ${theme.label}"
                                                }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(theme.label, color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Compact cards", color = TextLight, fontSize = 13.sp)
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
                                placeholder = { Text("Search installed apps...", color = TextDim) },
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = accent) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = fieldColors(accent)
                            )
                        }

                        items(filteredApps, key = { it.packageName }) { app ->
                            SimpleCard(accent, 10.dp) {
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
                                                statusMsg = "Opened ${app.name}"
                                            }
                                        }) {
                                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Open", tint = accent)
                                        }
                                        IconButton(onClick = {
                                            val del = Intent(Intent.ACTION_DELETE).apply {
                                                data = Uri.parse("package:${app.packageName}")
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(del)
                                            statusMsg = "Uninstall ${app.name}"
                                        }) {
                                            Icon(Icons.Outlined.Delete, contentDescription = "Uninstall", tint = TextDim)
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
    accent: Color,
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
            .padding(vertical = 12.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
