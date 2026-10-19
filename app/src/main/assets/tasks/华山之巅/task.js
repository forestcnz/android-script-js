// 内置任务：华山之巅（循环找图，找到就点击按钮中心）
// 界面出现「华山之巅」按钮时自动点击进入；点击后等界面刷新再继续监控。
auto.waitFor(5000);
if (!auto.service) { toastLog("请先开启无障碍服务"); exit(); }
if (!requestScreenCapture()) { toastLog("请先授权屏幕录制"); exit(); }

var THRESHOLD = 0.9;    // 模板相似度阈值（0~1）
var POLL_MS = 800;      // 未找到时的轮询间隔（毫秒）
var CLICK_REST = 1200;  // 点击后停顿，等界面刷新，避免连点

// 预加载模板（找不到素材立即提示退出）
var tpl = images.read("images/华山之巅.png");
if (!tpl) { toastLog("缺少素材 images/华山之巅.png"); exit(); }

// OCR 兜底（初始化失败则只用模板找图）
var ocrOn = ocr.init();
var lastOcrAt = 0;

log("华山之巅开始：循环监控屏幕，发现「华山之巅」按钮即点击（停止请按悬浮条）");
while (true) {
    var p = null;
    var img = captureScreen();
    if (img) {
        p = images.findImage(img, tpl, { threshold: THRESHOLD });
        if (!p) p = findText(img, "华山之巅");
    }
    if (p) {
        log("找到「华山之巅」: (" + p.x + ", " + p.y + ")");
        if (!click(p.x, p.y)) toastLog("点击失败，请确认无障碍服务已开启");
        sleep(CLICK_REST);
    } else {
        sleep(POLL_MS);
    }
}

/** OCR 兜底：按文字找按钮中心（精确匹配，最多每 2 秒一次） */
function findText(img, text) {
    var now = Date.now();
    if (!ocrOn || now - lastOcrAt < 2000) return null;
    lastOcrAt = now;
    var items = ocr.recognize(img);
    for (var i = 0; i < items.length; i++) {
        var t = String(items[i].text).replace(/\s/g, "");
        if (t === text) return { x: items[i].centerX(), y: items[i].centerY() };
    }
    return null;
}
