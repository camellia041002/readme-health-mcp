package app.healthquery.sync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.healthquery.sync.ui.theme.LifeDashboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var permissions: ActivityResultLauncher<Set<String>>
    private lateinit var qrScanner: ActivityResultLauncher<com.journeyapps.barcodescanner.ScanOptions>
    private lateinit var cameraPermission: ActivityResultLauncher<String>
    private var pendingQr by mutableStateOf("")
    private var healthPermissionResultVersion by mutableStateOf(0)
    private var healthPermissionResult: Set<String> = emptySet()
    private var healthPermissionRequestInFlight = false
    private var healthPermissionRequestAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        permissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            healthPermissionRequestInFlight = false
            healthPermissionResult = granted
            healthPermissionResultVersion++
        }
        qrScanner = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
            result.contents?.let { pendingQr = it }
        }
        cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchQrScanner()
        }
        pendingQr = intent?.dataString.orEmpty()
        setContent { LifeDashboardTheme { CompanionScreen() } }
    }

    private fun requestHealthConnectPermissions(requiredPermissions: Set<String>): Boolean {
        if (healthPermissionRequestInFlight || healthPermissionRequestAttempts >= MAX_HEALTH_PERMISSION_REQUEST_ATTEMPTS) {
            return false
        }
        healthPermissionRequestInFlight = true
        healthPermissionRequestAttempts++
        permissions.launch(requiredPermissions)
        return true
    }

    private fun resetHealthPermissionRequestAttempts() {
        healthPermissionRequestAttempts = 0
        healthPermissionResult = emptySet()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingQr = intent.dataString.orEmpty()
    }

    @Composable
    private fun CompanionScreen() {
        val scope = rememberCoroutineScope()
        val engine = remember { LosslessHealthSyncEngine(this) }
        val preferences = remember { PreferencesManager(this) }
        val application = applicationContext as? LifeDashboardApplication
        var connection by remember { mutableStateOf(engine.connected()) }
        var qr by remember { mutableStateOf(pendingQr) }
        var status by remember {
            mutableStateOf(
                when {
                    connection == null -> "Ready"
                    engine.isBootstrapComplete() -> "Up to date"
                    else -> "Needs attention"
                }
            )
        }
        var lastSync by remember { mutableStateOf(engine.lastSuccessfulSyncMillis()) }
        var history by remember { mutableStateOf(engine.syncHistory()) }
        var progress by remember { mutableStateOf<UiSyncProgress?>(null) }
        var settingsOpen by remember { mutableStateOf(false) }
        var reimportConfirmation by remember { mutableStateOf(false) }
        var showPairingLink by remember { mutableStateOf(qr.isNotBlank()) }
        var bootstrapPending by remember { mutableStateOf(connection != null && !engine.isBootstrapComplete()) }
        var healthPermissionsReady by remember { mutableStateOf<Boolean?>(null) }
        var cadenceMinutes by remember { mutableStateOf(preferences.getHealthSyncIntervalMinutes()) }

        suspend fun refreshHealthPermissions(scheduleBootstrap: Boolean): Boolean {
            val hc = HealthConnectManager(this@MainActivity)
            if (!hc.isHealthConnectAvailable()) {
                healthPermissionsReady = false
                if (connection != null) status = "Health Connect unavailable"
                return false
            }
            val granted = runCatching { hc.getGrantedPermissions() }.getOrDefault(emptySet())
            val requiredPermissions = hc.providerSupportedReadPermissions()
            val missing = requiredPermissions - granted
            val ready = missing.isEmpty()
            healthPermissionsReady = ready
            if (!ready) {
                if (connection != null) status = "Health Connect permission needed"
                // A blank result is commonly the return from Health Connect's first-run
                // onboarding. Allow one follow-up request, but do not loop forever after a
                // deliberate denial.
                if (!healthPermissionRequestInFlight &&
                    (healthPermissionResult.isEmpty() || healthPermissionRequestAttempts == 0)
                ) {
                    requestHealthConnectPermissions(requiredPermissions)
                }
                return false
            }
            if (scheduleBootstrap && connection != null && bootstrapPending && !engine.isBootstrapComplete()) {
                application?.scheduleBootstrapSyncWork()
                bootstrapPending = false
                status = "Bootstrap scheduled"
            }
            return true
        }

        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    connection = engine.connected()
                    lastSync = engine.lastSuccessfulSyncMillis()
                    history = engine.syncHistory()
                    scope.launch {
                        delay(250)
                        refreshHealthPermissions(scheduleBootstrap = connection != null && bootstrapPending)
                    }
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(pendingQr) {
            if (pendingQr.isNotBlank()) {
                qr = pendingQr
                showPairingLink = true
            }
        }
        LaunchedEffect(Unit) {
            refreshHealthPermissions(scheduleBootstrap = connection != null)
        }
        LaunchedEffect(healthPermissionResultVersion) {
            if (healthPermissionResultVersion > 0) {
                // The first return can only mean that Health Connect finished onboarding. Recheck
                // after the provider has committed its state, then request the actual grants.
                delay(250)
                refreshHealthPermissions(scheduleBootstrap = connection != null && bootstrapPending)
            }
        }
        LaunchedEffect(connection) {
            if (connection != null) {
                refreshHealthPermissions(scheduleBootstrap = bootstrapPending)
            }
        }
        LaunchedEffect(connection) {
            if (connection != null) while (isActive) {
                val (oneTimeInfos, periodicInfos) = withContext(Dispatchers.IO) {
                    val manager = WorkManager.getInstance(applicationContext)
                    (manager.getWorkInfosForUniqueWork(BootstrapSyncWorker.WORK_NAME).get() +
                        manager.getWorkInfosForUniqueWork(HealthSyncWorker.ONE_TIME_WORK_NAME).get()) to
                        manager.getWorkInfosForUniqueWork(HealthSyncWorker.WORK_NAME).get()
                }
                val running = oneTimeInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: periodicInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val queued = oneTimeInfos.firstOrNull {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                }
                if (running != null) {
                    status = if (running.tags.any { it.contains("BootstrapSyncWorker") }) {
                        "Importing history"
                    } else {
                        "Syncing"
                    }
                    // WorkManager may report RUNNING before the worker publishes its first
                    // progress snapshot. Keep the running status, but do not fabricate 0/—
                    // values from an empty Data object.
                    progress = progressFrom(running)
                } else if (queued != null) {
                    status = if (queued.tags.any { it.contains("BootstrapSyncWorker") }) {
                        "Import queued"
                    } else {
                        "Sync queued"
                    }
                    progress = null
                } else if (status in setOf(
                    "Importing history", "Syncing", "Sync scheduled", "Bootstrap scheduled",
                    "Sync queued", "Import queued",
                )) {
                    val refreshed = LosslessHealthSyncEngine(this@MainActivity)
                    status = if (refreshed.isBootstrapComplete()) "Up to date" else "Needs attention"
                    lastSync = refreshed.lastSuccessfulSyncMillis()
                    history = refreshed.syncHistory()
                    progress = null
                }
                delay(750)
            }
        }

        val title = if (settingsOpen) "设置" else "健康同步"
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        if (settingsOpen) {
                            IconButton(onClick = { settingsOpen = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                            }
                        }
                    },
                    actions = {
                        if (!settingsOpen && connection != null) {
                            IconButton(
                                onClick = { settingsOpen = true },
                                modifier = Modifier.semantics { contentDescription = "打开设置" }
                            ) { Icon(Icons.Default.Settings, null) }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { inset ->
            Box(Modifier.fillMaxSize().padding(inset)) {
                if (settingsOpen && connection != null) {
                    SettingsScreen(
                        connection = connection!!,
                        cadenceMinutes = cadenceMinutes,
                        active = progress != null || status in setOf(
                            "Importing history", "Syncing", "Sync scheduled", "Bootstrap scheduled",
                            "Sync queued", "Import queued",
                        ),
                        onDisconnect = {
                            application?.cancelSyncWork()
                            engine.disconnect()
                            connection = null
                            bootstrapPending = false
                            healthPermissionsReady = null
                            settingsOpen = false
                            status = "Ready"
                        },
                        onReimport = { reimportConfirmation = true },
                        onCadenceChange = { minutes ->
                            application?.updateHealthSyncFrequency(minutes)
                            cadenceMinutes = minutes
                        },
                    )
                } else if (connection == null) {
                    PairingScreen(
                        value = qr,
                        onValueChange = { qr = it },
                        showLink = showPairingLink,
                        onShowLink = { showPairingLink = true },
                        onScan = {
                            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                launchQrScanner()
                            } else cameraPermission.launch(Manifest.permission.CAMERA)
                        },
                        onConnect = {
                            scope.launch {
                                status = "Connecting"
                                runCatching { engine.pair(qr) }
                                    .onSuccess {
                                        connection = it
                                        bootstrapPending = true
                                        resetHealthPermissionRequestAttempts()
                                        healthPermissionsReady = null
                                        status = "Health Connect permission needed"
                                        refreshHealthPermissions(scheduleBootstrap = true)
                                    }
                                    .onFailure {
                                        status = "Needs attention"
                                    }
                            }
                        },
                        status = status
                    )
                } else {
                    ConnectedScreen(
                        status = status,
                        lastSync = lastSync,
                        history = history,
                        progress = progress,
                        cadenceMinutes = cadenceMinutes,
                        onSync = {
                            progress = null
                            if (engine.isBootstrapComplete()) {
                                status = "Sync scheduled"
                                application?.scheduleIncrementalSyncWork()
                            } else {
                                bootstrapPending = true
                                status = "Health Connect permission needed"
                                scope.launch { refreshHealthPermissions(scheduleBootstrap = true) }
                            }
                        }
                    )
                }
            }
        }

        if (reimportConfirmation) {
            AlertDialog(
                onDismissRequest = { reimportConfirmation = false },
                title = { Text("重新导入最近 30 天？") },
                text = {
                    Text("应用会重新读取 Health Connect 最近 30 天的数据并更新到服务器。服务器上已有的数据不会删除；完成后自动同步会继续运行。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        reimportConfirmation = false
                        if (application?.scheduleFullResyncWork() == true) {
                            settingsOpen = false
                            status = "Bootstrap scheduled"
                            progress = null
                        } else {
                            status = "Needs attention"
                        }
                    }) { Text("继续") }
                },
                dismissButton = { TextButton(onClick = { reimportConfirmation = false }) { Text("取消") } }
            )
        }
    }

    @Composable
    private fun PairingScreen(
        value: String,
        onValueChange: (String) -> Unit,
        showLink: Boolean,
        onShowLink: () -> Unit,
        onScan: () -> Unit,
        onConnect: () -> Unit,
        status: String,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            Text("健康记录，由你掌控。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("将手机与自己的服务器配对，健康数据始终由你管理。", style = MaterialTheme.typography.bodyLarge)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("连接服务器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.QrCodeScanner, null)
                        Spacer(Modifier.width(10.dp))
                        Text("扫描配对二维码")
                    }
                    TextButton(onClick = onShowLink, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("改用配对链接")
                    }
                    if (showLink) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth().testTag("pairing-link"),
                            label = { Text("配对链接") },
                            placeholder = { Text("https://server.example/pair/…") },
                            singleLine = true
                        )
                        OutlinedButton(onClick = onConnect, enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("连接")
                        }
                    }
                }
            }
            if (status != "Ready") {
                Text(
                    statusLabel(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == "Needs attention") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("只会申请同步所需的 Health Connect 数据权限。", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun ConnectedScreen(
        status: String,
        lastSync: Long?,
        history: List<SyncHistoryEntry>,
        progress: UiSyncProgress?,
        cadenceMinutes: Int,
        onSync: () -> Unit,
    ) {
        val running = progress != null || status in setOf("Importing history", "Syncing")
        val workPending = running || status in setOf(
            "Sync scheduled", "Bootstrap scheduled", "Sync queued", "Import queued",
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusCard(status, lastSync, running)
            if (running && progress != null) ProgressCard(progress)
            else Button(onClick = onSync, enabled = !workPending, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (running) "正在同步" else if (workPending) "同步已排队" else "同步新数据")
            }
            Text("自动同步：${SyncFrequencies.labelFor(cadenceMinutes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HistoryCard(history)
        }
    }

    @Composable
    private fun StatusCard(status: String, lastSync: Long?, active: Boolean) {
        val needsAttention = status in setOf(
            "Needs attention",
            "Health Connect permission needed",
            "Health Connect unavailable",
        )
        val accent = if (needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = .35f), RoundedCornerShape(18.dp))
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                        if (active) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = accent)
                        else Icon(if (needsAttention) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null, tint = accent)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(statusLabel(status), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                active -> "正在安全传输健康记录"
                                lastSync == null -> "等待首次同步成功"
                                else -> "上次成功同步：${relativeTime(lastSync)}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).semantics {
                        contentDescription = "同步状态指示条"
                    },
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    repeat(3) { segment ->
                        val alpha = when {
                            needsAttention -> if (segment == 0) 1f else .22f
                            active -> if (segment < 2) 1f else .28f
                            else -> if (segment == 2) 1f else .42f
                        }
                        Box(Modifier.weight(1f).fillMaxSize().background(accent.copy(alpha = alpha)))
                    }
                }
            }
        }
    }

    @Composable
    private fun ProgressCard(progress: UiSyncProgress) {
        val phase = progress.phase.lowercase(Locale.US)
        val inventory = phase.contains("inventory") || phase.contains("count")
        val sending = phase.contains("send") || phase.contains("upload")
        val changes = phase.contains("change")
        val total = progress.totalRecords
        val fraction = if (sending) total?.takeIf { it > 0 }?.let {
            (progress.processedRecords.toFloat() / it).coerceIn(0f, 1f)
        } else null
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        sending -> "正在发送最近 30 天的数据"
                        changes -> "正在同步最新变化"
                        inventory -> "正在统计最近 30 天的数据"
                        else -> "正在准备健康数据"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val progressDescription = fraction?.let { "数据传输已完成 ${(it * 100).toInt()}%" }
                    ?: "正在查找健康数据"
                if (fraction == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().testTag("sync-progress").semantics {
                            contentDescription = progressDescription
                        }
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().testTag("sync-progress").semantics {
                            contentDescription = progressDescription
                        }
                    )
                }
                if (progress.typeCount != null && progress.typeCount > 0) {
                    val visibleTypes = progress.typeCount.coerceAtMost(24)
                    val actualCompletedTypes = (progress.typeIndex ?: 0).coerceIn(0, progress.typeCount)
                    val completedTypes = ((actualCompletedTypes.toFloat() / progress.typeCount) * visibleTypes)
                        .toInt().coerceIn(0, visibleTypes)
                    Row(
                        Modifier.fillMaxWidth().height(4.dp).semantics {
                            contentDescription = "${progress.typeCount} 类数据中已完成 $actualCompletedTypes 类"
                        },
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        repeat(visibleTypes) { index ->
                            Box(
                                Modifier.weight(1f).fillMaxSize().background(
                                    if (index < completedTypes) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
                                )
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        fraction?.let { "${(it * 100).toInt()}%" }
                            ?: if (changes) "即将完成" else "准备中",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        when {
                            sending && total != null -> "${progress.processedRecords} / $total 条"
                            changes -> "${progress.processedRecords} 条最新变化"
                            else -> "已发现 ${progress.processedRecords} 条"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                val categoryTotal = progress.currentTypeTotal
                Text(
                    if (inventory) "${prettyType(progress.recordType)} · 已发现 ${progress.currentTypeProgress} 条"
                    else if (changes) "正在检查 Health Connect 的新数据"
                    else "${prettyType(progress.recordType)} ${progress.currentTypeProgress} / ${categoryTotal ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                val typeProgress = if (progress.typeIndex != null && progress.typeCount != null) {
                    "${progress.typeCount} 类中的第 ${(progress.typeIndex + 1).coerceAtMost(progress.typeCount)} 类"
                } else "已完成 ${progress.completedTypes} 类"
                Text("$typeProgress · 已用 ${formatDuration(progress.elapsedMillis)}${progress.etaMillis?.let { " · 预计还需 ${formatDuration(it)}" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun HistoryCard(history: List<SyncHistoryEntry>) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("同步记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (history.isEmpty()) {
                Text("完成的同步会显示在这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val latestSuccess = history.firstOrNull { it.success }
                if (latestSuccess != null) {
                    Text("最近一次成功同步", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HistoryRow(latestSuccess)
                } else {
                    Text("还没有成功同步", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                val recentAttempts = history.filterNot { it == latestSuccess }.take(4)
                if (recentAttempts.isNotEmpty()) {
                    Text("最近尝试", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    recentAttempts.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        HistoryRow(entry)
                    }
                }
            }
        }
    }

    @Composable
    private fun HistoryRow(entry: SyncHistoryEntry) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (entry.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                null,
                tint = if (entry.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(syncModeLabel(entry.runKind, entry.mode), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "${formatTime(entry.timestampMillis)} · ${entry.records ?: 0} 条${entry.durationMillis?.let { " · ${formatDuration(it)}" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!entry.success) Text("失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }

    @Composable
    private fun SettingsScreen(
        connection: ServerConnection,
        cadenceMinutes: Int,
        active: Boolean,
        onDisconnect: () -> Unit,
        onReimport: () -> Unit,
        onCadenceChange: (Int) -> Unit,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("后台同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "选择手机自动检查 Health Connect 新数据的频率。你也可以随时手动同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.selectableGroup()) {
                    SyncFrequencies.options.forEachIndexed { index, frequency ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = cadenceMinutes == frequency.minutes,
                                    enabled = !active,
                                    role = Role.RadioButton,
                                    onClick = { onCadenceChange(frequency.minutes) },
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = cadenceMinutes == frequency.minutes,
                                onClick = null,
                                enabled = !active,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(frequency.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(frequency.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Text(
                if (active) "当前同步完成后才能修改频率。"
                else "为节省电量，Android 可能推迟后台任务；新计划从现在开始计算。",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("服务器连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("服务器地址", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(connection.serverUrl, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { contentDescription = "服务器地址 ${connection.serverUrl}" })
                }
            }
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("断开并连接其他服务器") }
            Spacer(Modifier.height(8.dp))
            Text("重新导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(
                onClick = onReimport,
                enabled = !active,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) { Text("重新导入最近 30 天") }
            Text("重新读取并更新最近 30 天的数据，完成后会恢复自动同步。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (active) Text("同步进行中，暂时不可用。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }

    private fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE))

    private fun relativeTime(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val seconds = ((nowMillis - millis) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "刚刚"
            seconds < 3600 -> "${seconds / 60} 分钟前"
            seconds < 86400 -> "${seconds / 3600} 小时前"
            seconds < 604800 -> "${seconds / 86400} 天前"
            else -> formatTime(millis)
        }
    }

    private fun syncModeLabel(runKind: String?, mode: String?): String = when ((runKind ?: mode)?.lowercase(Locale.US)) {
        "initial" -> "首次导入"
        "full", "snapshot", "bootstrap" -> "最近 30 天导入"
        "manual" -> "手动同步"
        "incremental", "automatic", "changes" -> "自动同步"
        else -> "同步"
    }

    private fun prettyType(type: String?): String = when (type?.substringAfterLast('.')?.removeSuffix("Record")) {
        "ActiveCaloriesBurned" -> "活动消耗热量"
        "Distance" -> "距离"
        "ExerciseSession" -> "锻炼"
        "HeartRate" -> "心率"
        "HeartRateVariabilityRmssd" -> "心率变异性"
        "OxygenSaturation" -> "血氧"
        "RestingHeartRate" -> "静息心率"
        "SleepSession" -> "睡眠"
        "Steps" -> "步数"
        "TotalCaloriesBurned" -> "总消耗热量"
        else -> "健康记录"
    }
    private fun formatDuration(millis: Long): String = Duration.ofMillis(millis.coerceAtLeast(0)).let { if (it.toMinutes() > 0) "${it.toMinutes()} 分 ${it.seconds % 60} 秒" else "${it.seconds} 秒" }

    private fun statusLabel(status: String): String = when (status) {
        "Ready" -> "等待连接"
        "Up to date" -> "数据已是最新"
        "Needs attention" -> "需要处理"
        "Health Connect unavailable" -> "Health Connect 不可用"
        "Health Connect permission needed" -> "需要 Health Connect 权限"
        "Bootstrap scheduled" -> "最近 30 天导入已安排"
        "Importing history" -> "正在导入最近 30 天"
        "Syncing" -> "正在同步"
        "Sync scheduled" -> "同步已安排"
        "Sync queued" -> "同步已排队"
        "Import queued" -> "导入已排队"
        "Connecting" -> "正在连接"
        else -> status
    }

    private fun progressFrom(info: WorkInfo): UiSyncProgress? {
        if (info.progress.size() == 0) return null
        return UiSyncProgress(
        phase = info.progress.getString("phase") ?: "working",
        recordType = info.progress.getString("record_type"),
        processedRecords = info.progress.getInt("overall_processed_records", info.progress.getInt("processed_records", 0)),
        completedTypes = info.progress.getInt("completed_types", info.progress.getInt("completed_type_count", 0)),
        totalRecords = listOf("overall_total_records", "total_records", "records_total", "overall_total").firstNotNullOfOrNull { key ->
            info.progress.getInt(key, -1).takeIf { it >= 0 }
        },
        currentTypeProgress = info.progress.getInt("current_type_progress", info.progress.getInt("processed_records", 0)),
        currentTypeTotal = info.progress.getInt("current_type_total", -1).takeIf { it >= 0 },
        typeIndex = info.progress.getInt("type_index", -1).takeIf { it >= 0 },
        typeCount = info.progress.getInt("type_count", -1).takeIf { it >= 0 },
        elapsedMillis = info.progress.getLong("elapsed_millis", 0L),
        etaMillis = info.progress.getLong("eta_millis", -1L).takeIf { it >= 0L },
        )
    }

    private fun launchQrScanner() {
        qrScanner.launch(com.journeyapps.barcodescanner.ScanOptions().apply {
            setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            setPrompt("扫描服务器配对二维码")
            setBeepEnabled(false)
            setOrientationLocked(false)
        })
    }

    private companion object {
        const val MAX_HEALTH_PERMISSION_REQUEST_ATTEMPTS = 2
    }
}

private data class UiSyncProgress(
    val phase: String,
    val recordType: String?,
    val processedRecords: Int,
    val completedTypes: Int,
    val totalRecords: Int?,
    val currentTypeProgress: Int,
    val currentTypeTotal: Int?,
    val typeIndex: Int?,
    val typeCount: Int?,
    val elapsedMillis: Long,
    val etaMillis: Long?,
)
