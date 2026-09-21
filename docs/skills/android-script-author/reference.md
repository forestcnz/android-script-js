# API 速查（AI 用，已核对引擎实现）

> 权威来源：`app/src/main/java/com/script/js/core/engine/ScriptEngine.kt` 的注入绑定，
> 与 `docs/API_REFERENCE.md` 对齐。本页只列**实测已绑定/可用**的 API，并标出文档漂移点。
> 运行环境：QuickJS（ES2020+，Promise / async / await 可用）。

**权限标注**：**[基础]** 无障碍即可用；**[截屏]** 需 `requestScreenCapture()` 授权（或无 Root/Shizuku 静默通道）。

---

## 1. 全局函数

| API | 返回 | 说明 |
|---|---|---|
| `sleep(ms)` | 无 | 同步休眠，可被宿主停止打断 |
| `random(min, max)` | number | `[min, max]` 闭区间随机整数；`max<=min` 时返回 `min` |
| `exit()` | 无 | 结束脚本（内部抛退出异常） |
| `toastLog(msg)` | 无 | Toast + 控制台输出 |
| `log(msg)` / `print(msg)` | 无 | 控制台普通输出 |
| `console.log/info/warn/error(...)` | 无 | 控制台输出，级别影响日志着色 |
| `__scriptPath__` | string | 当前脚本绝对路径（素材相对路径基于脚本目录） |

## 2. 无障碍

```javascript
auto.waitFor(5000);   // 阻塞等待服务开启，超时返回 false
auto.service;         // boolean，服务是否连接
auto(5000);           // 打开无障碍设置页并最多等 5000ms
```

> ⚠️ **必须传超时**：`auto.waitFor()`（无参）在引擎里等价 `timeout=0`，服务未就绪会**立即返回 false**，
> 不是文档描述的“阻塞直到开启”。统一写 `auto.waitFor(5000)`。

## 3. 坐标与手势（全局，坐标经 adapter 换算）**[基础]**

| API | 返回 | 默认参数 |
|---|---|---|
| `click(x, y[, duration])` | boolean | `duration=30`(ms) |
| `clickRandom(x, y[, range, duration])` | boolean | `range=5`(px), `duration=100` |
| `longClick(x, y[, duration])` | boolean | `duration=600` |
| `press(x, y[, duration])` | boolean | `duration=500`（文档写必填，实际可省） |
| `swipe(x1, y1, x2, y2[, duration])` | boolean | `duration=500` |
| `swipeRandom(x1, y1, x2, y2[, range, duration])` | boolean | `range=10`, `duration=500` |
| `gesture(duration, [x,y], [x,y], ... [, delay])` | boolean | 单指轨迹；`delay` 可省 |
| `gestures([duration,[x,y],...[,delay]], ...)` | boolean | 多指并动，最多 10 指；**各指同时起手**，delay 不一致会统一 |
| `gesturesAvailable()` | boolean | 手势能力是否可用 |

> 无 Root 且无障碍不可用时，以上手势类返回 `false`；有 Root 时宿主自动降级 `input tap/swipe`（折线手势退化为直线滑动）。

## 4. 截屏与图片 **[截屏]**

```javascript
requestScreenCapture();                 // boolean，申请录屏授权（landscape 参数被忽略）
requestScreenCaptureAsync();            // Promise<boolean>（内部同步，等价）
captureScreen();                        // Image/null
waitImage(tpl, timeout=10, interval=0.5, options?);       // Point/null
waitImageVanish(tpl, timeout=10, interval=0.5, options?); // boolean
```

| `images.*` | 返回 | 说明 |
|---|---|---|
| `findImage(img, tpl, options?)` | Point/null | `options={threshold=0.9, region:[x,y,w?,h?]}` |
| `findAllImages(img, tpl, options?)` | Point[] | `options` 另加 `max=5`；相邻命中聚合，按左上→右下排序 |
| `existsImage(img, tpl, options?)` | boolean | 模板是否在屏 |
| `findImageInRegion(img, tpl, x, y[, w, h, threshold])` | Point/null | 区域找图简写 |
| `findImageWithText(img, tpl, options?)` | Match/null | 找图 + 对匹配区域 OCR，`options` 另加 `padding=0` |
| `findColor(img, color, options?)` | Point/null | `options={region, threshold=16}` |
| `findMultiColors(img, firstColor, offsets, options?)` | Point/null | `offsets=[[dx,dy,color],...]`，`threshold=16` |
| `read(path)` | Image/null | 读图，`path` 相对脚本目录 |
| `save(image, path[, format, quality])` | boolean | `format` png/jpg，`quality=100` |
| `clip(img, x, y, w, h)` | Image | 裁剪 |
| `pixel(img, x, y)` | number/null | 取像素色值（ARGB） |
| `width(img)` / `height(img)` | number | 图片宽高（Image 对象也有 `.width/.height` 属性） |
| `release(img)` | 无 | 释放图片句柄 |

