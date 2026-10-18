---
name: android-script-author
description: 为 android-script-js 执行器编写/修改任务脚本（task.js + config.json + images/），新增或排错内置任务（assets/tasks/）。当用户说「写个脚本 / 自动点击 / 循环找图 / 自动确定 / 使用 / 击败它们 / 加个内置任务 / 脚本不对没点中 / 找图失败」，或需要 模板匹配、OCR 找文字、坐标点击、config.json 配置 时使用本技能。
---

# Android 任务脚本作者技能（android-script-author）

为本工程生成**可直接运行**的任务脚本。任务 = `tasks/<任务名>/` 目录：
`task.js`（固定入口名）+ `config.json` + `images/`（模板素材，用到才建）。
脚本顺序同步执行（QuickJS, ES2020+），全部 API 为**同步阻塞绑定**，无需回调。

> 详细 API 速查（已核对引擎绑定）：`docs/skills/android-script-author/reference.md`；
> 完整手册：`docs/API_REFERENCE.md`。APK 构建见 opencode 技能 `android-apk-build`。

## 1. 硬性约束（违反跑不起来）

1. 入口固定 `task.js`；素材全用相对路径 `images/xxx.png`（相对任务目录解析）。
2. `require()` 仅支持 `images/colors/ocr/adapter`；同时也是全局对象。
3. **能力收敛**：只有 坐标+图色+OCR。没有控件树/`app.launch`/网络/多线程/事件监听。
   拉起目标应用用 `config.json` 的 `target`（宿主运行前自动拉起）。
4. 权限顺序：开头 `auto.waitFor(5000)`（**必须带超时**，无参等价 timeout=0 会立即返回）；
   找图/找色/OCR 前先 `requestScreenCapture()`，失败走降级，不能假定授权成功。
5. `exit()` 靠抛异常中止：**不要写在 try 块内**（会被自己的 catch 吞掉）。
6. 坐标基准二选一且有 `config.json` 对应：
   - 脚本写**固定设计坐标**（如 click(540,1200)）→ `config.json` 填 `"designSize": [1080,1920]`；
   - 脚本**只点击找图返回的坐标** → **省略 designSize**（匹配坐标=真机像素，原样点击，零换算误差）。

## 2. 三种标准模式（直接套）

### 模式 A：一次性任务（打开→定位→点击→校验）
见 `docs/skills/android-script-author/SKILL.md` §3 骨架；要点：每步 `waitImage(tpl, 有限超秒)`，
失败坐标兜底 → 再失败 `toastLog` 原因后 `exit()`（try 外）。

### 模式 B：循环找图守护（「找到就点击」，可长跑）
内置任务「自动确定/万能使用/击败它们」即此模式。结构：

```javascript
auto.waitFor(5000);
if (!auto.service) { toastLog("请先开启无障碍服务"); exit(); }
if (!requestScreenCapture()) { toastLog("请先授权屏幕录制"); exit(); }

var THRESHOLD = 0.9, POLL_MS = 800, CLICK_REST = 1200;  // 阈值/轮询/点击后冷却

// 预加载模板：每轮轮询复用句柄（避免反复解码文件）；缺失则跳过并提示
var tpl = images.read("images/使用.png");
if (!tpl) { toastLog("缺少素材 images/使用.png"); exit(); }

while (true) {                       // 由悬浮条「停止」结束脚本，无需自设退出条件
    var p = null;
    var img = captureScreen();       // 未授权时返回 null，务必判空
    if (img) {
        p = images.findImage(img, tpl, { threshold: THRESHOLD });
        if (!p) p = findText(img, "使用");   // OCR 兜底（见下）
    }
    if (p) {
        log("找到: (" + p.x + ", " + p.y + ")");
        if (!click(p.x, p.y)) toastLog("点击失败，请确认无障碍服务已开启");
        sleep(CLICK_REST);           // 点击后停顿等界面刷新，防连点/重复点
    } else {
        sleep(POLL_MS);              // 空转轮询；sleep 内部会被停止请求打断
    }
}
```

