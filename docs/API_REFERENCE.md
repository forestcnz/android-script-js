# 脚本 API 手册（脚本作者版）

> 仅包含两大核心能力：**坐标操作**与**图片匹配点击**。
> 权限标注：**[基础]** = 开启无障碍服务即可用；**[截屏]** = 另需屏幕录制授权。
> 运行环境：ES2020+（Promise / async / await 可用），CommonJS `require` 可用。

---

## 0. 快速上手

```javascript
auto.waitFor();                          // 等待无障碍服务开启（坐标点击依赖）
if (!requestScreenCapture()) {           // 申请屏幕录制授权（找图依赖）
    toastLog("请授权屏幕录制");
    exit();
}

// 方式一：图片匹配点击
let p = waitImage("images/confirm.png", 10);   // 等待按钮图出现，最多10秒
if (p) {
    click(p.x, p.y);                           // 找图返回坐标可直接点击
} else {
    click(540, 1200);                          // 兜底：坐标点击
    toastLog("未匹配到图片，使用坐标点击");
}
```

**内置模块一览**（模块既是全局对象，也可用 `require("...")` 引用）：

| 模块 | 用途 | 使用方式 |
|---|---|---|
| 坐标手势 | 点击/滑动/手势 | 全局函数 |
| `images` | 截屏/找图/等图/找色 | `require("images")`，`requestScreenCapture`/`captureScreen` 为全局 |
| `colors` | 颜色解析/比较 | `require("colors")` |
| `ocr` | 文字识别定位点击 | `require("ocr")`（V3 提供，当前调用会抛「模块未实现」） |
| `adapter` | 分辨率适配 | `require("adapter")` |

---

## 1. 全局函数

### 1.1 无障碍（坐标点击依赖）
```javascript
auto();             // 跳转开启无障碍服务
auto(5000);         // 跳转开启，最多等 5000ms
auto.waitFor();     // 阻塞直到开启（推荐脚本开头调用）
auto.service;       // true/false 服务是否连接
```

### 1.2 基础工具
| 函数 | 返回值 | 说明 |
|---|---|---|
| `sleep(ms)` | 无 | 同步休眠 |
| `random(min, max)` | number | `[min, max]` 随机整数 |
| `exit()` | 无 | 结束当前脚本 |
| `toastLog(msg)` | 无 | Toast 提示 + 控制台输出 |
| `log(msg)` / `print(msg)` | 无 | 控制台输出 |
| `waitForAction(fn, timeout?)` | boolean | 每 200ms 轮询 `fn()` 直到 true 或超时 |
| `__scriptPath__` | string | 脚本绝对路径（素材相对路径基于脚本目录） |

---

## 2. 坐标与手势（全局）

> 所有坐标基于**当前实际分辨率**；配合第 6 节 `adapter` 可按设计基准自动缩放。

### 2.1 点击 / 滑动
```javascript
click(540, 1200);                      // 坐标点击 [基础]
clickRandom(540, 1200);                // 随机偏移点击，默认半径5px
clickRandom(540, 1200, 10, 80);        // 偏移半径10px，按压80ms
longClick(540, 1200);                  // 长按600ms
longClick(540, 1200, 1500);            // 长按1.5秒
press(540, 1200, 800);                 // 按住800ms后抬起
swipe(540, 1800, 540, 600, 500);       // 上滑，500ms
swipeRandom(540, 1800, 540, 600);      // 端点带随机偏移的滑动
```

| 函数 | 返回值 | 说明 |
|---|---|---|
| `click(x, y)` | boolean | 坐标点击 **[基础]** |
| `clickRandom(x, y, range?=5, duration?=100)` | boolean | 随机偏移点击（防检测） |
| `longClick(x, y, duration?=600)` | boolean | 长按 |
| `press(x, y, duration)` | boolean | 定长按压 |
| `swipe(x1, y1, x2, y2, duration?=500)` | boolean | 滑动 |
| `swipeRandom(x1,y1,x2,y2, range?=10, duration?=500)` | boolean | 随机端点滑动 |

