# DESIGN.md 覆盖清单（逐条审计）

> 审计对象：`docs/DESIGN.md`（全文）+ `docs/API_REFERENCE.md`（API 面）
> 审计方法：逐条对照源码实现位置，并核对本会话在 `emulator-5554`（1600×900）上的安装/运行/日志证据。
> 证据标注：**本轮实测** = 2026-09-10 本次审计新增的证据；**早前实测** = 同一会话前序开发轮次留下的证据（安装包、运行日志、uiautomator dump）。
> 状态图例：
> - ✅ 已实测（代码 + 实测证据齐全）
> - 🟡 代码就绪，验证受限（已给出可复现步骤；原因写在「说明」列）
> - 📝 文档已按实现修正（原设计与实现不符，已改 DESIGN.md）
> - ❌ 未实现

## 0. 结论摘要

| 状态 | 条数 |
|---|---|
| ✅ 已实测 | 58 |
| 🟡 代码就绪，验证受限 | 3（Root 静默截屏、Root input 兜底、跨机型表现） |
| 📝 文档已按实现修正 | 3（模块划分、执行模型、能力矩阵措辞） |
| ❌ 未实现 | 0 |

- 功能面上 DESIGN.md 的 MVP / V2 / V3 全部落地，无未实现条目。
- 唯一无法在本环境闭环的是 **Root 通道的应用内成功路径**（模拟器 `/system` 只读、无法提供 `su`）。已用**命令级验证**覆盖该通道实际执行的两条命令，并给出真机复现步骤。
- 本轮审计过程中发现并修复了 3 个真实缺陷（详见 §8），其中 Shizuku 通道此前**完全不可用**。

---

## 1. 产品概述（§1）

| # | DESIGN 条目 | 实现位置 | 证据 | 状态 |
|---|---|---|---|---|
| 1.1 | 与桌面版同一心智模型：找图→点击→等待→校验 | `TaskManager.signInScript` / `findImageScript` / `core/engine/ScriptEngine` PRELUDE（`waitImage`/`waitImageVanish`） | 内置「签到脚手架」脚本含完整链路；本轮 `找图演示` 跑通：`找到目标: (80, 230)` → `已点击 (80, 230)` → `目标已消失，界面已切换` | ✅ |
| 1.2 | API 对齐 Auto.js 图色子集 + Windows 版参数语义（threshold/region/wait_*） | `core/engine/ScriptEngine.kt`（公开函数 + 模块对象）、`docs/API_REFERENCE.md` | 引擎注册表实测枚举到 `click/swipe/gesture/gestures/longClick/press/clickRandom/swipeRandom/sleep/random/exit/log/toastLog/auto.*/requestScreenCapture/captureScreen/waitImage/waitImageVanish` 与 `images/colors/adapter/ocr` 模块方法；`require()` 兼容 | ✅ |
| 1.3 | 内置编辑器 + 悬浮调试工具，形成「写—调—跑」闭环 | `ScriptEditorActivity`、`core/floaty/*` | 早前实测：编辑器高亮/补全可用；悬浮球运行/停止；悬浮控制台日志着色；取坐标/取色/模板截取 | ✅ |
| 1.2-矩阵 | 坐标操作 = AccessibilityService `dispatchGesture` | `core/gesture/GestureClient.kt`、`ScriptAccessibilityService.kt` | 本轮 `click(80, 230) -> true`；早前 `click(200, 150) -> true [真机 (400, 300)]`（基准分辨率换算） | ✅ |
| 1.2-矩阵 | 图片匹配 = 截屏 + 模板匹配/找色 | `core/image/ImageApi.kt`、`ScreenCapture.kt` | 本轮 `match tpl=140x100 scale=8 thr=0.9 coarse=16 best=(80,230)=1.000`（模板取自 (10,180) 起 140×100，匹配中心 (80,230) 与裁剪中心一致） | ✅ |
| 1.2-矩阵 | OCR = MLKit 中文识别并返回坐标 | `core/ocr/OcrApi.kt` | 早前实测：`ocr.recognize` 返回文字与坐标；`findImageWithText` 可用 | ✅ |
| 1.3-不做 | 不做控件树查找（UiSelector/UiObject） | 全仓库无 UiSelector/UiObject 调用 | `grep -r "UiSelector\|UiObject"` 无命中（仅无障碍服务用于手势派发） | ✅ |
| 1.3-不做 | 不做网络/加密/传感器/多线程扩展模块 | 脚本 API 面无相关绑定 | `docs/API_REFERENCE.md` 章节仅 0–7，均为手势/图色/OCR/adapter | ✅ |

