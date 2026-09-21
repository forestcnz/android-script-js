---
name: android-script-author
description: 为 android-script-js 执行器编写、修改、排错 JS 自动化任务（task.js + config.json + images/ 素材）。当用户说“写一个签到脚本 / 自动点击 / 找图点击 / 滑动刷视频 / 识别文字后点击 / 帮我做个安卓自动化任务”，或需要检查任务目录结构、config.json 配置、坐标多分辨率适配、找图阈值/区域、内置 API 是否可用时使用本技能。
---

# Android 脚本作者技能（android-script-author）

本技能指导 AI 为 `android-script-js` 生成**可直接运行**的任务脚本。
所有 API 以当前工程实现（`core/engine/ScriptEngine.kt` 的注入绑定 + `docs/API_REFERENCE.md`）为准，见本目录 `reference.md`。

## 0. 心智模型

任务 = **找图/找色/OCR 定位 → 点击 → 等待 → 校验**。脚本是**顺序执行**的同步代码，
长按、滑动、手势调用会阻塞到动作完成，不需要回调或事件监听。

## 1. 硬性约束（必须遵守，违反会导致脚本跑不起来）

1. **产出物是一个任务目录**，不是散落的 `.js` 文件：
   ```
   tasks/<任务名>/
   ├── task.js        # 固定入口名，必须有
   ├── config.json    # 任务配置，建议有
   └── images/        # 模板素材（找图用），用到才建
   ```
2. **入口文件名固定为 `task.js`**，宿主点击任务即运行它；不支持自定义入口或 `require` 其他本地文件。
3. **素材路径一律写相对路径**，相对任务目录解析，放 `images/` 子目录：`waitImage("images/btn.png", 10)`。
   不要写 `/sdcard/...` 或绝对路径。
4. **`require()` 只支持 4 个内置模块**：`"images"`、`"colors"`、`"ocr"`、`"adapter"`，
   其他名字会抛「模块未实现」。这些模块同时也是全局对象，直接 `images.xxx` 也可。
5. **能力收敛，只做坐标 + 图色 + OCR**。禁止使用控件树 API（`text()` / `desc()` / `id()` / `UiSelector` /
   `className()`）、`app.launch()`、网络/HTTP、多线程、传感器、事件监听、自定义弹窗。
   - 拉起目标应用用 `config.json` 的 `target` 字段（宿主运行前自动拉起），脚本里没有 `app` 对象。
   - 没有 `files` / `storages` / `engines` 等 Auto.js 扩展模块。
6. **权限顺序**：脚本开头先 `auto.waitFor(5000)` 等无障碍（坐标点击依赖）；用到找图/找色/OCR 前先
   `requestScreenCapture()` 拿截屏授权，并**对失败做降级**，不要假定一定授权成功。
7. **坐标基准**：`config.json` 里填了 `designSize`（如 `[1080,1920]`）时，脚本内所有坐标、`region`
   都按该基准写，运行时会自动换算；否则按真机分辨率写。同一任务内不要混用两种基准。
8. **`waitForAction` 不存在**（`API_REFERENCE.md` 1.2 有记载但引擎未绑定），需要轮询请自己写
   `while` + `sleep`。`requestScreenCaptureAsync()` 虽存在但内部是同步的，没必要用。
9. **`auto.waitFor()` 必须带超时参数**：不传时引擎按 `timeout=0` 处理，服务未就绪会**立即返回 false**
   而不是阻塞等待；统一写 `auto.waitFor(5000)`。
10. **`exit()` 不要写在 `try` 块内**：`exit()` 靠抛异常中止脚本，写在 `try` 里会被自己的 `catch(e)`
    当成普通异常吞掉，脚本继续往下跑。把退出判断放在 `try` 外，或改用 `throw` 让 catch 统一处理。

## 2. 标准工作流

按顺序执行，缺一步都可能生成跑不通的脚本：