- `tpl` 可以是 `"images/x.png"` 相对路径，也可以是 `images.read(...)` 得到的 Image 对象。
- `region` 用**脚本侧坐标**（启用 `designSize` 时按设计基准填），引擎换算到真机。
- **模板图不做坐标缩放**：找图在真机截图上做原生像素匹配，模板必须是目标设备原生分辨率的裁剪图。

## 5. colors 颜色工具（纯 JS，无需截屏）

```javascript
colors.parseColor("#ff4400");               // number（#RRGGBB / #AARRGGBB）
colors.rgb(r,g,b) / colors.argb(a,r,g,b);   // number
colors.red/green/blue/alpha(c);             // 通道值
colors.toString(c);                          // "#AARRGGBB"
colors.isSimilar(c1, c2[, threshold=4, algorithm="diff"]); // diff/rgb/hsv
```

## 6. ocr 文字识别 **[截屏]**（MLKit 中文模型内置，已实现）

```javascript
ocr.init("mlkit");                          // 使用前建议先初始化；"paddle" 会回退 mlkit
ocr.ready();                                // boolean
ocr.recognize(image, options?);             // OcrItem[]；options={region:[x,y,w,h]}
ocr.recognizeText(image, options?);         // string
ocr.findText(image, text, options?);        // OcrItem/null
ocr.findAllText(image, text, options?);     // OcrItem[]
```

## 7. adapter 分辨率适配

```javascript
adapter.setDesignSize(1080, 1920);  // 声明基准，此后坐标 API 与找图/找色返回值双向换算
adapter.reset();                    // 取消缩放
adapter.scale(x, y);                // [真机x, 真机y]
adapter.enabled();                  // boolean
```

> `config.json` 的 `designSize` 会由宿主在每次运行前自动 `setDesignSize`，脚本里无需重复调用。

## 8. require 模块

```javascript
require("images") === images
require("colors") === colors
require("ocr")    === ocr
require("adapter")=== adapter
// 其他名字 → throw new Error("模块未实现: <name>")
```

## 9. 返回对象

| 对象 | 成员 |
|---|---|
| `Point` | `x`, `y`（可直接传 `click`） |
| `Rect` | `left`, `top`, `right`, `bottom` |
| `OcrItem` | `text`, `bounds`, `confidence`, `left/top/right/bottom`, `centerX()`, `centerY()` |
| `Match`（findImageWithText） | `x`, `y`, `bounds`, `text`, `items: OcrItem[]` |

## 10. 禁用 / 不存在（用了会报错或静默失败）

- `waitForAction(...)` — **文档有、引擎未绑定**，用了会 `undefined is not a function`。自己写 `while + sleep`。
- 控件树 API：`text()` / `desc()` / `id()` / `className()` / `UiSelector` / `UiObject`。
- `app.launch()` / `app` 对象 — 拉起目标应用请用 `config.json.target`。
- 网络/HTTP、多线程/线程、传感器、事件监听、`events`、`floaty`、`dialogs`、`storages`、`files`、`engines` 等 Auto.js 扩展模块。
- `require` 非 §8 的模块名；`require` 本地 `.js` 文件（不支持自定义模块）。
- 绝对路径素材（`/sdcard/...`）：路径按任务目录相对解析，绝对路径行为不可靠。

## 11. 文档漂移点 / 陷阱（以本页为准）

1. `API_REFERENCE.md` §1.2 列出 `waitForAction`，但引擎未绑定 → 不可用。
2. `API_REFERENCE.md` 模块表称 `ocr`「当前调用会抛模块未实现」→ **已过时**；OCR 已用 MLKit 实现并绑定。
3. `API_REFERENCE.md` §2.1 把 `press(x,y,duration)` 标为必填 → 实际 `duration` 可省，默认 500ms。
4. `images.width/height/release` 与 `adapter.enabled()` 在手册表格中未列，但引擎已实现可用。
5. `auto.waitFor()` 无参时**不会阻塞**（引擎按 timeout=0 立即返回 false），务必传超时。
6. `exit()` 靠抛异常中止；写在 JS `try` 块内会被 `catch(e)` 吞掉，脚本不会退出。退出判断放 `try` 外。