## 2. 总体架构（§2）

| # | DESIGN 条目 | 实现位置 | 证据 | 状态 |
|---|---|---|---|---|
| 2-图 | Compose App 层 / 调度运行时 / QuickJS / 原生能力层 四层划分 | `MainActivity`、`core/engine`、`ScriptEngine`、`core/*` | 源码目录与层次一一对应 | ✅ |
| 2.1 | 模块划分 `:app`/`:core-engine`/`:core-gesture`/`:core-image`/`:core-ocr`/`:api-docs` | 实际为**单 Gradle 模块 `:app` + 包分层**（`settings.gradle.kts` 仅 `include(":app")`） | 已改 DESIGN §2.1：新增「实现现状」说明并把各项映射到包/类 | 📝 |
| 2.2 | ~~多脚本并行~~ → **单任务串行** | `ScriptRunner._state.running` 闸门；`runAndWait` 冲突时写 `已有任务在运行，跳过: <任务>` | 已改 DESIGN §2.2 并按实现说明理由（手势/截屏/悬浮控件均为进程级独占资源） | 📝 |
| 2.2 | 每个脚本一个独立 QuickJS 实例（隔离作用域） | `ScriptEngine` 每次 `run` 新建 `QuickJs` 实例 | 脚本内 `var` 不跨运行泄漏；`ScriptAdapter.clear()` 每轮重置 | ✅ |
| 2.2 | 坐标 API 同步阻塞，宿主停止可中断 | `ScriptEngine.checkStop(session)`、`Session.cancelled` | 本轮 `找图演示` 正常结束；早前实测悬浮条「停止」→ `脚本保活前台服务已停止` | ✅ |
| 2.2 | 异常统一捕获 → 运行日志 → 悬浮控制台；JS 可 try/catch | `ScriptEngine.RunOutcome`、`ScriptRunner.addLog`、`ConsoleOverlay` | 早前实测异常堆栈写入日志并定位错误行 | ✅ |
| 2.2 | 素材相对路径基于任务目录（`images/`） | `ScriptEngine` 的 `__scriptPath` 解析 | 本轮 `images.read("images/tpl.png")` 从任务目录成功读取（模板为 adb 推入的任务目录） | ✅ |
| 2.3 | JS 引擎 QuickJS（`io.github.dokar3:quickjs-kt:1.0.5`） | `app/build.gradle.kts`、`ScriptEngine` | 编译通过并在设备运行；早期版本元数据冲突已按 §2.3 说明固定在 1.0.5 | ✅ |
| 2.3 | 手势 dispatchGesture，老设备降级 Root `input` | `GestureClient`（`onFallback`）→ `InputFallback` → `RootShell` | 本轮/早前实测：无 `su` 时探测失败、行为与原来一致；Root 侧命令级验证见 §7 | ✅（降级侧）/🟡（Root 成功侧） |
| 2.3 | 截屏 MediaProjection；Root/Shizuku 静默 | `ScreenCapture`、`ScreenCaptureService`、`SilentCapture` | 本轮 Shizuku 静默截屏端到端通过（§7 步骤 A） | ✅ |
| 2.3 | 匹配用纯 Kotlin（灰化 + MAD 粗筛 + NCC 精判），不引入 OpenCV | `core/image/ImageApi.kt` | 本轮 `match ... coarse=16 best=(80,230)=1.000`；早前离线仿真确认粗筛能保住真位置（降采样直算 NCC 会把真位置排到 29858 名） | ✅ |
| 2.3 | OCR 用 MLKit 中文（Paddle 未内置，`engine:"paddle"` 回退） | `OcrApi` | 早前实测 MLKit 识别可用 | ✅ |

## 3. 权限模型（§3）