### 2.2 手势
```javascript
// 单指手势：从(500,1500)划到(500,500)，共800ms
gesture(800, [500, 1500], [500, 1000], [500, 500]);

// 延迟200ms后开始手势
gesture(800, [500, 1500], [500, 500], 200);

// 双指缩放：两根手指同时动作
gestures(
    [500, [800, 300], [500, 800], [300, 900]],
    [500, [900, 300], [700, 800], [900, 900]]
);
```

| 函数 | 返回值 | 说明 |
|---|---|---|
| `gesture(duration, [x,y]...[, delay])` | boolean | 单指轨迹手势 **[基础]** |
| `gestures([duration, [x,y]..., delay?], ...)` | boolean | 多指并动手势（最多 10 指） |
| `gesturesAvailable()` | boolean | 手势能力是否可用 |

> 多指手势受系统限制必须**同时起手**：各指的 `delay` 若不一致会被统一为同时起手并输出一条警告日志；需要错峰时请改用多次单指 `gesture()` 串接。

---

## 3. images 截屏与图片匹配

### 3.1 截屏
```javascript
if (!requestScreenCapture()) {        // 系统弹窗授权，成功true
    toastLog("请授权屏幕录制");
    exit();
}
let img = captureScreen();            // 截当前屏幕
```

| 函数 | 返回值 | 说明 |
|---|---|---|
| `requestScreenCapture(landscape?)` | boolean | **[截屏]** 申请录屏授权 |
| `requestScreenCaptureAsync()` | Promise\<boolean\> | 异步申请 |
| `captureScreen()` | Image | **[截屏]** 截取全屏 |

### 3.2 找图
```javascript
let p = images.findImage(captureScreen(), "images/btn.png", {
    threshold: 0.8,               // 相似度阈值0-1
    region: [0, 0, 540, 960]      // 限定区域，加快速度、防误匹配
});
if (p) click(p.x, p.y);           // 找图坐标可直接点击

let q = waitImage("images/loading.png", 10);      // 等图出现，最多10秒
let ok = waitImageVanish("images/loading.png", 5); // 等图消失（加载框），最多5秒

// 找图并同时识别图内文字（如商品卡片：匹配卡片样式 + 读取价格文字）
let m = images.findImageWithText(captureScreen(), "images/card.png", {
    threshold: 0.8,
    padding: 8                  // OCR 区域向四周扩8px，避免边缘文字被裁
});
if (m) {
    click(m.x, m.y);            // 匹配中心，可直接点击
    console.log(m.text);        // 图内识别出的文字，如 "限时 9.9元"
    m.items.forEach(it => console.log(it.text, it.bounds));  // 逐条文本+位置
}
```

| 函数 | 返回值 | 说明 |
|---|---|---|
| `images.findImage(img, tpl, options?)` | Point/null | 模板匹配；`options={threshold=0.9, region:[x,y,w?,h?], max=5}` |
| `images.findImageInRegion(img, tpl, x, y, w?, h?, threshold?)` | Point/null | 区域找图简写 |
| `images.findAllImages(img, tpl, options?)` | Point[] | 全部匹配位置（相邻命中点聚合为一个目标），按从左到右、从上到下排序 |
| `images.existsImage(img, tpl, options?)` | boolean | 快速判断模板是否在屏 |
| `waitImage(tpl, timeout?=10, interval?=0.5, options?)` | Point/null | 全局函数：轮询等图出现；等待期间可被停止打断 |
| `waitImageVanish(tpl, timeout?=10, interval?=0.5, options?)` | boolean | 轮询等图消失（如加载框关闭），超时返回 false |
| `images.findImageWithText(img, tpl, options?)` | Match/null | 找图同时 OCR 图内文字；`options` 同 findImage，另加 `padding=0`（OCR 区域扩边像素）、`engine`；返回 `{x, y, bounds, text, items}` |