多模板变体：预加载进数组 `TPLS`，一轮内 `for (i…&& !p)` 逐个 `findImage`，命中即点。

### 模式 C：OCR 兜底（模板失配时按文字点按钮中心）

```javascript
var ocrOn = ocr.init();
var lastOcrAt = 0;

/** 精确/规则匹配，最多每 2 秒一次（OCR 较重，别每轮都跑） */
function findText(img, text) {
    var now = Date.now();
    if (!ocrOn || now - lastOcrAt < 2000) return null;
    lastOcrAt = now;
    var items = ocr.recognize(img);
    for (var i = 0; i < items.length; i++) {
        var t = String(items[i].text).replace(/\s/g, "");
        if (/* 精确: */ t === text /* 或规则: */ || /^确定[(（]\d+[)）]$/.test(t)) {
            return { x: items[i].centerX(), y: items[i].centerY() };  // 是方法，不是属性
        }
    }
    return null;
}
```

- `ocr.recognize` 返回 `OcrItem[]`：`{text, left/top/right/bottom, bounds, centerX(), centerY()}`。
- **优先用 recognize 白名单匹配**；`ocr.findText(img, s)` 内部是「精确→包含」，
  会被正文里出现的同词误命中（如「是否确定退出？」点在句子上），慎用或只传无歧义的短词。

## 3. 选型与其他要点

| 场景 | 首选 |
|---|---|
| 按钮有固定图样 | 模板 `findImage/waitImage`，`threshold` 0.9 起调（误匹配→0.92+，漏→0.85） |
| 样式固定但要读图内文字 | `images.findImageWithText(img, tpl, {padding:8})` → `{x,y,text,items}` |
| 多个相同按钮 | `findAllImages`，按左上→右下排序逐个处理 |
| 纯色状态条 | `findColor/findMultiColors`，`threshold=16` |
| 文字无固定图样 | OCR（模式 C） |
| 等出现/等消失 | `waitImage` / `waitImageVanish`（必须给有限 timeout） |

**模板匹配没有多尺度**：模板必须是**目标设备原生分辨率**的裁剪图，
不放 `images/` 不会自动缩放；同 UI 不同尺寸要各截一份模板（如弹窗大小两种 → 两张模板）。
仓库根 `png/` 存模板原始裁剪，正式素材放任务 `images/` 下（两处可同名同图）。

## 4. 内置任务机制（assets/tasks/）

- 新增默认任务 = 在 `app/src/main/assets/tasks/<任务名>/` 建目录并放三件套。
  App 启动时 `ensureBuiltinTasks` 全量复制到设备；**已存在的目录不覆盖**（升级保留用户改动），
  删掉任务或「重置为官方版本」会从 assets 重新复制。
- 目录名/素材文件名**支持中文**，assets 直接中文打包（`task.js`/`config.json` 建议与目录同名一致）。
- `config.json` 字段：`name/desc/target/loop{count,interval}/designSize?`（合法 JSON 无注释）。
  `loop` 会让宿主把**整个脚本反复执行**；模式 B 自带无限循环时应保持 `count:1`。
- 打包验证：构建后用 zip 工具确认 APK 内含 `assets/tasks/<任务名>/...` 全部文件。

## 5. 验收工作流（写完必做）

1. `node --check tasks/<任务名>/task.js`（语法层）+ `node -e "JSON.parse(...)"` 验 config。
2. 逐条过清单：权限顺序、退出不在 try 内、每个 wait 有超时、每个点击有兜底、
   模板缺失有提示、OCR 有节流、循环内有 `sleep`（否则停止请求无法及时打断）。
3. 需要装包验证时：`gradle :app:assembleDebug --console=plain`（无 wrapper，用全局 gradle，
   详见 opencode 技能 `android-apk-build`），再用 aapt2/badging + zip 列表核对产物。
4. 不主动提交 git；除非用户明确说 commit/push。