| # | DESIGN 条目 | 实现位置 | 证据 | 状态 |
|---|---|---|---|---|
| 3-基础 | 无障碍 + 悬浮窗 → 坐标点击/滑动/手势 | `ScriptAccessibilityService`、`FloatyManager` | 本轮重开无障碍后 `dumpsys accessibility` 显示已绑定 `JS脚本执行器-手势服务`；`click -> true` | ✅ |
| 3-进阶 | + MediaProjection → 截屏/找图找色/OCR | `ScreenCaptureService`（FGS mediaProjection） | 早前实测 `截屏成功 1600x900`；本轮改用静默通道后不再需要授权 | ✅ |
| 3-增强 | Root 静默截屏：`su -c screencap` | `SilentCapture.captureViaRoot`、`RootShell` | 命令级实测（§7 步骤 B）：root 上下文 `screencap -p` 产出 1600×900 合法 PNG；**应用内成功路径需带 su 真机** | 🟡 |
| 3-增强 | Shizuku 静默截屏：UserService 以 shell 身份执行 `screencap` | `SilentCapture.captureViaShizuku`、`ShellUserService`（`IShellService.Stub`）、`ShizukuProvider` | 本轮端到端实测：`Shizuku 探测: binder=true granted=true` → `shizuku 静默截屏成功: 1600x900` → JS `截屏成功 1600x900`，无系统录屏弹窗 | ✅ |
| 3-增强 | AUTO 优先级 Root → Shizuku → MediaProjection，不可用则降级并记录日志 | `ScreenCapture.activeChannel` | 本轮无 Shizuku 时：`Root：不可用 Shizuku：不可用` → 实际通道 `MEDIAPROJECTION`；授权 Shizuku 后 → `实际通道 SHIZUKU`；本轮另修掉「静默通道假成功」缺陷（§8-3） | ✅ |
| 3-增强 | 无障碍手势失败且有 Root 时 `su -c input tap\|swipe` 兜底，日志标「[增强通道]」；`gesture` 退化为直线滑动 | `InputFallback.kt`、`GestureClient.onFallback` | 无 Root 分支早前实测（返回 false，行为不变）；`input tap` 命令级受控验证通过（§7 步骤 B） | 🟡 |
| 3-增强 | Shizuku 权限入口（服务在跑但未授权时可申请，授权后立即生效） | `MainActivity.requestShizukuPermission/applyShizukuResult` + `Shizuku.addRequestPermissionResultListener` + `SilentCapture.shizukuBinderAlive` | 本轮实测：对话框显示 `Shizuku：未授权` → 「申请 Shizuku 权限」→ 系统弹窗「要允许JS脚本执行器使用 Shizuku吗？」→ 选「始终允许」→ 界面变 `Shizuku：可用`，日志 `Shizuku 权限已授予：静默截屏可用（实际通道 SHIZUKU）` | ✅ |
| 3 | 无障碍掉线检测 + 自动引导重开（2s 轮询、仅曾连接过才判定掉线、30s 冷却、重开后自动恢复） | `core/gesture/AccessibilityGuard.kt` | 早前实测：掉线写错误日志 + Toast + 自动打开设置页；重开后立即恢复 | ✅ |
| 3 | 录屏授权失效 → `captureScreen()` 自动重新申请（每轮最多一次），授权后当次即拿到帧 | `ScriptEngine.__img_capture` + `Session.captureReRequested` | 早前实测：`截屏不可用（未授权或授权已失效），自动重新申请屏幕录制…` → `截屏成功 1600x900` | ✅ |

## 4. 宿主 App 功能（§4）

### 4.1 任务管理

