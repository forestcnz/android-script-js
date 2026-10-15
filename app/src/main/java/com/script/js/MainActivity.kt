package com.script.js

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.script.js.core.engine.ScriptRunner
import com.script.js.core.floaty.ConsoleOverlay
import com.script.js.core.floaty.FloatyManager
import com.script.js.core.floaty.PickerOverlay
import com.script.js.core.gesture.AccessibilityGuard
import com.script.js.core.gesture.ScriptAccessibilityService
import com.script.js.core.image.ScreenCapture
import com.script.js.core.image.SilentCapture
import com.script.js.core.pack.ApkPackager
import com.script.js.core.task.ScheduleManager
import com.script.js.core.task.RunHistory
import com.script.js.core.task.TaskManager
import com.script.js.core.task.TaskZip
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import rikka.shizuku.Shizuku

/**
 * 主界面：权限状态 + 任务列表（新建/运行/停止）+ 运行日志面板。
 * 任务模型见 docs/DESIGN.md 第 4.1 节。
 */
class MainActivity : ComponentActivity() {

    // 权限状态（页面可见期间每秒轮询刷新）
    private var a11yEnabled by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var captureReady by mutableStateOf(false)
    private var tasks by mutableStateOf(emptyList<TaskManager.TaskInfo>())
    private var showCreateDialog by mutableStateOf(false)

    /** 任务操作菜单/素材管理 */
    private var menuTask by mutableStateOf<String?>(null)
    private var assetsTask by mutableStateOf<String?>(null)
    private var assets by mutableStateOf(emptyList<TaskManager.ImageInfo>())
    private var previewAsset by mutableStateOf<TaskManager.ImageInfo?>(null)
    private var renameAsset by mutableStateOf<TaskManager.ImageInfo?>(null)

    /** 定时任务编辑状态 */
    private var scheduleEditing by mutableStateOf<String?>(null)
    private var currentSchedule by mutableStateOf<ScheduleManager.Schedule?>(null)
    private var scheduleType by mutableStateOf(ScheduleManager.Type.ONCE_AFTER_MINUTES)
    private var scheduleValue by mutableStateOf("1")

    /** 保活指引对话框 */
    private var keepAliveDialog by mutableStateOf(false)

    /** 待确认重置的内置任务名 */
    private var resetTask by mutableStateOf<String?>(null)

    /** 运行历史对话框状态 */
    private var historyTask by mutableStateOf<String?>(null)
    private var historyEntries by mutableStateOf(emptyList<RunHistory.Entry>())

    /** 截屏通道对话框状态 */
    private var captureDialog by mutableStateOf(false)
    private var captureModeName by mutableStateOf("AUTO")
    private var captureChannelName by mutableStateOf("-")
    private var rootOk by mutableStateOf(false)
    private var shizukuOk by mutableStateOf(false)

    /** Shizuku 服务在运行但权限尚未授予：界面上给出「申请权限」入口 */
    private var shizukuBinder by mutableStateOf(false)

    private val REQUEST_SHIZUKU = 0x5A17

    /** 各任务的定时设置（列表展示用） */
    private var schedules by mutableStateOf(emptyMap<String, ScheduleManager.Schedule>())

    /** 主界面当前 Tab：0=权限状态 1=工具 2=任务 3=日志 */
    private var mainTab by mutableStateOf(0)

    private val TAB_TITLES = listOf("权限状态", "工具", "任务", "日志")

    /** 任务副标题（config 的 desc / target），仅任务集合变化时刷新 */
    private var taskSubtitles by mutableStateOf(emptyMap<String, String>())

    /** 通知权限（API 33+）：前台保活通知可见才用得到通知栏「停止」 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ScriptRunner.appendLog(
                if (granted) "已授予通知权限：保活通知可见（可直接在通知栏停止脚本）"
                else "未授予通知权限：前台保活通知不可见，请在系统设置中允许通知",
                if (granted) 'I' else 'W'
            )
        }

    /** 从相册/文件导入素材（SAF） */
    private val importImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val task = assetsTask ?: return@registerForActivityResult
            lifecycleScope.launch {
                val file = withContext(Dispatchers.IO) { TaskManager.importImage(this@MainActivity, task, uri) }
                if (file != null) {
                    Toast.makeText(this@MainActivity, "已导入素材：" + file.name, Toast.LENGTH_SHORT).show()
                    ScriptRunner.appendLog("已导入素材: " + file.name)
                    assets = TaskManager.imageInfos(this@MainActivity, task)
                } else {
                    Toast.makeText(this@MainActivity, "导入失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

    /** 导入任务 zip（SAF 选择文件） */
    private val importZipLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val name = withContext(Dispatchers.IO) {
                    TaskZip.importTask(this@MainActivity, uri) { reason ->
                        ScriptRunner.appendLog("导入失败: $reason", 'E')
                    }
                }
                if (name != null) {
                    Toast.makeText(this@MainActivity, "已导入任务：$name", Toast.LENGTH_LONG).show()
                    ScriptRunner.appendLog("已导入任务: $name")
                    tasks = TaskManager.listTasks(this@MainActivity)
                } else {
                    Toast.makeText(this@MainActivity, "导入失败，详见运行日志", Toast.LENGTH_LONG).show()
                }
            }
        }

    /** 系统录屏授权结果 → 交给 ScreenCapture 建立投影 */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            ScreenCapture.onProjectionResult(result.resultCode, result.data)
        }

