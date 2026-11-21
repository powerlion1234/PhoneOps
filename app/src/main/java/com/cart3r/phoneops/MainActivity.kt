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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgObsidian = Color(0xFF050507)
private val SurfaceCard = Color(0xFF0D0E12)
private val SurfaceElevated = Color(0xFF14161D)
private val CrimsonPrimary = Color(0xFFFF1E38)
private val CrimsonDim = Color(0xFF4A0A14)
private val CyanAccent = Color(0xFF00E5FF)
private val TextPrimary = Color(0xFFF2F4F8)
private val TextMuted = Color(0xFF7D8594)
private val BorderSubtle = Color(0xFF222632)

data class InstalledAppItem(
    val name: String,
    val packageName: String
)

data class SystemWorkflow(
    val id: String,
    val code: String,
    val title: String,
    val subtitle: String,
    val steps: List<String>,
    val icon: ImageVector,
    val accent: Color
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneOpsApp()
        }
    }
}

@Composable
fun PhoneOpsApp() {
    val context = LocalContext.current
    var activeTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var torchEnabled by remember { mutableStateOf(false) }
    val logs = remember {
        mutableStateListOf(
            "[${nowTime()}] KERNEL BRIDGE READY // ANDROID API ${Build.VERSION.SDK_INT}",
            "[${nowTime()}] PACKAGE ENUMERATOR & INTENT DISPATCHER ONLINE"
        )
    }

    fun pushLog(entry: String) {
        logs.add(0, "[${nowTime()}] $entry")
        if (logs.size > 25) logs.removeAt(logs.lastIndex)
    }

    val installedApps by remember {
        mutableStateOf(loadUserApps(context))
    }

    val workflows = remember {
        listOf(
            SystemWorkflow(
                id = "wf_torch",
                code = "HW-01",
                title = "HARDWARE TORCH OVERRIDE",
                subtitle = "Direct CameraManager LED state toggle",
                steps = listOf("Query rear flash unit", "Invert torch state"),
                icon = Icons.Outlined.FlashlightOn,
                accent = CrimsonPrimary
            ),
            SystemWorkflow(
                id = "wf_stealth",
                code = "SYS-02",
                title = "STEALTH & FOCUS LOCKDOWN",
                subtitle = "Kill torch + open Do Not Disturb & Radio controls",
                steps = listOf("Force torch OFF", "Open DND Access", "Open Wireless Settings"),
                icon = Icons.Outlined.Shield,
                accent = CyanAccent
            ),
            SystemWorkflow(
                id = "wf_purge",
                code = "CLN-03",
                title = "STORAGE & CACHE PURGE",
                subtitle = "Jump straight to disk analyzer & package manager",
                steps = listOf("Launch Storage Manager", "Open Installed Apps"),
                icon = Icons.Outlined.CleaningServices,
                accent = CrimsonPrimary
            ),
            SystemWorkflow(
                id = "wf_net",
                code = "NET-04",
                title = "NETWORK & BT MATRIX",
                subtitle = "Rapid jump to Wi-Fi and Bluetooth hardware panels",
                steps = listOf("Open Wi-Fi Panel", "Open Bluetooth Panel"),
                icon = Icons.Outlined.WifiTethering,
                accent = CyanAccent
            ),
            SystemWorkflow(
                id = "wf_battery",
                code = "PWR-05",
                title = "POWER & BACKGROUND AUDIT",
                subtitle = "Inspect unrestricted background drainers",
                steps = listOf("Open Battery Optimization"),
                icon = Icons.Outlined.Bolt,
                accent = CrimsonPrimary
            ),
            SystemWorkflow(
                id = "wf_dev",
                code = "DEV-06",
                title = "DEVELOPER & USB DEBUG",
                subtitle = "Direct jump to Android Developer Options",
                steps = listOf("Launch Developer Settings"),
                icon = Icons.Outlined.Terminal,
                accent = CyanAccent
            )
        )
    }

    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = BgObsidian,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgObsidian)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "OVER // RIDE",
                            color = TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "DEVICE EXECUTION & PACKAGE DECK",
                            color = CrimsonPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                    Surface(
                        color = CrimsonDim.copy(alpha = 0.45f),
                        shape = CutCornerShape(6.dp),
                        border = BorderStroke(1.dp, CrimsonPrimary)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(CrimsonPrimary)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${installedApps.size} PKGS",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceCard, RoundedCornerShape(8.dp))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TabPill(
                        label = "WORKFLOWS",
                        selected = activeTab == 0,
                        modifier = Modifier.weight(1f)
                    ) { activeTab = 0 }
                    TabPill(
                        label = "APP CONTROL (${installedApps.size})",
                        selected = activeTab == 1,
                        modifier = Modifier.weight(1f)
                    ) { activeTab = 1 }
                    TabPill(
                        label = "TELEMETRY",
                        selected = activeTab == 2,
                        modifier = Modifier.weight(1f)
                    ) { activeTab = 2 }
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceCard,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ">",
                        color = CrimsonPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = logs.firstOrNull() ?: "IDLE",
                        color = CyanAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (activeTab) {
                0 -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(workflows) { wf ->
                            WorkflowCard(
                                workflow = wf,
                                activeState = if (wf.id == "wf_torch") torchEnabled else false,
                                onExecute = {
                                    executeWorkflow(
                                        context = context,
                                        workflow = wf,
                                        currentTorch = torchEnabled,
                                        onTorchChanged = { torchEnabled = it },
                                        onLog = ::pushLog
                                    )
                                }
                            )
                        }
                    }
                }

                1 -> {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = {
                            Text(
                                "Filter by app name or package ID...",
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null, tint = CrimsonPrimary)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = null, tint = TextMuted)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CrimsonPrimary,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = CrimsonPrimary,
                            focusedContainerColor = SurfaceCard,
                            unfocusedContainerColor = SurfaceCard
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(Modifier.height(10.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            AppControlRow(
                                app = app,
                                onLaunch = {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                    if (launchIntent != null) {
                                        context.startActivity(launchIntent)
                                        pushLog("EXEC LAUNCH -> ${app.packageName}")
                                    } else {
                                        pushLog("NO LAUNCHER ACTIVITY -> ${app.packageName}")
                                    }
                                },
                                onAppInfo = {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${app.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                    pushLog("OPEN INSPECTOR -> ${app.packageName}")
                                },
                                onUninstall = {
                                    val deleteIntent = Intent(Intent.ACTION_DELETE).apply {
                                        data = Uri.parse("package:${app.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(deleteIntent)
                                    pushLog("DISPATCH UNINSTALL -> ${app.packageName}")
                                }
                            )
                        }
                    }
                }

                2 -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(logs) { line ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = SurfaceCard,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, BorderSubtle)
                            ) {
                                Text(
                                    text = line,
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) CrimsonPrimary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else TextMuted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun WorkflowCard(
    workflow: SystemWorkflow,
    activeState: Boolean,
    onExecute: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExecute),
        color = SurfaceCard,
        shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.horizontalGradient(
                listOf(workflow.accent.copy(alpha = 0.85f), BorderSubtle)
            )
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(SurfaceElevated, RoundedCornerShape(8.dp))
                            .border(1.dp, workflow.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = workflow.icon,
                            contentDescription = null,
                            tint = workflow.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "${workflow.code} // ${workflow.title}",
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = workflow.subtitle,
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }

                Surface(
                    color = if (activeState) CrimsonPrimary else SurfaceElevated,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, workflow.accent)
                ) {
                    Text(
                        text = if (activeState) "ACTIVE" else "RUN",
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                workflow.steps.forEachIndexed { idx, step ->
                    Text(
                        text = "${idx + 1}. $step",
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .background(SurfaceElevated, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppControlRow(
    app: InstalledAppItem,
    onLaunch: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceCard,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = app.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionIconButton(
                    icon = Icons.Outlined.PlayArrow,
                    tint = CyanAccent,
                    onClick = onLaunch
                )
                ActionIconButton(
                    icon = Icons.Outlined.Tune,
                    tint = TextPrimary,
                    onClick = onAppInfo
                )
                ActionIconButton(
                    icon = Icons.Outlined.DeleteForever,
                    tint = CrimsonPrimary,
                    onClick = onUninstall
                )
            }
        }
    }
}

@Composable
private fun ActionIconButton(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceElevated)
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

private fun executeWorkflow(
    context: Context,
    workflow: SystemWorkflow,
    currentTorch: Boolean,
    onTorchChanged: (Boolean) -> Unit,
    onLog: (String) -> Unit
) {
    when (workflow.id) {
        "wf_torch" -> {
            val next = !currentTorch
            if (setTorchState(context, next)) {
                onTorchChanged(next)
                onLog("HW-01 TORCH -> ${if (next) "ENABLED" else "DISABLED"}")
            } else {
                onLog("HW-01 ERROR -> NO FLASH UNIT FOUND")
            }
        }

        "wf_stealth" -> {
            if (currentTorch && setTorchState(context, false)) {
                onTorchChanged(false)
            }
            safeStartSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            onLog("SYS-02 STEALTH -> TORCH OFF + DND POLICY OPENED")
        }

        "wf_purge" -> {
            val storageIntent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(storageIntent)
            onLog("CLN-03 PURGE -> STORAGE & PACKAGE INSPECTOR OPENED")
        }

        "wf_net" -> {
            safeStartSettings(context, Settings.ACTION_WIRELESS_SETTINGS)
            onLog("NET-04 MATRIX -> WIRELESS & BT CONTROLS OPENED")
        }

        "wf_battery" -> {
            safeStartSettings(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            onLog("PWR-05 AUDIT -> BATTERY OPTIMIZATION OPENED")
        }

        "wf_dev" -> {
            safeStartSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            onLog("DEV-06 BRIDGE -> DEVELOPER OPTIONS OPENED")
        }
    }
}

private fun setTorchState(context: Context, enabled: Boolean): Boolean {
    return try {
        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = camManager.cameraIdList.firstOrNull { id ->
            camManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return false
        camManager.setTorchMode(cameraId, enabled)
        true
    } catch (_: Exception) {
        false
    }
}

private fun safeStartSettings(context: Context, action: String) {
    val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}

private fun loadUserApps(context: Context): List<InstalledAppItem> {
    val pm = context.packageManager
    val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
    }
    return packages
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != context.packageName }
        .map {
            InstalledAppItem(
                name = pm.getApplicationLabel(it).toString(),
                packageName = it.packageName
            )
        }
        .sortedBy { it.name.lowercase(Locale.getDefault()) }
}

private fun nowTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