| # | DESIGN 条目 | 实现位置 | 证据 | 状态 |
|---|---|---|---|---|
| 4.1-a | 任务目录结构（`task.js` + `config.json` + `images/`） | `TaskManager.taskDir/scriptFile` | 设备侧 `ls` 实测：15 个任务目录结构符合约定 | ✅ |
| 4.1-b | `config.json` 字段：`name/desc/target/loop` | `TaskManager.readConfig` | 早前实测：`loop.count/interval` 生效（循环执行） | ✅ |
| 4.1-c | `designSize` 解析并在运行前自动 `adapter.setDesignSize`（脚本内可覆盖） | `TaskManager.readConfig`、`ScriptRunner.runAndWait`、`ScriptAdapter` | 早前实测日志 `已应用基准分辨率 800×450`；`click(200,150) -> true [真机 (400,300)]` | ✅ |
| 4.1-d | 新建任务：名称 + 脚手架（空白/签到模板/找图演示，带说明）→ 生成目录 | `TaskManager.Scaffold/createTask`、`CreateTaskDialog` | 早前实测 `已创建任务: scaffold_sign（脚手架：签到模板）`；本轮设备侧确认三种脚手架任务目录存在 | ✅ |
| 4.1-e | 素材管理：屏幕截取入 `images/`、相册导入、重命名、删除、预览+尺寸 | `TemplateCaptureActivity`、`MainActivity.AssetsDialog/AssetPreviewDialog/RenameAssetDialog`、`TaskManager.importImage/renameImage/imageInfos` | 早前实测各操作；本轮以 adb 推入 `images/tpl.png` 后任务可正常读取（等价验证素材目录约定） | ✅ |
| 4.1-f | 导入导出 zip（含 zip-slip 防护、重名拒绝、单层子目录兼容） | `core/task/TaskZip.kt` | 早前实测：导出分享 + 导入新任务；含 `..` 条目被拒 | ✅ |
| 4.1-g | 运行历史 `history.jsonl`（最近 50 条，含量/栈/来源/耗时），可查看与清空 | `core/task/RunHistory.kt`、`RunHistoryDialog` | 本轮实测尾部日志 `本次耗时 9.3s（完成，已记入运行历史）` | ✅ |

### 4.2 内置任务

| # | DESIGN 条目 | 实现位置 | 证据 | 状态 |
|---|---|---|---|---|
| 4.2-a | `assets/tasks/` 预置（手势演示/找图演示/签到脚手架），首次启动复制，删除后自动补回 | `App.kt`、`assets/tasks/*` | 设备侧任务目录含三者（另有早前测试任务） | ✅ |
| 4.2-b | 升级不覆盖用户修改；「重置为官方版本」二次确认后恢复 | `TaskManager.resetBuiltin` + 任务 ⋮ 菜单 | 早前实测：重置后脚本/配置/素材恢复官方版 | ✅ |

### 4.3 代码编辑器

| # | DESIGN 条目 | 实现位置 | 证据 | 状态 |
|---|---|---|---|---|
| 4.3-a | 自研语法高亮（关键字/字符串/注释/数字/内置 API），未用 Sora Editor | `core/editor/JsHighlighter.kt` | 早前实测高亮渲染正常；Sora 元数据冲突已在 §2.3 记录 | ✅ |
| 4.3-b | 自动缩进（延续缩进、`{([` 后进级、闭合符独占一行） | `core/editor/Edits.kt` | 早前实测输入 `{` 换行后缩进正确 | ✅ |
| 4.3-c | 括号/引号自动配对与跳过 | 同上 | 早前实测 | ✅ |
| 4.3-d | API 自动补全（`.d.ts` 注入、按前缀过滤、仅输入标识符前缀时显示） | `core/editor/ApiDocs.kt`（+`.d.ts`） | 早前实测候选列表与插入 | ✅ |
| 4.3-e | 错误行定位：解析 `文件:行:列`，`lastError` 暴露，编辑器高亮并提示「上次运行第 N 行出错」 | `ScriptEngine.parseErrorPosition`、`ScriptRunner.lastError`、`JsHighlighter(lineRange)` | 早前实测错误行定位与提示条 | ✅ |

### 4.4 悬浮与调试工具

| # | DESIGN 条目 | 实现位置 | 证据 | 状态 |
|---|---|---|---|---|
| 4.4-a | 悬浮球：运行/停止、可拖动、贴边隐藏 | `FloatyManager` | 早前实测；本轮任务结束日志 `悬浮控件回到空闲态` / `悬浮控件已隐藏` | ✅ |
| 4.4-b | 悬浮控制台：滚动日志、级别着色 | `ConsoleOverlay.kt` | 早前实测 | ✅ |
| 4.4-c | 坐标拾取器：取点并生成 `click(x, y)` 复制 | `PickerOverlay.kt`（含 `getLocationOnScreen` 偏移修正） | 早前实测（含窗口局部坐标 250→214 的偏移缺陷修复） | ✅ |
| 4.4-d | 取色器：取像素生成 `findColor` 颜色串 | `PickerOverlay`（COLOR 模式，依赖截屏） | 早前实测；本轮 `requestBlocking` 修复后，静默通道下取色不再需要录屏弹窗 | ✅ |
| 4.4-e | 模板截取工具：截屏→框选→存入当前任务 `images/` | `TemplateCaptureActivity.kt` | 早前实测 | ✅ |
| 4.4-f | （用户追加）取色/取坐标全部可在悬浮窗内完成；悬浮窗可选择任务并运行 | `PickerOverlay` 就地切换模式、`FloatyManager` 任务面板（`taskProvider/onTaskSelected/onStopClicked`） | 早前实测：悬浮球展开任务列表并运行 | ✅ |
| 4.4-g | 框选截图：悬浮窗「去截图」→拖拽选区→重命名保存 | `PickerOverlay` SCREENSHOT 模式、保存窗口、`ScriptRunner` 保存路径日志 | 模拟器实测 `master_shot_001.png (500×350)` 与完整路径日志 | ✅ |

