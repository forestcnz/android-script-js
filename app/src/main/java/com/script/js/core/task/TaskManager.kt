package com.script.js.core.task

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 任务管理：任务（Task）= 入口脚本 task.js + 素材 + 配置，任务名即目录名。
 * 目录结构与配置约定见 docs/DESIGN.md 第 4.1/4.2 节。
 */
object TaskManager {

    data class TaskInfo(val name: String)

    /** 新建任务的脚手架类型（设计 docs/DESIGN.md 第 4.1 节） */
    enum class Scaffold(val label: String, val desc: String) {
        /** 空白：最小可运行骨架 */
        BLANK("空白", "最小骨架：等待无障碍 + 一次坐标点击"),
        /** 签到模板：打开目标应用 → 等按钮 → 点击 → 校验 */
        SIGN_IN("签到模板", "打开目标应用→等按钮→点击→校验（含 designSize）"),
        /** 找图演示：截屏 → 保存/读取模板 → 找图点击 */
        FIND_IMAGE("找图演示", "截屏授权→保存截图→按模板找图并点击（含 images/）")
    }

    /**
     * 任务配置（config.json）。字段缺失时用默认值，解析失败也不影响运行。
     * target：运行前自动拉起的目标应用包名；loop：脚本正常结束后自动循环执行。
     */
    data class TaskConfig(
        val name: String = "",
        val desc: String = "",
        val target: String? = null,
        val loopCount: Int = 1,
        val loopIntervalSec: Int = 0,
        /** 基准分辨率（config.json 的 designSize，如 [1080,1920]）；0 表示未配置 */
        val designWidth: Int = 0,
        val designHeight: Int = 0
    ) {
        /** 是否配置了基准分辨率 */
        fun hasDesignSize(): Boolean = designWidth > 0 && designHeight > 0
    }