> `tpl` 支持模板图路径（相对脚本目录，推荐放 `images/` 子目录）或 Image 对象。
> `findImageWithText` 依赖 OCR 能力（MLKit，已内置）；`padding` 用于把 OCR 区域向四周外扩，避免边缘文字被裁。

**Match 对象**（`findImageWithText` 返回）：

| 成员 | 说明 |
|---|---|
| `x / y` | 匹配中心坐标，可直接传 `click` |
| `bounds` | 模板匹配矩形（Rect） |
| `text` | 图内识别出的拼接文字 |
| `items` | OcrItem[] 逐条文本与位置（见第 4 节） |

### 3.3 找色
```javascript
// 区域内找纯色
let p = images.findColor(captureScreen(), "#ff4400", {
    region: [0, 0, 1080, 500],
    similarity: 0.9
});

// 多点找色：先命中首色，再校验相对偏移点（抗干扰，找图的轻量替代）
let q = images.findMultiColors(captureScreen(), "#ff4400",
    [[10, 0, "#ffffff"], [0, 10, "#ff4400"], [20, 20, "#333333"]],
    { region: [0, 0, 1080, 1920] });
```

| 函数 | 返回值 | 说明 |
|---|---|---|
| `images.findColor(img, color, options?)` | Point/null | `options={region, similarity=0.9, threshold=16}` |
| `images.findColorInRegion(img, color, x, y, w?, h?, threshold?)` | Point/null | 区域找色简写 |
| `images.findAllPointsForColor(img, color, options?)` | Point[] | 所有匹配点 |
| `images.findMultiColors(img, firstColor, offsets, options?)` | Point/null | 多点找色，`offsets=[[dx,dy,color],...]` |
| `images.detectsColor(img, color, x, y, threshold?, algorithm?)` | boolean | 指定点颜色校验 |
| `images.pixel(img, x, y)` | number | 取像素色值 |

### 3.4 读图与保存
```javascript
let img = images.read("/sdcard/a.png");            // 读模板图
images.save(captureScreen(), "shot.png");          // 保存截图（相对脚本目录）
let cut = images.clip(img, 0, 0, 200, 200);        // 裁剪
```

| 函数 | 返回值 | 说明 |
|---|---|---|
| `images.read(path)` | Image | 读模板图 |
| `images.save(image, path, format?, quality?)` | boolean | 保存（png/jpg） |
| `images.clip(img, x, y, w, h)` | Image | 裁剪区域 |

**Point 对象**：`x y`（找图/找色返回，可直接传 `click`）

---

## 4. ocr 文字识别 **[截屏]**

> 输入为截图或任意 Image。首选引擎 **PaddleOCR**（离线内置模型，中英文识别强）；备选 `mlkit`（系统服务、包体更小）。
> OCR 定位点击是对图片匹配的补充：适合按钮无固定图样、但文字固定的场景。

```javascript
let ocr = require("ocr");
ocr.init();                              // 初始化（默认 mlkit，中文模型已内置）
let img = captureScreen();

// 识别全部文字
let items = ocr.recognize(img);
items.forEach(it => console.log(it.text, it.bounds, it.confidence));

// 找指定文字并点击中心
let hit = ocr.findText(img, "立即购买");
if (hit) click(hit.centerX(), hit.centerY());
```

| 函数 | 返回值 | 说明 |
|---|---|---|
| `ocr.init(engine?)` | boolean | 初始化引擎：`"mlkit"`（默认，模型内置无需下载）/ `"paddle"`（本版本未内置，会回退到 mlkit 并给出提示） |
| `ocr.recognize(image, options?)` | OcrItem[] | 识别全部文本；`options={region:[x,y,w,h], engine, language}` |
| `ocr.ready()` | boolean | 引擎是否已初始化 |
| `ocr.recognizeText(image, options?)` | string | 仅返回拼接文本 |
| `ocr.findText(image, text, options?)` | OcrItem/null | 找到第一处指定文本 |
| `ocr.findAllText(image, text, options?)` | OcrItem[] | 全部匹配 |