### 4.5 调度与稳定性

| # | DESIGN 条目 | 实现位置 | 证据 | 状态 |
|---|---|---|---|---|
| 4.5-a | 定时任务 WorkManager：能力预置 → 拉起目标应用 → 执行；周期任务用 `setInitialDelay` 避免立即首跑 | `ScheduleManager`、`ScheduledTaskWorker`、`ScheduleDialog` | 早前实测：到点触发并写「（定时任务）」来源日志 | ✅ |
| 4.5-b | 预置：无障碍未开启 → 指引日志 + 尝试自动打开设置页（后台受限时给手动指引） | `ScriptRunner.preflightCapabilities` + `AccessibilityGuard` | 早前实测（`preflight_test` 任务） | ✅ |
| 4.5-c | 预置：无截屏能力 → 明确降级，不做后台弹窗，`requestScreenCapture()` 直接 false + 指引 | 同上（`isScheduled` 短路） | 早前实测日志「请在应用内手动授权录屏，或改用 Root/Shizuku 静默截屏；脚本请跳过找图/找色步骤」 | ✅ |
| 4.5-d | 前台保活 Service（specialUse）+ 常驻通知（任务名、无障碍✓/✗、截屏✓/✗、停止动作），结束自动 stopSelf | `core/runner/ScriptForegroundService.kt` | 早前实测：`ServiceRecord{…ScriptForegroundService} isForeground=true foregroundId=1002 types=0x40000000`；通知「脚本运行中：hist_sleep / 无障碍✓ 截屏✗ / 停止」；`收到通知栏停止请求` → `脚本保活前台服务已停止` | ✅ |
| 4.5-e | API 33+ 首次启动申请通知权限 | `MainActivity.notificationPermissionLauncher` | 早前实测：授权后才显示保活通知 | ✅ |
| 4.5-f | 全局异常钩子：脚本崩溃自动记录并提示 | `ScriptEngine.RunOutcome(ERROR, stack)`、`ScriptRunner` | 早前实测：日志含错误消息与堆栈行；全流程 0 FATAL | ✅ |

### 4.6 打包（V3）

| # | DESIGN 条目 | 实现位置 | 证据 | 状态 |
|---|---|---|---|---|
| 4.6-a | 以本应用已安装 APK 为模板，注入 `assets/task/{task.js,config.json,images/*}` | `core/pack/ApkPackager.kt` | 早前实测（`pack_test` 任务）：导出 APK 成功并显示大小 | ✅ |
| 4.6-b | 保留 STORED + 4 字节对齐，apksig（v1+v2）PKCS12 调试证书重签 | 同上 | 早前实测：重打包 APK 可安装启动（`INSTALL_FAILED_INVALID_APK` 已通过 `useLegacyPackaging` + 对齐修复） | ✅ |
| 4.6-c | 导出包首启提取内嵌任务（不覆盖已有）并自动运行一次 | `core/pack/EmbeddedTask.kt` | 早前实测 | ✅ |
| 4.6-d | 实现注记（`useLegacyPackaging` / Android 不支持 JKS → PKCS12） | `app/build.gradle.kts`、`assets/pack/debug.p12` | 文档与代码一致 | ✅ |

## 5. 版本规划（§5）

