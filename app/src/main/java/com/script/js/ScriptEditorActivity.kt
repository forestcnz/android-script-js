package com.script.js

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.script.js.core.editor.ApiDocs
import com.script.js.core.editor.Edits
import com.script.js.core.editor.JsHighlighter
import com.script.js.core.engine.ScriptRunner
import com.script.js.core.task.TaskManager

/**
 * 内置编辑器：编辑任务入口脚本 task.js，支持语法高亮、API 补全、保存与“保存并运行”。
 * 打开时可定位到上次运行的出错行（高亮 + 提示条）。
 * 对应设计 docs/DESIGN.md 第 4.3 节。
 */
class ScriptEditorActivity : ComponentActivity() {

    private var content by mutableStateOf(TextFieldValue(""))
    private var dirty by mutableStateOf(false)

    /** 需要定位/高亮的出错行（1 起）；0 表示无 */
    private var errorLine by mutableStateOf(0)

    /** 出错信息（显示在提示条上） */
    private var errorHint by mutableStateOf<String?>(null)

    private val taskName: String by lazy { intent.getStringExtra(EXTRA_TASK) ?: "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = try {
            TaskManager.scriptFile(this, taskName).readText()
        } catch (e: Exception) {
            "// 读取失败: ${e.message}\n"
        }

        // 出错行定位：优先用传入参数（菜单跳转），否则取运行器最近一次错误
        val extraLine = intent.getIntExtra(EXTRA_ERROR_LINE, 0)
        val extraMsg = intent.getStringExtra(EXTRA_ERROR_MSG)
        val last = ScriptRunner.lastError.value
        val matchedLast = last?.takeIf { it.taskName == taskName }
        val targetLine = if (extraLine > 0) extraLine else (matchedLast?.line ?: 0)
        errorLine = targetLine
        errorHint = when {
            targetLine <= 0 -> null
            !extraMsg.isNullOrBlank() -> extraMsg
            else -> matchedLast?.message
        }
        val initialSelection = JsHighlighter.lineRange(text, targetLine)?.first ?: 0
        content = TextFieldValue(text, TextRange(initialSelection))

        ApiDocs.writeDts(this)   // 生成/刷新 .d.ts（相当于 :api-docs 产物）
        setContent {
            MaterialTheme {
                EditorScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditorScreen() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("编辑 · $taskName" + if (dirty) " *" else "") },
                    actions = {
                        TextButton(onClick = { save(showToast = true) }) { Text("保存") }
                        TextButton(onClick = {
                            if (save(showToast = false)) {
                                ScriptRunner.attach(applicationContext)
                                ScriptRunner.run(this@ScriptEditorActivity, taskName)
                                Toast.makeText(
                                    this@ScriptEditorActivity,
                                    "已保存并开始运行",
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()   // 回到主界面看运行日志
                            }
                        }) { Text("运行") }
                        TextButton(onClick = { finish() }) { Text("返回") }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 上次运行的出错行提示（可关闭；改动后会失效，故仅作定位参考）
                if (errorLine > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "上次运行第 $errorLine 行出错" +
                                    (errorHint?.lineSequence()?.firstOrNull()?.let { "：$it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            errorLine = 0
                            errorHint = null
                        }) { Text("知道了") }
                    }
                }

                SuggestionBar(
                    prefix = JsHighlighter.wordBefore(content.text, content.selection.start)?.first,
                    onPick = { snippet -> insertAtCursor(snippet) }
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    BasicTextField(
                        value = content,
                        onValueChange = { new ->
                            // 自动缩进 / 括号配对（只影响单字符插入，粘贴原样放行）
                            content = Edits.apply(content, new)
                            dirty = true
                        },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = remember(errorLine) {
                            JsHighlighter.transformation(errorLine.takeIf { it > 0 })
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    /** API 补全条：仅在输入了标识符前缀时出现，避免误触把片段插入脚本 */
    @Composable
    private fun SuggestionBar(prefix: String?, onPick: (String) -> Unit) {
        if (prefix.isNullOrBlank()) return
        val items = remember(prefix) { ApiDocs.suggestions(prefix, limit = 8) }
        if (items.isEmpty()) return
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                Text(
                    item.name.substringAfterLast('.'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onPick(item.snippet) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }

    /** 在光标处插入片段（若光标处已有同名前缀则替换掉前缀） */
    private fun insertAtCursor(snippet: String) {
        val text = content.text
        val cursor = content.selection.start.coerceIn(0, text.length)
        val word = JsHighlighter.wordBefore(text, cursor)
        val start = word?.second ?: cursor
        val newText = text.substring(0, start) + snippet + text.substring(cursor)
        val newCursor = start + snippet.length
        content = TextFieldValue(newText, TextRange(newCursor))
        dirty = true
    }

    /** 保存脚本；返回是否成功 */
    private fun save(showToast: Boolean): Boolean {
        return try {
            TaskManager.scriptFile(this, taskName).writeText(content.text)
            dirty = false
            if (showToast) Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    override fun onPause() {
        // 自动保存，避免误退出丢改动
        if (dirty) save(showToast = false)
        super.onPause()
    }

    companion object {
        const val EXTRA_TASK = "task_name"

        /** 出错行（1 起，0/缺省表示不定位） */
        const val EXTRA_ERROR_LINE = "error_line"

        /** 出错信息（提示条展示） */
        const val EXTRA_ERROR_MSG = "error_msg"
    }
}