**OcrItem 成员**：

| 成员 | 说明 |
|---|---|
| `text` | 识别的文本内容 |
| `bounds` | 文本矩形框（Rect） |
| `confidence` | 置信度 0-1 |
| `centerX() / centerY()` | 文本中心坐标（可直接传 `click`） |

---

## 5. colors 颜色工具

```javascript
let colors = require("colors");
let c = colors.parseColor("#ff0000");      // 解析颜色字符串
colors.red(c);      // 255
colors.toString(colors.rgb(255, 0, 0));   // "#ffff0000"
colors.isSimilar("#ff0000", "#ff0101", 16); // true，色差阈值16
```

| 函数 | 返回值 | 说明 |
|---|---|---|
| `colors.parseColor(s)` | number | `"#RRGGBB"` / `"#AARRGGBB"` → 色值 |
| `colors.rgb(r,g,b)` / `colors.argb(a,r,g,b)` | number | 合成色值 |
| `colors.red/green/blue/alpha(c)` | number | 通道值 |
| `colors.toString(c)` | string | 转 `#AARRGGBB` |
| `colors.isSimilar(c1, c2, threshold?=4, algorithm?="diff")` | boolean | 相似判断，algorithm：`diff/rgb/hsv` |

---

## 6. adapter 分辨率适配

```javascript
let adapter = require("adapter");
// 脚本里的坐标一律按 1080x1920 编写，运行时自动缩放到真机
adapter.setDesignSize(1080, 1920);
click(540, 1800);   // 实际会按真机分辨率换算
```

> 启用后**双向换算**：坐标类 API（click/swipe/gesture 等）派发前放大到真机坐标；
> `findImage`/`findColor` 等返回的坐标换算回设计坐标，因此 `click(p.x, p.y)` 语义一致。
> `findImage` 的 `region` 参数同样按设计坐标填写。运行时日志会附上换算后的真机坐标便于排查。

| 函数 | 说明 |
|---|---|
| `adapter.setDesignSize(w, h)` | 声明设计基准分辨率，此后坐标 API 自动缩放 |
| `adapter.reset()` | 取消缩放 |
| `adapter.scale(x, y)` | 返回换算后坐标 `[x, y]` |

---

## 7. 附录：与 Windows 版（PyScriptRunner）API 概念映射

跨端迁移对照（windows-script-py → android-script-js），仅列与坐标/图片匹配相关的 API：

| Windows API | Android 对应 | 差异说明 |
|---|---|---|
| `find_template(path, threshold)` | `images.findImage(img, tpl, {threshold})` | 返回 Point 对象（x/y）而非元组 |
| `find_all_templates` | `images.findAllImages` | 相邻命中点聚合逻辑一致 |
| `find_template_in_region` | `images.findImageInRegion` / `options.region` | - |
| `exists_template` | `images.existsImage` | - |
| `wait_template` | `waitImage` | - |
| `wait_vanish` | `waitImageVanish` | - |
| `click(x, y)` | `click(x, y)` / `clickRandom(x, y)` | 无"光标归位"概念：无障碍手势不占用真实指针 |
| `move_to / scroll` | `gesture()` 轨迹 / `swipe()` | Android 无独立滚轮概念 |
| `sleep / sleep_random` | `sleep()` / `clickRandom` 随机时长 | - |
| `check_stop / exit_script` | `exit()` | 停止由宿主悬浮球统一控制 |
| `log(msg, level)` | `log / console.log` | - |
| `screenshot / screenshot_region / pixel_color` | `captureScreen()` + `images.pixel()` | 取色返回 number 色值，配合 `colors` 工具 |
| `save_image` | `images.save()` | - |