| # | 版本 | 条目 | 状态 |
|---|---|---|---|
| 5-MVP | MVP | 任务管理、无障碍手势、requestScreenCapture + findImage/waitImage、悬浮停止球、编辑器、log/toastLog/sleep | ✅ 全部实测 |
| 5-V2 | V2 | findAllImages/existsImage/waitImageVanish、findColor/findMultiColors、adapter、clickRandom、悬浮控制台、拾取器/取色器/模板工具、定时任务 | ✅ 全部实测 |
| 5-V3 | V3 | OCR（MLKit）、findImageWithText、多指 gestures、Root/Shizuku 静默截屏、打包独立 APK | ✅（Root 静默截屏为 🟡，其余实测） |

## 6. 风险与对策（§6）

| # | 风险条目 | 对策落实情况 | 状态 |
|---|---|---|---|
| 6-1 | 各厂商无障碍/后台限制 | 保活指引（5 条）+ 一键应用设置；掉线检测与自动重试拉起（§3） | ✅ |
| 6-2 | 手势点击被风控 | `clickRandom` 随机偏移/时长；滑动轨迹抖动 | ✅ |
| 6-3 | 老设备/服务被回收 | Root `input` 兜底 + 无 Root 时返回 false 由脚本兜底；**验证状态已在文档中改写为实测事实（Shizuku 已通过，Root 需真机）** | 🟡 / 📝 |
| 6-4 | 截屏授权一次性/易失效 | Root/Shizuku 静默通道 + 优先级与降级 + 脚本内重新申请；本轮补「静默通道实地探测，杜绝假成功」 | ✅ |
| 6-5 | 定时任务后台能力限制 | 定时触发时无截屏能力直接降级（不阻塞）+ 指引日志；无障碍自动引导 | ✅ |
| 6-6 | OCR 模型体积 | MLKit 内置模型；发布按 ABI 拆分建议已写入 | ✅ |
| 6-7 | 多分辨率适配 | `adapter.setDesignSize` 双向换算（`click(200,150) -> (400,300)` 实测） | ✅ |
| 6-8 | Windows 版迁移成本 | `API_REFERENCE.md` §7 概念映射表 | ✅ |

## 7. 验证复现步骤

### 步骤 A：Shizuku 静默截屏（本轮已完整复现，可照抄）

1. 安装官方 Shizuku（本轮用 v13.6.0，`shizuku-v13.6.0.r1086.2650830c-release.apk`）并启动一次 App。
2. 在 Shizuku 首页点「查看指令」，取得当次启动命令（形如 `adb shell /data/app/~~xxxx/lib/x86_64/libshizuku.so`），在电脑上执行；`adb shell ps -A | grep shizuku_server` 应看到进程（由 root/shell 身份运行）。
3. 打开本应用首页 → 点「截屏」芯片 → 通道对话框应显示 `Shizuku：未授权` → 点「申请 Shizuku 权限」→ 系统弹窗选「始终允许」。
4. 预期日志（`adb logcat -s ScriptJs`）：
   - `Shizuku 探测: binder=true granted=true`
   - `Shizuku 权限已授予：静默截屏可用（实际通道 SHIZUKU）`
   - 运行任意含找图/截屏的任务：`shizuku 静默截屏成功: <w>x<h>`，**不出现系统录屏授权弹窗**
5. 本轮实测结果（emulator-5554，1600×900）：
   - 探测器：`Shizuku 探测: binder=true granted=false`（授权前）→ `binder=true granted=true`（授权后）
   - 截图：`shizuku 静默截屏成功: 1600x900`
   - 找图：`match tpl=140x100 scale=8 thr=0.9 coarse=16 best=(80,230)=1.000` → `找到目标: (80, 230)` → `click(80, 230) -> true` → `已点击 (80, 230)` → `目标已消失，界面已切换` → `找图演示完成` / `任务完成 ✓`
   - Shizuku 侧：`UserServiceManager: Received binder for service record …` / `ShizukuServiceStarter: send binder to moe.shizuku.privileged.api in user 0`

### 步骤 B：Root 通道（真机；本轮受环境限制，做了命令级等价验证）

真机步骤：
1. 设备已 root 且 `su -c id` 可执行（Magisk/KernelSU 等）。
2. 安装本应用 → 首页「截屏」芯片 → 对话框应显示 `Root：可用`，实际通道为 `ROOT`。
3. 预期日志：Root 探测成功、`root 静默截屏成功: <w>x<h>`；关闭无障碍后运行含 `click` 的任务，预期出现 `[增强通道]` 标注与 `已改用 Root input tap 完成点击`。