    /** 任务根目录（应用专属外部存储，无需权限） */
    fun tasksRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "tasks").apply { mkdirs() }
    }

    /** 任务目录 */
    fun taskDir(context: Context, name: String): File = File(tasksRoot(context), name)

    /** 任务入口脚本（固定名 task.js，点击任务即运行） */
    fun scriptFile(context: Context, name: String): File = File(taskDir(context, name), "task.js")

    /** 列出所有任务（含 task.js 的目录），按名称排序 */
    fun listTasks(context: Context): List<TaskInfo> =
        tasksRoot(context).listFiles { f -> f.isDirectory && !f.name.startsWith(".deleted-") && File(f, "task.js").isFile }
            ?.map { TaskInfo(it.name) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * 新建任务：生成目录 + 脚手架脚本 + 默认配置。
     * 返回 null 表示名称非法（空/含路径符/重名）。
     */
    fun createTask(
        context: Context,
        rawName: String,
        scaffold: Scaffold = Scaffold.BLANK
    ): TaskInfo? {
        val name = rawName.trim()
        if (name.isEmpty() || name.length > 50) return null
        if (name.contains('/') || name.contains('\\')) return null
        val dir = taskDir(context, name)
        if (dir.exists()) return null
        if (!dir.mkdirs()) return null
        File(dir, "task.js").writeText(scaffoldScript(name, scaffold))
        File(dir, "config.json").writeText(scaffoldConfig(name, scaffold))
        // 需要素材的脚手架预建 images/ 目录，避免首次使用还要手动创建
        if (scaffold != Scaffold.BLANK) {
            File(dir, "images").mkdirs()
        }
        return TaskInfo(name)
    }

    /** 重命名整个任务目录，素材与历史保持相对路径；同步迁移定时设置。 */
    @Synchronized
    fun renameTask(context: Context, oldName: String, rawName: String): Result<TaskInfo> = runCatching {
        requireTasksIdle()
        val name = rawName.trim()
        val source = checkedTaskDir(context, oldName)
        val target = checkedTaskDir(context, name)
        check(File(source, "task.js").isFile) { "任务不存在" }
        if (name == oldName) return@runCatching TaskInfo(name)
        check(!target.exists()) { "该任务名称已存在" }
        val configFile = File(source, "config.json")
        val oldConfig = configFile.takeIf { it.isFile }?.readText()
        val config = if (oldConfig != null) JSONObject(oldConfig) else JSONObject()
        writeAtomic(configFile, config.put("name", name).toString(2))
        if (!source.renameTo(target)) {
            if (oldConfig != null) writeAtomic(configFile, oldConfig) else configFile.delete()
            error("无法重命名任务目录")
        }
        if (!ScheduleManager.rename(context, oldName, name)) {
            check(target.renameTo(source)) { "任务已改名为「$name」，但定时迁移失败，请重新设置定时" }
            if (oldConfig != null) writeAtomic(configFile, oldConfig) else configFile.delete()
            error("无法迁移定时设置，已恢复原名称")
        }
        suppressBuiltin(context, oldName)
        TaskInfo(name)
    }

    /** 界面确认后调用；只允许删除任务根目录的直接子任务。 */
    @Synchronized
    fun deleteTask(context: Context, name: String): Result<Unit> = runCatching {
        requireTasksIdle()
        val directory = checkedTaskDir(context, name)
        check(File(directory, "task.js").isFile) { "任务不存在" }
        val removed = File(tasksRoot(context), ".deleted-${java.util.UUID.randomUUID()}")
        check(directory.renameTo(removed)) { "无法删除任务目录" }
        if (!ScheduleManager.remove(context, name)) {
            check(removed.renameTo(directory)) { "定时取消失败，任务文件暂存于 ${removed.name}" }
            error("无法取消该任务的定时设置，已恢复任务")
        }
        suppressBuiltin(context, name)
        if (!removed.deleteRecursively()) android.util.Log.w("ScriptJs", "任务已移除，部分临时文件未清理：${removed.name}")
    }

    private fun requireTasksIdle() {
        check(!com.script.js.core.recording.RecordingService.active && !com.script.js.core.engine.ScriptRunner.state.value.running) {
            "请先完成录制或停止正在运行的任务"
        }
    }

    private fun checkedTaskDir(context: Context, name: String): File {
        require(name.isNotBlank() && name.length <= 50 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }) { "任务名称不合法" }
        val root = tasksRoot(context).canonicalFile
        return File(root, name).canonicalFile.also {
            require(it.parentFile == root) { "任务目录超出允许范围" }
        }
    }

    private fun writeAtomic(target: File, text: String) {
        val file = android.util.AtomicFile(target)
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun suppressedBuiltins(context: Context): Set<String> =
        context.getSharedPreferences("task_management", Context.MODE_PRIVATE)
            .getStringSet("removed_builtins", emptySet())?.toSet() ?: emptySet()

    private fun suppressBuiltin(context: Context, name: String) {
        if (isBuiltin(context, name)) context.getSharedPreferences("task_management", Context.MODE_PRIVATE)
            .edit().putStringSet("removed_builtins", suppressedBuiltins(context) + name).apply()
    }

    /** 读取任务配置（config.json）；文件缺失/解析失败返回默认配置 */
    fun readConfig(context: Context, name: String): TaskConfig {
        val file = File(taskDir(context, name), "config.json")
        if (!file.isFile) return TaskConfig(name = name)
        return try {
            val json = JSONObject(file.readText())
            val loop = json.optJSONObject("loop")
            // designSize: [w, h]（可选）；非数组/长度不足时视为未配置
            val design = json.optJSONArray("designSize")
            val designW = design?.optInt(0, 0)?.coerceAtLeast(0) ?: 0
            val designH = design?.optInt(1, 0)?.coerceAtLeast(0) ?: 0
            TaskConfig(
                name = json.optString("name", name),
                desc = json.optString("desc", ""),
                target = json.optString("target", "").takeIf { it.isNotBlank() },
                loopCount = loop?.optInt("count", 1)?.coerceAtLeast(1) ?: 1,
                loopIntervalSec = loop?.optInt("interval", 0)?.coerceAtLeast(0) ?: 0,
                designWidth = designW,
                designHeight = designH
            )
        } catch (e: Exception) {
            TaskConfig(name = name)
        }
    }

    // ==================== 素材（images/） ====================

    /** 任务素材目录（images/），不存在则创建 */
    fun imagesDir(context: Context, name: String): File =
        File(taskDir(context, name), "images").apply { mkdirs() }

    /** 任务素材图片列表（按名称排序） */
    fun listImages(context: Context, name: String): List<File> =
        imagesDir(context, name).listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()

    /** 删除素材 */
    fun deleteImage(file: File): Boolean = try {
        file.delete()
    } catch (e: Exception) {
        false
    }

    /** 素材信息：文件 + 像素尺寸 + 大小（尺寸按位图头解析，失败为 0） */
    data class ImageInfo(val file: File, val width: Int, val height: Int, val sizeBytes: Long) {
        fun sizeText(): String {
            val kb = sizeBytes / 1024.0
            return if (kb < 1) "$sizeBytes B" else "%.1f KB".format(kb)
        }

        fun dimensionText(): String = if (width > 0 && height > 0) "${width}×$height" else "尺寸未知"
    }

    /** 读取任务全部素材信息（预览/尺寸展示用） */
    fun imageInfos(context: Context, name: String): List<ImageInfo> =
        listImages(context, name).map { file ->
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            } catch (_: Exception) {
            }
            ImageInfo(file, bounds.outWidth.coerceAtLeast(0), bounds.outHeight.coerceAtLeast(0), file.length())
        }

    /**
     * 从相册/文件选择导入素材：把 uri 内容复制到任务 images/ 目录。
     * @return 导入后的文件；失败返回 null
     */
    fun importImage(context: Context, taskName: String, uri: android.net.Uri): File? {
        return try {
        val dir = imagesDir(context, taskName)
        val rawName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "imported.png"
        val base = rawName.substringBeforeLast('.', rawName).replace('/', '_')
        val ext = rawName.substringAfterLast('.', "png").lowercase().let {
            if (it.length in 1..5) it else "png"
        }
        // 不重名
        var candidate = "$base.$ext"
        var i = 1
        while (File(dir, candidate).exists()) {
            candidate = "${base}_$i.$ext"
            i++
        }
        val target = File(dir, candidate)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
            if (target.length() == 0L) {
                target.delete()
                null
            } else {
                target
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 重命名素材；非法名或目标已存在返回 null */
    fun renameImage(file: File, rawName: String): File? {
        val name = rawName.trim().replace('/', '_').replace('\\', '_')
        if (name.isBlank() || name.length > 60) return null
        // 与原扩展名保持一致，避免改坏图片
        val oldExt = file.extension
        val newExt = name.substringAfterLast('.', "")
        val finalName = when {
            newExt.equals(oldExt, ignoreCase = true) -> name
            newExt.isBlank() -> "$name.$oldExt"
            else -> name   // 允许显式改成其他扩展名
        }
        val target = File(file.parentFile, finalName)
        if (target.absolutePath == file.absolutePath) return file
        if (target.exists()) return null
        return try {
            if (file.renameTo(target)) target else null
        } catch (e: Exception) {
            null
        }
    }

    /** 生成不冲突的素材文件名（如 tpl_161530.png） */
    fun suggestImageName(context: Context, taskName: String): String {
        val dir = imagesDir(context, taskName)
        val stamp = java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        var candidate = "tpl_$stamp.png"
        var i = 1
        while (File(dir, candidate).exists()) {
            candidate = "tpl_${stamp}_$i.png"
            i++
        }
        return candidate
    }

    /** 复制 assets/tasks/ 内置任务到用户任务目录；已存在的任务不覆盖（升级保留用户修改） */
    fun ensureBuiltinTasks(context: Context) {
        val am = context.assets
        val root = tasksRoot(context)
        val names = try {
            am.list("tasks") ?: emptyArray()
        } catch (_: Exception) {
            return
        }
        for (name in names) {
            if (name in suppressedBuiltins(context)) continue
            val target = File(root, name)
            if (target.exists()) continue
            copyAssetDir(am, "tasks/$name", target)
        }
    }

    /** 内置任务名列表（assets/tasks/ 下的目录） */
    fun builtinTaskNames(context: Context): List<String> = try {
        context.assets.list("tasks")?.toList() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** 是否为内置任务（可重置为官方版本） */
    fun isBuiltin(context: Context, name: String): Boolean =
        builtinTaskNames(context).contains(name)

    /**
     * 重置内置任务为最新官方版本：删除当前目录后从 assets 重新复制。
     * 用户对该任务的修改会被丢弃（界面需二次确认）。
     * @return 是否成功；非内置任务返回 false
     */
    fun resetBuiltin(context: Context, name: String): Boolean {
        if (!isBuiltin(context, name)) return false
        return try {
            val target = taskDir(context, name)
            if (target.exists() && !target.deleteRecursively()) return false
            copyAssetDir(context.assets, "tasks/$name", target)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun copyAssetDir(am: android.content.res.AssetManager, assetPath: String, target: File) {
        val children = am.list(assetPath) ?: return
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetDir(am, "$assetPath/$child", File(target, child))
        }
    }

    private fun scaffoldScript(name: String, scaffold: Scaffold): String = when (scaffold) {
        Scaffold.BLANK -> blankScript(name)
        Scaffold.SIGN_IN -> signInScript(name)
        Scaffold.FIND_IMAGE -> findImageScript(name)
    }

    private fun scaffoldConfig(name: String, scaffold: Scaffold): String {
        val designSize = if (scaffold == Scaffold.BLANK) "null" else "[1080, 1920]"
        return """
{
    "name": "$name",
    "desc": "${scaffold.desc}",
    "target": "",
    "designSize": $designSize,
    "loop": { "count": 1, "interval": 0 }
}
""".trim() + "\n"
    }

    /** 空白脚手架：最小可运行骨架 */
    private fun blankScript(name: String): String = """
// 任务：$name（空白脚手架）
// 坐标 API 见 docs/API_REFERENCE.md；素材放本任务目录的 images/ 下
auto.waitFor(5000);              // 等待无障碍服务开启（坐标点击依赖）
toastLog("任务开始");

click(400, 300);                 // TODO: 改成你的目标坐标
sleep(1000);

toastLog("任务结束");
""".trim() + "\n"

    /** 签到模板：打开目标应用 → 等按钮 → 点击 → 校验（对齐内置「签到脚手架」） */
    private fun signInScript(name: String): String = """
// 任务：$name（签到模板脚手架）
// 结构：等无障碍 → 申请录屏 → 找签到按钮（失败转坐标兜底）→ 校验成功标记
// 坐标按 config.json 的 designSize(1080x1920) 编写，运行时自动换算；素材放 images/ 下

auto.waitFor(5000);
if (!auto.service) { toastLog("请先开启无障碍服务"); exit(); }

var canFindImage = requestScreenCapture();
log(canFindImage ? "截屏已就绪" : "无截屏能力，改为纯坐标流程");

// 1) 打开目标应用：在 config.json 里填 target 包名，运行前会自动拉起

// 2) 等签到按钮出现并点击；没有模板图时用坐标兜底
var clicked = false;
if (canFindImage) {
    var p = waitImage("images/sign_button.png", 8, 0.5, { threshold: 0.9 });
    if (p) {
        click(p.x, p.y);
        clicked = true;
        log("已按图找到签到按钮: (" + p.x + ", " + p.y + ")");
    } else {
        log("未匹配到签到按钮图，使用坐标兜底");
    }
}
if (!clicked) {
    click(540, 1200);            // TODO: 替换为你的签到按钮坐标
    log("已按坐标点击 (540, 1200)");
}
sleep(1500);

// 3) 校验：等到「已签到」标记图片即视为成功
if (canFindImage) {
    var done = waitImage("images/signed.png", 3, 0.5, { threshold: 0.9 });
    log(done ? "签到成功 ✓" : "未检测到成功标记，请检查素材或坐标");
} else {
    log("无截屏能力，跳过结果校验");
}
""".trim() + "\n"

    /** 找图演示脚手架：截屏 → 保存截图 → 按模板找图并点击 */
    private fun findImageScript(name: String): String = """
// 任务：$name（找图演示脚手架）
// 用法：首次运行保存 images/screen.png，用它裁出目标按钮存为 images/tpl.png，再次运行即可找图点击
// 素材目录 images/ 已自动创建

if (!requestScreenCapture()) {
    toastLog("请先授权屏幕录制");
    exit();
}

var tpl = images.read("images/tpl.png");
if (!tpl) {
    var shot = captureScreen();
    if (!shot) { toastLog("截屏失败"); exit(); }
    images.save(shot, "images/screen.png");
    toastLog("已保存 images/screen.png，请裁出目标按钮存为 images/tpl.png");
    log("屏幕尺寸: " + images.width(shot) + "x" + images.height(shot));
    exit();
}

log("模板尺寸: " + images.width(tpl) + "x" + images.height(tpl));
var p = waitImage("images/tpl.png", 10, 0.5, { threshold: 0.9 });
if (!p) { toastLog("未找到目标图片"); exit(); }

log("找到目标: (" + p.x + ", " + p.y + ")");
if (click(p.x, p.y)) {
    toastLog("已点击 (" + p.x + ", " + p.y + ")");
} else {
    toastLog("点击失败：请确认无障碍服务已开启");
}

if (waitImageVanish("images/tpl.png", 5, 0.5, { threshold: 0.9 })) {
    log("目标已消失，界面已切换");
} else {
    log("目标仍在屏幕上");
}
log("找图演示完成");
""".trim() + "\n"
}