1. **澄清并记下 3 件事**：目标应用包名、要操作的界面/按钮特征、成功判据（什么算完成）。
   信息不足时按最合理假设继续，并在最终说明里标注假设，不要停下来问。
2. **选定位策略**（见 §4），对每个待点击元素确定用哪种方式，并写清坐标/素材文件名。
3. **建任务目录**：`tasks/<任务名>/`，写 `task.js`；需要找图就建 `images/` 并把模板图列进交付说明。
4. **写 `config.json`**：至少 `name` / `desc`，能填就填 `target` 与 `designSize`。
5. **写 `task.js`**：套用 §3 骨架，按 §5 写健壮逻辑，按 §6 加日志。
6. **自检**：逐条过 §7 清单；确认每个用到的 API 都在 `reference.md` 的可用清单里。

## 3. 脚本骨架模板

```javascript
// task.js — <任务名>
// 坐标基准：config.json designSize = 1080x1920（未配置则按真机分辨率）
auto.waitFor(5000);
if (!auto.service) { toastLog("请先开启无障碍服务"); exit(); }

var canImage = requestScreenCapture();
log(canImage ? "截屏已就绪" : "无截屏能力，降级为纯坐标流程");

// 优先按模板图点击，找不到用坐标兜底；返回是否成功
function tapImage(tpl, timeout, fallbackX, fallbackY) {
    if (canImage) {
        var p = waitImage(tpl, timeout || 8, 0.5, { threshold: 0.9 });
        if (p) { clickRandom(p.x, p.y); log("图匹点击 " + tpl + " (" + p.x + "," + p.y + ")"); return true; }
        log("未匹配 " + tpl);
    }
    if (fallbackX != null) {
        clickRandom(fallbackX, fallbackY);
        log("坐标兜底 (" + fallbackX + "," + fallbackY + ")");
        return true;
    }
    return false;
}

try {
    // 1) 进入目标界面：config.json 的 target 由宿主运行前自动拉起
    // 注意 exit() 要放在 try 外：写在 try 内会被 catch 吞掉，脚本不会真正退出
    if (!tapImage("images/entry.png", 10, null, null)) {
        toastLog("未进入目标界面，任务中止");
        exit();
    }
    sleep(1000);

    // 2) 核心操作（TODO：替换为真实步骤）
    tapImage("images/sign_button.png", 8, 540, 1200);
    sleep(1500);

    // 3) 结果校验
    var ok = !canImage || waitImage("images/signed.png", 3, 0.5, { threshold: 0.9 });
    log(ok ? "任务完成 ✓" : "未检测到成功标记，请检查素材/坐标");
} catch (e) {
    toastLog("脚本异常: " + e);
    log("异常: " + e);
}
```

完整可复制文件见 `templates/task.js.tpl` 与 `templates/config.json.tpl`。

## 4. 定位策略决策

| 场景 | 首选 | 写法要点 |
|---|---|---|
| 按钮有固定图样、每台设备都一致 | 找图 | `waitImage("images/x.png", 8, 0.5, {threshold:0.9})`；图匹配返回的 `x/y` 直接可点 |
| 图样相似度高、屏幕上有多个 | 找图 + 区域 | 用 `options.region=[x,y,w,h]` 限定范围，加快速度并防误匹配 |
| 纯色块/状态条，无固定图样 | 找色 | `images.findColor(captureScreen(), "#ff4400", {region, threshold:16})` |
| 同一图样多处、需逐一处理 | 多目标找图 | `images.findAllImages(...)` 返回按左上→右下排序的 Point 数组 |
| 按钮无固定图样但文字固定 | OCR | `ocr.init(); ocr.findText(captureScreen(), "立即购买")`，命中项 `centerX()/centerY()` 可直接点 |
| 卡片样式固定但要读图内文字 | 图文联合 | `images.findImageWithText(img, tpl, {padding:8})` → `{x,y,text,items}` |
| 完全没有视觉特征、已知坐标 | 坐标兜底 | 可直接 `click(x,y)`，但优先保留找图分支 + 坐标 fallback 的双保险 |
| 加载中 → 结束 | 等消失/出现 | 加载框用 `waitImageVanish`，目标用 `waitImage` |