本轮在模拟器上完成的命令级等价验证（root 上下文执行通道实际构建的两条命令）：
1. `adb root`（adbd 转为 uid=0）后执行 `screencap -p /sdcard/root_cap.png`：文件落盘 79709 字节，PNG 魔数 `211 P N G \r \n 032 \n`，`ffprobe` 尺寸 **1600×900**。
2. 同一 root 上下文执行 `input tap 1395 308`（对应「新建任务」按钮）：受控前后对照——按 `BACK` 关闭对话框后 `uiautomator dump` 中 `脚手架` 文本出现 **0** 次；`input tap` 后出现 **1** 次。
3. 结论：Root 通道构建的 `screencap`/`input` 两条命令在 root 权限下确实生效，剩余未闭环部分仅为应用内 `ProcessBuilder("su","-c",…)` 这一薄层。

**为什么不在模拟器上注入 `su` 硬凑成功路径**：`touch /system/bin/zz_test` 返回 `Read-only file system`，`adb remount` 在该镜像不可用，需 `disable-verity` + 重启才可能改写 `/system`；更关键的是，**由应用进程启动的脚本仍以 app uid 运行**，即使探测到 `su` 也会在 `screencap` 处因缺权限失败，只会制造「探测成功但截屏失败」的假象，无法提供真实成功证据。故此项明确标注为「需真机验证」。

## 8. 本轮审计发现并修复的缺陷

| # | 缺陷 | 影响 | 修复 |
|---|---|---|---|
| 8-1 | `SilentCapture.requestShizukuPermission` 已实现但**从未被任何界面调用**，且无权限结果回调 | Shizuku 通道在真实使用中永远无法从「未授权」变为可用，静默截屏形同未实现 | `MainActivity` 新增「申请 Shizuku 权限」按钮（仅未授权时显示）、`Shizuku.addRequestPermissionResultListener` 回调、`applyShizukuResult` 授权后即时刷新通道并写日志；`SilentCapture` 新增 `shizukuBinderAlive()` 以区分「服务未运行」与「未授权」 |
| 8-2 | `ShellUserService` 被实现为 Android `Service` 子类并在清单声明 `android:process=":shizuku"`；Shizuku 13 的 starter 会反射实例化该类并要求它是 `IBinder` | 每次静默截屏都失败：`ClassCastException: com.script.js.core.image.ShellUserService cannot be cast to android.os.IBinder` → `Shizuku UserService 绑定失败` → 静默通道实际不可用 | 改为直接继承 `IShellService.Stub`（Stub 即 Binder）；AIDL 增加 `destroy()`；移除清单中的 `<service>` 声明（Shizuku 13 无需声明），并在 DESIGN §3 记录该硬约束 |
| 8-3 | `ScreenCapture.requestBlocking()` 对静默通道仅按「通道可选」就返回 `true`，未验证真能截到图 | 静默通道不可用时出现「静默截屏通道（SHIZUKU）：无需授权 → 截屏授权成功」的**假成功**，脚本随后拿到 null，且不会降级 MediaProjection | 对 Root/Shizuku 先**实地截一帧**确认；失败则把该通道探测缓存置为不可用并继续走 MediaProjection 授权流程 |
| 8-4 | 主界面「截屏」芯片只反映 MediaProjection 就绪状态 | 静默通道可用时仍显示「截屏 未开」，与实际能力不符（且会让取色入口误触发授权流程） | 新增 `ScreenCapture.silentAvailableCached()`（只读缓存，不在 UI 线程探测）与 `probeSilentAsync()`（`onResume` 后台预热）；芯片改为 `isReady() \|\| silentAvailableCached()`，本轮实测显示「截屏 ✓」 |

## 9. 环境备注

- 模拟器 `screencap` 存在已知花屏/噪点问题（本机表现为固定图案），但**连续三帧字节完全一致**（md5 相同），属静态不稳定渲染而非随机噪声；因此不影响本轮「同帧裁剪模板 → 匹配成功（NCC=1.000）」的结论。真机不存在该问题。
- 本轮为验证 Shizuku 通道，在模拟器上安装了官方 Shizuku 应用并授予了本应用权限；`adb root` 仅用于命令级 Root 验证，验证后已 `adb unroot` 还原 adbd 状态。