    /** Shizuku 授权结果：授予后静默截屏立即生效，无需重启脚本 */
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_SHIZUKU) {
                applyShizukuResult(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
        // API 33+ 需要运行时通知权限，否则前台保活通知不可见
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    a11yEnabled = isAccessibilityEnabled()
                    overlayGranted = FloatyManager.hasOverlayPermission(this@MainActivity)
                    // 静默通道（Root/Shizuku）可用时无需录屏授权即视为「截屏可用」
                    captureReady = ScreenCapture.isReady() || ScreenCapture.silentAvailableCached()
                    tasks = TaskManager.listTasks(this@MainActivity)
                    schedules = ScheduleManager.load(this@MainActivity)
                        .filter { it.enabled }
                        .associateBy { it.taskName }
                    delay(1000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 悬浮窗默认开启：已授权则自动显示悬浮球；未授权则首次自动引导去授权
        autoEnableFloaty()
        // 静默通道探测放到后台线程：完成后 chip 立即显示「截屏 ✓」
        ScreenCapture.probeSilentAsync(this)
        // Shizuku 服务可能在应用启动后才被拉起：回到界面时重新挂监听并刷新可用性
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        // 每次回到本界面重新绑定截屏授权：脚本请求时若界面不在前台，先把自己拉到前台再弹窗
        ScreenCapture.bind(this) { intent ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                window.decorView.postDelayed({ projectionLauncher.launch(intent) }, 400)
            } else {
                projectionLauncher.launch(intent)
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        ScreenCapture.unbind(this)
        super.onDestroy()
    }

    /**
     * 悬浮窗「默认开启」：
     * - 已授权：自动显示悬浮球（用户主动隐藏过则尊重其选择，不再自动弹）；
     * - 未授权：首次自动跳转系统悬浮窗授权页（只引导一次，避免每次启动抢屏），
     *   开启后回到界面即自动出现悬浮球。
     */
    private fun autoEnableFloaty() {
        if (FloatyManager.hasOverlayPermission(this)) {
            ScriptRunner.ensureLauncher(this)
            return
        }
        // 权限被撤销时同步收起已失效的浮层，并停止悬浮球保活服务。
        ScriptRunner.ensureLauncher(this)
        if (FloatyManager.wasOverlayAsked(this)) return
        FloatyManager.markOverlayAsked(this)
        ScriptRunner.appendLog("悬浮窗默认开启：已自动打开授权页，开启「显示在其他应用上层」后悬浮球会自动出现", 'I')
        Toast.makeText(this, "请开启悬浮窗权限，开启后悬浮球自动出现", Toast.LENGTH_LONG).show()
        FloatyManager.requestOverlayPermission(this)
    }

    /** 检查本应用的手势无障碍服务是否已开启 */
    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, ScriptAccessibilityService::class.java)
        return enabled.contains(cn.flattenToString()) ||
                enabled.contains(cn.flattenToShortString())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        val runState by ScriptRunner.state.collectAsState()
        val logs by ScriptRunner.logs.collectAsState()
        val launcherEnabled by ScriptRunner.launcherEnabled.collectAsState()

        // 任务副标题（描述/目标应用）：任务集合变化时后台读取一次，避免每秒轮询做文件 IO
        LaunchedEffect(tasks) {
            taskSubtitles = withContext(Dispatchers.IO) {
                tasks.associate { t ->
                    val cfg = TaskManager.readConfig(this@MainActivity, t.name)
                    t.name to cfg.desc.ifBlank { cfg.target?.let { "目标：$it" } ?: "" }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(getString(R.string.app_name)) },
                    actions = {
                        // 运行状态放到标题栏：不再挤占任务列表，且任何位置都能看到/停止
                        if (runState.running) {
                            Text(
                                "运行中：${runState.taskName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            TextButton(onClick = { ScriptRunner.stop() }) { Text("停止") }
                        }
                    }
                )
            }
        ) { padding ->
            // 四个分区各占一个 Tab：权限状态 / 工具 / 任务 / 日志。
            // 竖屏与横屏都只展示一屏内容，避免「设置区挤掉任务列表」；
            // 宽屏下用 widthIn 限制内容宽度并居中，防止行被拉伸得过长。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                TabRow(selectedTabIndex = mainTab) {
                    TAB_TITLES.forEachIndexed { index, title ->
                        Tab(
                            selected = mainTab == index,
                            onClick = { mainTab = index },
                            text = { Text(title, style = MaterialTheme.typography.titleSmall) }
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxHeight()
                    ) {
                        when (mainTab) {
                            0 -> PermissionsTab(runState)
                            1 -> ToolsTab(launcherEnabled)
                            2 -> TaskListSection(runState, Modifier.fillMaxSize())
                            else -> LogsTab(logs)
                        }
                    }
                }
            }
        }

        if (showCreateDialog) {
            CreateTaskDialog(
                onDismiss = { showCreateDialog = false },
                onConfirm = { name, scaffold ->
                    showCreateDialog = false
                    val created = TaskManager.createTask(this, name, scaffold)
                    if (created == null) {
                        Toast.makeText(this, "创建失败：名称非法或已存在", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "已创建「" + created.name + "」（" + scaffold.label + "）", Toast.LENGTH_SHORT).show()
                        ScriptRunner.appendLog("已创建任务: " + created.name + "（脚手架：" + scaffold.label + "）")
                        tasks = TaskManager.listTasks(this)
                    }
                }
            )
        }

        menuTask?.let { name ->
            TaskMenuDialog(
                taskName = name,
                onDismiss = { menuTask = null },
                onEditScript = {
                    menuTask = null
                    // 带上上次运行的出错行，打开编辑器即定位/高亮
                    val err = ScriptRunner.lastError.value?.takeIf { it.taskName == name }
                    startActivity(
                        Intent(this, ScriptEditorActivity::class.java)
                            .putExtra(ScriptEditorActivity.EXTRA_TASK, name)
                            .putExtra(ScriptEditorActivity.EXTRA_ERROR_LINE, err?.line ?: 0)
                            .putExtra(ScriptEditorActivity.EXTRA_ERROR_MSG, err?.message)
                    )
                },
                onCaptureTemplate = {
                    menuTask = null
                    startActivity(
                        Intent(this, TemplateCaptureActivity::class.java)
                            .putExtra(TemplateCaptureActivity.EXTRA_TASK, name)
                    )
                },
                onManageAssets = {
                    menuTask = null
                    assetsTask = name
                    assets = TaskManager.imageInfos(this, name)
                },
                onExportApk = {
                    menuTask = null
                    exportApk(name)
                },
                onReset = {
                    menuTask = null
                    resetTask = name
                },
                onHistory = {
                    menuTask = null
                    historyTask = name
                    historyEntries = RunHistory.read(this, name)
                },
                onExportZip = {
                    menuTask = null
                    exportZip(name)
                },
                onSchedule = {
                    menuTask = null
                    scheduleEditing = name
                    currentSchedule = ScheduleManager.scheduleOf(this, name)
                    scheduleType = currentSchedule?.type ?: ScheduleManager.Type.ONCE_AFTER_MINUTES
                    scheduleValue = currentSchedule?.let { s ->
                        when (s.type) {
                            ScheduleManager.Type.ONCE_AFTER_MINUTES -> s.delayMinutes.toString()
                            ScheduleManager.Type.EVERY_MINUTES -> s.intervalMinutes.toString()
                            else -> "%02d:%02d".format(s.hour, s.minute)
                        }
                    } ?: "1"
                }
            )
        }

        previewAsset?.let { info ->
            AssetPreviewDialog(info = info, onDismiss = { previewAsset = null })
        }

        renameAsset?.let { info ->
            RenameAssetDialog(
                info = info,
                onDismiss = { renameAsset = null },
                onConfirm = { newName ->
                    val renamed = TaskManager.renameImage(info.file, newName)
                    renameAsset = null
                    val task = assetsTask
                    if (renamed != null && task != null) {
                        Toast.makeText(this, "已重命名为 " + renamed.name, Toast.LENGTH_SHORT).show()
                        assets = TaskManager.imageInfos(this, task)
                    } else {
                        Toast.makeText(this, "重命名失败（名称非法或已存在）", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        resetTask?.let { name ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { resetTask = null },
                title = { Text("重置内置任务") },
                text = {
                    Text(
                        "将用官方最新版本覆盖「" + name + "」，当前对该任务的修改会被丢弃。",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        resetTask = null
                        val ok = TaskManager.resetBuiltin(this, name)
                        Toast.makeText(
                            this,
                            if (ok) "已重置为官方版本：" + name else "重置失败",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (ok) {
                            ScriptRunner.appendLog("已重置内置任务: " + name)
                            tasks = TaskManager.listTasks(this)
                        }
                    }) { Text("重置") }
                },
                dismissButton = {
                    TextButton(onClick = { resetTask = null }) { Text("取消") }
                }
            )
        }

        historyTask?.let { name ->
            RunHistoryDialog(
                taskName = name,
                entries = historyEntries,
                onDismiss = { historyTask = null },
                onClear = {
                    RunHistory.clear(this, name)
                    historyEntries = emptyList()
                    Toast.makeText(this, "已清空运行历史", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (keepAliveDialog) {
            KeepAliveDialog(
                onDismiss = { keepAliveDialog = false },
                onOpenAppSettings = {
                    keepAliveDialog = false
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:" + packageName))
                    )
                }
            )
        }

        if (captureDialog) {
            CaptureChannelDialog(
                onDismiss = { captureDialog = false },
                onSelect = { mode -> applyCaptureMode(mode) }
            )
        }

        scheduleEditing?.let { name ->
            ScheduleDialog(
                taskName = name,
                current = currentSchedule,
                type = scheduleType,
                value = scheduleValue,
                onTypeChange = { t ->
                    scheduleType = t
                    scheduleValue = when (t) {
                        ScheduleManager.Type.DAILY_AT -> "08:00"
                        ScheduleManager.Type.EVERY_MINUTES -> "30"
                        else -> "1"
                    }
                },
                onValueChange = { scheduleValue = it },
                onDismiss = { scheduleEditing = null },
                onSave = {
                    val schedule = buildSchedule(name)
                    if (schedule == null) {
                        Toast.makeText(this, "参数不合法", Toast.LENGTH_SHORT).show()
                    } else if (ScheduleManager.upsert(this, schedule)) {
                        Toast.makeText(this, "已设置：${schedule.describe()}", Toast.LENGTH_LONG).show()
                        scheduleEditing = null
                    } else {
                        Toast.makeText(this, "设置失败", Toast.LENGTH_SHORT).show()
                    }
                },
                onCancelSchedule = {
                    ScheduleManager.remove(this, name)
                    Toast.makeText(this, "已取消定时", Toast.LENGTH_SHORT).show()
                    scheduleEditing = null
                }
            )
        }

        assetsTask?.let { name ->
            AssetsDialog(
                taskName = name,
                images = assets,
                onDismiss = { assetsTask = null },
                onRefresh = { assets = TaskManager.imageInfos(this, name) },
                onImport = {
                    importImageLauncher.launch(arrayOf("image/*"))
                },
                onPreview = { info -> previewAsset = info },
                onRename = { info -> renameAsset = info },
                onDelete = { info ->
                    TaskManager.deleteImage(info.file)
                    assets = TaskManager.imageInfos(this, name)
                }
            )
        }
    }

    /** 任务行：名称 + 运行/定时状态 + 运行/停止 + 更多操作 */
    @Composable
    private fun TaskRow(task: TaskManager.TaskInfo, runState: ScriptRunner.RunState) {
        val running = runState.running && runState.taskName == task.name
        val schedule = schedules[task.name]
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.bodyLarge)
                    // 副标题优先级：运行中 > 定时 > 任务描述/目标应用
                    val (subtitle, highlight) = when {
                        running -> "运行中…" to true
                        schedule != null -> "定时：${schedule.describe()}" to true
                        else -> (taskSubtitles[task.name] ?: "") to false
                    }
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (highlight) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (running) {
                    TextButton(onClick = { ScriptRunner.stop() }) { Text("停止") }
                } else {
                    Button(onClick = { runTask(task.name) }, enabled = !runState.running) {
                        Text("运行")
                    }
                }
                // 固定 40dp 触控区：既保证可点面积，也避免图标贴边被卡片裁切
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = !runState.running) { menuTask = task.name },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "⋮",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (runState.running) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    /** 任务操作菜单：编辑脚本 / 定时任务 / 模板截取 / 素材管理 */
    @Composable
    private fun TaskMenuDialog(
        taskName: String,
        onDismiss: () -> Unit,
        onEditScript: () -> Unit,
        onCaptureTemplate: () -> Unit,
        onManageAssets: () -> Unit,
        onSchedule: () -> Unit,
        onExportApk: () -> Unit,
        onExportZip: () -> Unit,
        onHistory: () -> Unit,
        onReset: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(taskName) },
            text = {
                Column {
                    TextButton(onClick = onEditScript) { Text("编辑脚本（task.js）") }
                    TextButton(onClick = onSchedule) {
                        Text(
                            if (schedules.containsKey(taskName)) "定时任务（${schedules[taskName]?.describe()}）"
                            else "定时任务（未设置）"
                        )
                    }
                    if (TaskManager.isBuiltin(this@MainActivity, taskName)) {
                        TextButton(onClick = onReset) { Text("重置为官方版本") }
                    }
                    TextButton(onClick = onHistory) {
                        Text("运行历史（" + RunHistory.read(this@MainActivity, taskName).size + " 条）")
                    }
                    TextButton(onClick = onExportZip) { Text("导出 ZIP（脚本+素材+配置）") }
                    TextButton(onClick = onExportApk) { Text("导出独立 APK（内嵌脚本与素材）") }
                    TextButton(onClick = onCaptureTemplate) { Text("截取模板（框选屏幕区域存为素材）") }
                    TextButton(onClick = onManageAssets) { Text("素材管理") }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }

    /** 把对话框输入解析为定时设置；参数非法返回 null */
    private fun buildSchedule(taskName: String): ScheduleManager.Schedule? = when (scheduleType) {
        ScheduleManager.Type.ONCE_AFTER_MINUTES -> {
            val m = scheduleValue.trim().toIntOrNull() ?: return null
            if (m <= 0) null
            else ScheduleManager.Schedule(taskName, scheduleType, delayMinutes = m)
        }

        ScheduleManager.Type.EVERY_MINUTES -> {
            val m = scheduleValue.trim().toIntOrNull() ?: return null
            if (m <= 0) null
            else ScheduleManager.Schedule(taskName, scheduleType, intervalMinutes = m)
        }

        ScheduleManager.Type.DAILY_AT -> {
            val parts = scheduleValue.trim().split(":")
            val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val min = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
            if (h !in 0..23 || min !in 0..59) null
            else ScheduleManager.Schedule(taskName, scheduleType, hour = h, minute = min)
        }
    }

    /** 定时任务设置对话框：选类型 + 填参数，可保存或取消定时 */
    @Composable
    private fun ScheduleDialog(
        taskName: String,
        current: ScheduleManager.Schedule?,
        type: ScheduleManager.Type,
        value: String,
        onTypeChange: (ScheduleManager.Type) -> Unit,
        onValueChange: (String) -> Unit,
        onDismiss: () -> Unit,
        onSave: () -> Unit,
        onCancelSchedule: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("定时任务 · $taskName") },
            text = {
                Column {
                    Text(
                        current?.let { "当前：${it.describe()}" } ?: "当前：未设置",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(modifier = Modifier.padding(vertical = 8.dp)) {
                        listOf(
                            ScheduleManager.Type.ONCE_AFTER_MINUTES to "一次",
                            ScheduleManager.Type.EVERY_MINUTES to "间隔",
                            ScheduleManager.Type.DAILY_AT to "每天"
                        ).forEach { (t, label) ->
                            TextButton(onClick = { onTypeChange(t) }) {
                                Text(
                                    if (type == t) "● $label" else label,
                                    color = if (type == t) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        label = {
                            Text(
                                when (type) {
                                    ScheduleManager.Type.ONCE_AFTER_MINUTES -> "多少分钟后执行一次"
                                    ScheduleManager.Type.EVERY_MINUTES -> "每隔多少分钟（最少 15）"
                                    ScheduleManager.Type.DAILY_AT -> "每天几点（HH:mm）"
                                }
                            )
                        }
                    )
                    Text(
                        when (type) {
                            ScheduleManager.Type.ONCE_AFTER_MINUTES -> "到点自动运行一次"
                            ScheduleManager.Type.EVERY_MINUTES -> "每隔 N 分钟运行一次，首次在 N 分钟后"
                            ScheduleManager.Type.DAILY_AT -> "每天该时刻自动运行"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
            dismissButton = {
                Row {
                    if (current != null) {
                        TextButton(onClick = onCancelSchedule) { Text("取消定时") }
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        )
    }

    /** 素材管理：列出任务 images/ 下的图片，可删除 */
    @Composable
    private fun AssetsDialog(
        taskName: String,
        images: List<TaskManager.ImageInfo>,
        onDismiss: () -> Unit,
        onRefresh: () -> Unit,
        onImport: () -> Unit,
        onPreview: (TaskManager.ImageInfo) -> Unit,
        onRename: (TaskManager.ImageInfo) -> Unit,
        onDelete: (TaskManager.ImageInfo) -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("素材 · $taskName") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = onImport) { Text("+ 从相册导入") }
                    if (images.isEmpty()) {
                        Text("images/ 目录暂无图片，可用「截取模板」制作或从相册导入", style = MaterialTheme.typography.bodySmall)
                    } else {
                        images.take(8).forEach { info ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onPreview(info) }
                                ) {
                                    Text(info.file.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        info.dimensionText() + " · " + info.sizeText() + "（点击预览）",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(onClick = { onRename(info) }) { Text("重命名") }
                                TextButton(onClick = { onDelete(info) }) { Text("删除") }
                            }
                        }
                        if (images.size > 8) {
                            Text("共 ${images.size} 个，仅显示前 8 个", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = onRefresh) { Text("刷新") }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }

    /** 素材预览：显示图片与尺寸/大小信息 */
    @Composable
    private fun AssetPreviewDialog(
        info: TaskManager.ImageInfo,
        onDismiss: () -> Unit
    ) {
        val bitmap = remember(info.file.absolutePath) {
            try {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(info.file.absolutePath, bounds)
                var scale = 1
                while (bounds.outWidth / scale > 720 || bounds.outHeight / scale > 720) scale *= 2
                android.graphics.BitmapFactory.decodeFile(
                    info.file.absolutePath,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
                )
            } catch (e: Exception) {
                null
            }
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(info.file.name) },
            text = {
                Column {
                    Text(
                        info.dimensionText() + " · " + info.sizeText(),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = info.file.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                    } else {
                        Text("无法解码该图片（可能不是有效的图片文件）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }

    /** 素材重命名对话框 */
    @Composable
    private fun RenameAssetDialog(
        info: TaskManager.ImageInfo,
        onDismiss: () -> Unit,
        onConfirm: (String) -> Unit
    ) {
        var name by remember(info.file.absolutePath) { mutableStateOf(info.file.name) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("重命名素材") },
            text = {
                Column {
                    Text(info.dimensionText() + " · " + info.sizeText(), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }

    /** 分区卡片标题：统一「标题 + 可选说明」的层级，替代散落的裸文本 */
    @Composable
    private fun SectionTitle(title: String, hint: String? = null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (hint != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    /** 权限状态卡片：每项一行（名称 + 用途 + 状态/动作），触控区 ≥48dp */
    @Composable
    private fun PermissionCard(compact: Boolean) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                SectionTitle("权限状态", if (compact) null else "点击未开启项去授权")
                PermissionRow(
                    title = "手势（无障碍）",
                    desc = "坐标点击 / 滑动 / 多指手势",
                    ok = a11yEnabled,
                    okText = "已开启",
                    actionText = "去开启",
                    compact = compact
                ) {
                    if (a11yEnabled) keepAliveDialog = true
                    else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                PermissionRow(
                    title = "悬浮窗",
                    desc = "悬浮球 / 悬浮日志 / 拾取器",
                    ok = overlayGranted,
                    okText = "已授权",
                    actionText = "去授权",
                    compact = compact
                ) {
                    if (overlayGranted) {
                        Toast.makeText(this@MainActivity, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
                    } else {
                        FloatyManager.requestOverlayPermission(this@MainActivity)
                    }
                }
                PermissionRow(
                    title = "截屏",
                    desc = if (captureReady) "找图找色 / OCR 可用" else "未授权（可用 Root/Shizuku 静默通道）",
                    ok = captureReady,
                    okText = "可用",
                    actionText = "设置通道",
                    compact = compact
                ) { openCaptureDialog() }
            }
        }
    }

    /**
     * 权限行：状态点 + 名称/用途 + 右侧动作，整行可点。
     * [compact] 下已授权项压成单行（用途说明只在未开启时展示），把竖向空间让给任务列表。
     */
    @Composable
    private fun PermissionRow(
        title: String,
        desc: String,
        ok: Boolean,
        okText: String,
        actionText: String,
        compact: Boolean = false,
        onClick: () -> Unit
    ) {
        val singleLine = compact && ok
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = if (singleLine) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dotColor = if (ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!singleLine) {
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            Text(
                if (ok) okText else actionText,
                style = MaterialTheme.typography.labelLarge,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
        }
    }

    /**
     * 工具卡片：拾取/日志/悬浮球开关。
     * [compact] 下改为 2×2 网格（说明限一行），比逐行列表省约一半高度。
     */
    @Composable
    private fun ToolsCard(launcherEnabled: Boolean, compact: Boolean) {
        val tools = listOf<Triple<String, String, () -> Unit>>(
            Triple("取坐标", "点屏幕取点，生成 click(x, y)", { showPicker(PickerOverlay.Mode.POINT) }),
            Triple("取颜色", "生成 findColor 颜色串（依赖截屏）", { showPicker(PickerOverlay.Mode.COLOR) }),
            Triple("去截图", "拖拽框选屏幕区域并重命名保存（依赖截屏）", { showPicker(PickerOverlay.Mode.SCREENSHOT) }),
            Triple("悬浮日志", "浮在屏幕上的实时日志面板", { toggleConsole() }),
            Triple(
                if (launcherEnabled) "隐藏悬浮球" else "显示悬浮球",
                "运行/停止入口，可拖动贴边"
            ) {
                if (!overlayGranted) {
                    FloatyManager.requestOverlayPermission(this@MainActivity)
                } else {
                    ScriptRunner.toggleLauncher(this@MainActivity)
                }
            }
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                SectionTitle("工具")
                if (compact) {
                    tools.chunked(2).forEach { rowTools ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            rowTools.forEach { (title, desc, action) ->
                                ToolCell(
                                    title = title,
                                    desc = desc,
                                    modifier = Modifier.weight(1f),
                                    onClick = action
                                )
                            }
                            // 奇数个工具时补空位，避免最后一格被拉伸
                            if (rowTools.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    tools.forEach { (title, desc, action) ->
                        ToolRow(title, desc, action)
                    }
                }
            }
        }
    }

    /** 工具网格单元：标题 + 单行说明 */
    @Composable
    private fun ToolCell(
        title: String,
        desc: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Row(
            modifier = modifier
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    /** 工具行：名称 + 用途 + 尾部箭头 */
    @Composable
    private fun ToolRow(title: String, desc: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    /** 任务列表区：标题行（数量 + 新建/导入） + 列表 */
    @Composable
    private fun TaskListSection(runState: ScriptRunner.RunState, modifier: Modifier = Modifier) {
        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("任务 (${tasks.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { showCreateDialog = true }) { Text("+ 新建任务") }
                TextButton(onClick = {
                    importZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }) { Text("导入 ZIP") }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(tasks, key = { it.name }) { task ->
                    TaskRow(task, runState)
                }
                if (tasks.isEmpty()) {
                    item {
                        Text(
                            "暂无任务，点「+ 新建任务」创建（可选空白/签到模板/找图演示脚手架）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }

    /** Tab 1：权限状态 —— 三项能力 + 当前运行状态 */
    @Composable
    private fun PermissionsTab(runState: ScriptRunner.RunState) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PermissionCard(compact = false)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    SectionTitle("运行状态", if (runState.running) "任务执行中" else "空闲")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                runState.taskName ?: "当前无任务运行",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                if (runState.running) "可在标题栏或悬浮球停止" else "在「任务」页选择任务运行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (runState.running) {
                            TextButton(onClick = { ScriptRunner.stop() }) { Text("停止") }
                        }
                    }
                }
            }
        }
    }

    /** Tab 2：工具 —— 取坐标/取色/悬浮日志/悬浮球 + 截屏通道入口 */
    @Composable
    private fun ToolsTab(launcherEnabled: Boolean) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolsCard(launcherEnabled, compact = false)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    SectionTitle("截屏通道", "Root/Shizuku 静默截屏优先")
                    ToolRow(
                        "截屏通道设置",
                        "模式：$captureModeName    实际通道：$captureChannelName"
                    ) { openCaptureDialog() }
                }
            }
        }
    }

    /** Tab 3：任务列表 + 运行日志（日志占满剩余空间，便于观察） */
    @Composable
    private fun LogsTab(logs: List<ScriptRunner.LogEntry>) {
        Card(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("运行日志 (${logs.size})", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.weight(1f))
                    if (logs.isNotEmpty()) {
                        TextButton(onClick = { ScriptRunner.clearLogs() }) { Text("清空") }
                    }
                }
                HorizontalDivider()
                if (logs.isEmpty()) {
                    Text(
                        "暂无日志：运行任务后这里会显示实时输出",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                } else {
                    LogPanel(logs, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }

    private fun runTask(name: String) {
        // 悬浮条是运行期停止入口，必须先有悬浮窗权限；
        // 无障碍服务未开启时脚本内 auto.waitFor 会等待/引导开启
        if (!overlayGranted) {
            FloatyManager.requestOverlayPermission(this)
            return
        }
        ScriptRunner.run(this, name)
    }

    /** 保活指引：降低无障碍服务被系统回收的概率（各厂商后台限制差异较大） */
    @Composable
    private fun KeepAliveDialog(onDismiss: () -> Unit, onOpenAppSettings: () -> Unit) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("保活指引") },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    listOf(
                        "① 电池优化：在应用详情中把本应用设为「不受限制」（不优化电池）",
                        "② 自启动/后台运行：允许自启动与后台弹出界面（部分厂商需单独开启）",
                        "③ 最近任务里锁定本应用，避免被一键清理带走",
                        "④ 无障碍服务被系统回收后，本应用会提示并在「设置 → 无障碍」重新开启即可",
                        "⑤ 需要长时间跑图色脚本时，建议常驻前台并保持屏幕常亮"
                    ).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        AccessibilityGuard.openAccessibilitySettings(this@MainActivity)
                    }) { Text("重试开启无障碍") }
                    TextButton(onClick = onOpenAppSettings) { Text("应用设置") }
                }
            }
        )
    }

    /** 运行历史对话框：时间 / 耗时 / 退出原因 / 异常信息与堆栈 */
    @Composable
    private fun RunHistoryDialog(
        taskName: String,
        entries: List<RunHistory.Entry>,
        onDismiss: () -> Unit,
        onClear: () -> Unit
    ) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("运行历史 · $taskName") },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    if (entries.isEmpty()) {
                        Text("暂无运行记录", style = MaterialTheme.typography.bodySmall)
                    }
                    entries.forEach { e ->
                        val color = when (e.outcome) {
                            RunHistory.Outcome.ERROR -> MaterialTheme.colorScheme.error
                            RunHistory.Outcome.STOPPED -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            e.time + "  " + e.outcome.label + "  " + e.durationText() + "  " + e.source,
                            style = MaterialTheme.typography.bodySmall,
                            color = color
                        )
                        e.message?.let { msg ->
                            Text(
                                "    " + msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        e.stack?.let { stack ->
                            // 只展示前几行堆栈，避免对话框过长
                            Text(
                                stack.lineSequence().take(6).joinToString("  ←  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            dismissButton = {
                TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text("清空历史") }
            }
        )
    }

    /** 导出任务 zip（脚本+素材+配置），支持一键分享 */
    private fun exportZip(taskName: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { TaskZip.exportTask(this@MainActivity, taskName) }
            if (result == null) {
                Toast.makeText(this@MainActivity, "导出失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            ScriptRunner.appendLog("任务已导出: " + result.file.absolutePath)
            val sizeText = if (result.sizeBytes < 1024) result.sizeBytes.toString() + " B"
            else (result.sizeBytes / 1024).toString() + " KB"
            val message = "ZIP: " + result.file.absolutePath + "\n大小: " + sizeText
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("导出成功")
                .setMessage(message)
                .setPositiveButton("分享") { _, _ -> shareZip(result.file) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    /** 通过 FileProvider 分享导出的 zip */
    private fun shareZip(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享任务包"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    /** 导出独立 APK：后台打包（注入脚本与素材 + 重签名），进度写入运行日志 */
    private fun exportApk(taskName: String) {
        Toast.makeText(this, "开始打包：$taskName", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApkPackager.packageTask(this@MainActivity, taskName) { line ->
                    ScriptRunner.appendLog("打包: $line")
                }
            }
            if (result != null) {
                val message = "APK: " + result.apk.absolutePath +
                        "\n大小: " + (result.sizeBytes / 1024 / 1024) + " MB"
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("导出成功")
                    .setMessage(message)
                    .setPositiveButton("知道了", null)
                    .show()
            } else {
                Toast.makeText(this@MainActivity, "打包失败，详见运行日志", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 打开截屏通道对话框：后台探测可用性，避免主线程阻塞 */
    private fun openCaptureDialog() {
        captureDialog = true
        captureModeName = ScreenCapture.mode(this).name
        captureChannelName = "探测中…"
        lifecycleScope.launch {
            val probe = withContext(Dispatchers.IO) {
                CaptureProbe(
                    root = ScreenCapture.rootAvailable(forceRefresh = true),
                    shizuku = ScreenCapture.shizukuAvailable(forceRefresh = true),
                    binder = SilentCapture.shizukuBinderAlive(),
                    channel = ScreenCapture.activeChannel(this@MainActivity).name
                )
            }
            rootOk = probe.root
            shizukuOk = probe.shizuku
            shizukuBinder = probe.binder
            captureChannelName = probe.channel
        }
    }

    /** 通道探测结果（后台线程 → UI 状态） */
    private data class CaptureProbe(
        val root: Boolean,
        val shizuku: Boolean,
        val binder: Boolean,
        val channel: String
    )

    /** 申请 Shizuku 权限（结果走 [shizukuPermissionListener] 回调） */
    private fun requestShizukuPermission() {
        when {
            !shizukuBinder -> {
                Toast.makeText(this, "Shizuku 服务未运行，请先启动 Shizuku", Toast.LENGTH_LONG).show()
                ScriptRunner.appendLog("Shizuku 服务未运行：请先启动 Shizuku（root 设备可直接在 Shizuku 内启动）", 'W')
            }
            SilentCapture.requestShizukuPermission(REQUEST_SHIZUKU) -> {
                Toast.makeText(this, "已弹出 Shizuku 授权请求", Toast.LENGTH_SHORT).show()
            }
            else -> {
                // 已被拒绝过：Shizuku 不再弹窗，需到 Shizuku 应用内授权
                Toast.makeText(this, "请在 Shizuku 应用内为本应用授权", Toast.LENGTH_LONG).show()
                ScriptRunner.appendLog("Shizuku 权限此前被拒绝，请到 Shizuku 应用内 → 已授权应用 → 本应用 手动授权", 'W')
            }
        }
    }

    /** Shizuku 授权结果：刷新通道状态（授权成功后静默截屏立即可用） */
    private fun applyShizukuResult(granted: Boolean) {
        shizukuOk = granted
        ScreenCapture.shizukuAvailable(forceRefresh = true)
        captureChannelName = ScreenCapture.activeChannel(this).name
        if (granted) {
            ScriptRunner.appendLog("Shizuku 权限已授予：静默截屏可用（实际通道 " + captureChannelName + "）")
            Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
        } else {
            ScriptRunner.appendLog("Shizuku 权限被拒绝：将降级 MediaProjection", 'W')
            Toast.makeText(this, "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    /** 应用通道模式并刷新显示 */
    private fun applyCaptureMode(mode: ScreenCapture.Mode) {
        ScreenCapture.setMode(this, mode)
        captureModeName = mode.name
        Toast.makeText(this, "截屏通道模式：" + mode.name, Toast.LENGTH_SHORT).show()
        openCaptureDialog()
    }

    /** 截屏通道设置对话框：Root/Shizuku 为静默截屏，均不可用时降级 MediaProjection */
    @Composable
    private fun CaptureChannelDialog(
        onDismiss: () -> Unit,
        onSelect: (ScreenCapture.Mode) -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("截屏通道") },
            text = {
                Column {
                    Text("模式：$captureModeName    实际通道：$captureChannelName", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Root：" + (if (rootOk) "可用" else "不可用") +
                                "    Shizuku：" + (if (shizukuOk) "可用"
                                else if (shizukuBinder) "未授权" else "不可用"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Root/Shizuku 为静默截屏（无需授权弹窗）；均不可用时使用 MediaProjection（需授权一次）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        listOf(
                            ScreenCapture.Mode.AUTO to "自动",
                            ScreenCapture.Mode.MEDIAPROJECTION to "投影",
                            ScreenCapture.Mode.ROOT to "Root",
                            ScreenCapture.Mode.SHIZUKU to "Shizuku"
                        ).forEach { (m, label) ->
                            TextButton(onClick = { onSelect(m) }) {
                                Text(
                                    (if (captureModeName == m.name) "● " else "") + label,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        TextButton(onClick = {
                            Thread { ScreenCapture.requestBlocking() }.start()
                        }) { Text("立即授权/测试截屏") }
                        if (!shizukuOk) {
                            TextButton(onClick = { requestShizukuPermission() }) {
                                Text("申请 Shizuku 权限", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }

    /** 开关悬浮控制台（可滚动日志面板，需悬浮窗权限） */
    private fun toggleConsole() {
        if (!overlayGranted) {
            FloatyManager.requestOverlayPermission(this)
            return
        }
        ConsoleOverlay.toggle(this)
    }

    /** 打开坐标拾取器/取色器（需要悬浮窗权限；取色还需截屏授权） */
    private fun showPicker(mode: PickerOverlay.Mode) {
        if (!overlayGranted) {
            FloatyManager.requestOverlayPermission(this)
            return
        }
        if (mode == PickerOverlay.Mode.COLOR && !ScreenCapture.captureAvailable(this)) {
            // 取色依赖截屏：先弹授权，成功后再打开拾取层
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { ScreenCapture.requestBlocking() }
                if (ok) {
                    PickerOverlay.show(this@MainActivity, mode)
                } else {
                    Toast.makeText(this@MainActivity, "取色需要截屏授权", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        PickerOverlay.show(this, mode)
    }

    @Composable
    private fun LogPanel(
        logs: List<ScriptRunner.LogEntry>,
        modifier: Modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        val listState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            items(logs) { entry ->
                Text(
                    text = "${entry.time} ${entry.msg}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = when (entry.level) {
                        'E' -> MaterialTheme.colorScheme.error
                        'W' -> MaterialTheme.colorScheme.tertiary
                        'D' -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }

    @Composable
    private fun CreateTaskDialog(
        onDismiss: () -> Unit,
        onConfirm: (String, TaskManager.Scaffold) -> Unit
    ) {
        var name by mutableStateOf("")
        var scaffold by mutableStateOf(TaskManager.Scaffold.BLANK)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("新建任务") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("任务名称") },
                        singleLine = true
                    )
                    Text(
                        "脚手架",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                    TaskManager.Scaffold.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { scaffold = option }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (if (scaffold == option) "● " else "○ ") + option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (scaffold == option) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            "    " + option.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(name, scaffold) }, enabled = name.isNotBlank()) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )
    }
}
