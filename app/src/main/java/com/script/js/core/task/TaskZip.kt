package com.script.js.core.task

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 任务整体打包与迁移：把任务目录（脚本 + 配置 + 素材）打成 zip，或从 zip 导入为新任务。
 * 对应设计 docs/DESIGN.md 第 4.1 节「导入导出」。
 *
 * 安全要点：
 * - 导入时拒绝包含 `..` / 绝对路径的条目（防 zip-slip）；
 * - 任务名做净化，且同名任务已存在时拒绝导入，避免覆盖用户数据。
 */
object TaskZip {

    private const val TAG = "ScriptJs"
    private const val MANIFEST_ENTRY = "task.js"

    data class Result(val file: File, val sizeBytes: Long)

    /** 导出目录（外部私有目录，便于分享与取出） */
    fun exportDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "zip").apply { mkdirs() }

    /** 导出任务为 zip；失败返回 null */
    fun exportTask(context: Context, taskName: String): Result? {
        val taskDir = TaskManager.taskDir(context, taskName)
        if (!taskDir.isDirectory) {
            Log.w(TAG, "任务目录不存在: ${taskDir.absolutePath}")
            return null
        }
        val out = File(exportDir(context), "$taskName.zip")
        return try {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                taskDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val rel = file.relativeTo(taskDir).path.replace('\\', '/')
                        zip.putNextEntry(ZipEntry(rel))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            Log.i(TAG, "任务已导出: ${out.absolutePath} (${out.length()} 字节)")
            Result(out, out.length())
        } catch (e: Exception) {
            Log.w(TAG, "导出失败: ${e.message}")
            out.delete()
            null
        }
    }

    /**
     * 从 zip 导入任务；返回新任务名，失败返回 null 并给出原因（通过 [onError]）。
     * 兼容两种情况：zip 根目录直接是任务内容，或内容位于单层子目录下。
     */
    fun importTask(context: Context, uri: Uri, onError: (String) -> Unit = {}): String? {
        return try {
            // 先解到临时目录再校验，避免半成品污染任务目录
            val temp = File(context.cacheDir, "import_tmp").apply {
                deleteRecursively()
                mkdirs()
            }
            var entryCount = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/')
                        if (!entry.isDirectory) {
                            if (isUnsafe(name)) {
                                onError("压缩包包含非法路径: $name")
                                temp.deleteRecursively()
                                return null
                            }
                            val target = File(temp, name)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                            entryCount++
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: run {
                onError("无法读取所选文件")
                return null
            }
            if (entryCount == 0) {
                onError("压缩包为空")
                temp.deleteRecursively()
                return null
            }

            // 允许内容位于单层子目录
            val root = resolveRoot(temp)
            if (!File(root, MANIFEST_ENTRY).isFile) {
                onError("不是有效的任务包（缺少 $MANIFEST_ENTRY）")
                temp.deleteRecursively()
                return null
            }

            val name = sanitize(readName(root) ?: uri.lastPathSegment.orEmpty().substringAfterLast('/').removeSuffix(".zip"))
                ?: run {
                    onError("无法确定任务名")
                    temp.deleteRecursively()
                    return null
                }
            val target = TaskManager.taskDir(context, name)
            if (target.exists()) {
                onError("任务「$name」已存在，请先删除或重命名")
                temp.deleteRecursively()
                return null
            }
            if (!root.renameTo(target)) {
                // rename 跨目录失败时退化为复制
                root.copyRecursively(target, overwrite = false)
                root.deleteRecursively()
            }
            temp.deleteRecursively()
            Log.i(TAG, "任务已导入: $name -> ${target.absolutePath}")
            name
        } catch (e: Exception) {
            Log.w(TAG, "导入失败: ${e.message}")
            onError("导入失败: ${e.message}")
            null
        }
    }

    /** 若内容都在单层子目录内，返回该子目录；否则返回 temp */
    private fun resolveRoot(temp: File): File {
        val children = temp.listFiles() ?: return temp
        if (File(temp, MANIFEST_ENTRY).isFile) return temp
        val dirs = children.filter { it.isDirectory }
        if (dirs.size == 1 && children.size == 1) {
            return dirs[0]
        }
        return temp
    }

    private fun readName(root: File): String? = try {
        val config = File(root, "config.json")
        if (!config.isFile) {
            null
        } else {
            JSONObject(config.readText()).optString("name", "").takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    /** 任务名净化：去掉路径分隔符与首尾空白 */
    private fun sanitize(raw: String): String? {
        val name = raw.trim().replace('/', '_').replace('\\', '_')
        if (name.isBlank() || name.length > 50 || name == "." || name == "..") return null
        return name
    }

    /** 拒绝绝对路径与向上穿越 */
    private fun isUnsafe(name: String): Boolean =
        name.startsWith("/") || name.split('/').any { it == ".." } || name.contains(":")
}