**阈值与区域的取值经验**：`threshold` 默认 0.9，误匹配多就提到 0.92~0.95，漏匹配多就降到 0.8~0.85；
`region` 用设计坐标填写（启用 `designSize` 时引擎会换算）。

## 5. 健壮性规范

- **每个关键点击都要有兜底**：找图失败 → 坐标 fallback → 仍失败则 `exit()` 并 `toastLog` 说明原因。
- **不要死循环等一个不会出现的东西**：`waitImage` / `waitImageVanish` 必须给有限的 `timeout`（秒）。
- **点击防检测**：能用 `clickRandom` / `swipeRandom` 就不用 `click` / `swipe`；连续动作之间 `sleep(500~1500)`。
- **无截屏能力的降级路径要真能用**：`canImage == false` 时跳过所有找图/找色/OCR 步骤走坐标流程，
  而不是让脚本在 `waitImage` 里空等（后台定时任务场景下 `requestScreenCapture()` 会直接返回 false）。
- **整段核心逻辑包 `try/catch`**：异常时 `toastLog` + `log`，避免静默失败。
- **素材图必须是目标设备原生分辨率的裁剪图**。找图是在真机截图上做像素匹配，
  模板图不会参与坐标缩放；跨分辨率截的模板会匹配失败（不同设备应各截一份素材）。
- **停止是宿主能力**：脚本不需要（也没有）`check_stop()`，悬浮条/通知栏的停止会直接中断引擎，
  正常写顺序代码即可。

## 6. 日志与可读性

- 每个阶段用 `log("1/3 ...")` 打点，便于运行日志排查；关键结果用 `toastLog` 反馈用户。
- 控制台有 `log(print)`、`console.log/info/warn/error`，级别会影响日志着色。
- 生成的脚本注释用中文，与工程内置示例（`app/src/main/assets/tasks/*`）风格一致：
  开头一段说明用途/用法，关键步骤行内注释。

## 7. 交付前自检清单

- [ ] 目录结构为 `tasks/<任务名>/{task.js, config.json, images/}`，入口名是 `task.js`。
- [ ] `config.json` 是合法 JSON（无注释、无尾逗号），`designSize` 与实际坐标基准一致。
- [ ] 脚本开头有 `auto.waitFor(5000)`（带超时）+ `auto.service` 检查；`exit()` 不在任何 `try` 块内。
- [ ] 用找图/找色/OCR 前有 `requestScreenCapture()`，且 false 时有可用降级路径。
- [ ] 每个视觉定位都给了有限 `timeout`，每个关键点击都有 fallback 或失败退出。
- [ ] 用到的每个 API 都在 `reference.md`「可用 API」清单内；没有用到 `waitForAction`、
      `app.launch`、UiSelector、网络、require 非内置模块等禁用项。
- [ ] 素材路径为 `images/xxx.png` 相对路径，交付说明里列清需要用户放入 `images/` 的模板图
      （名称 + 截取方法：在目标界面用 App 内「模板截取工具」框选保存）。
- [ ] 坐标按 `designSize` 编写；无 `designSize` 时明确说明按真机分辨率。
- [ ] `try/catch` 兜底 + 阶段日志齐全。

## 8. 本技能的参考文件

- `reference.md`：**已核对引擎实现**的 API 速查（可用清单 / 禁用清单 / 模块 / 返回对象）。
- `templates/task.js.tpl`：带权限预检、找图+坐标双保险、异常兜底的任务模板。
- `templates/config.json.tpl`：任务配置模板。
- 工程权威文档：`docs/API_REFERENCE.md`（完整手册）、`docs/DESIGN.md`（能力边界与权限模型）。
