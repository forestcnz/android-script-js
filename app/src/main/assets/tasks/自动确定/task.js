// 内置任务：自动确定（循环找图，找到就点击按钮中心）
// 覆盖两类按钮：
//   1) 不带计时的「确定」——模板 images/确定.png
//   2) 带倒计时的「确定(27)/(28)」——模板 images/计时确定27.png、images/计时确定28.png
// 倒计时数值每秒都在变（26、25…），模板只有 27/28 两种，
// 所以再用 OCR 按「确定(数字)」兜底，任意倒计时值都能点中。
auto.waitFor(5000);
if (!auto.service) { toastLog("请先开启无障碍服务"); exit(); }
if (!requestScreenCapture()) { toastLog("请先授权屏幕录制"); exit(); }

var THRESHOLD = 0.9;    // 模板相似度阈值（0~1）
var POLL_MS = 800;      // 未找到时的轮询间隔（毫秒）
var CLICK_REST = 1200;  // 点击后停顿，等界面刷新，避免连点

// 预加载模板：缺失的素材跳过并提示
var TPL_NAMES = ["确定.png", "计时确定27.png", "计时确定28.png"];
var TPLS = [];
for (var i = 0; i < TPL_NAMES.length; i++) {
    var tpl = images.read("images/" + TPL_NAMES[i]);
    if (tpl) TPLS.push(tpl);
    else log("跳过缺失素材: images/" + TPL_NAMES[i]);
}
if (!TPLS.length) { toastLog("缺少素材，请检查任务目录 images/"); exit(); }

// OCR 兜底（初始化失败则只用模板找图）
var ocrOn = ocr.init();
var lastOcrAt = 0;

log("自动确定开始：循环监控屏幕，发现「确定」按钮即点击（停止请按悬浮条）");
while (true) {
    var p = null;
    var img = captureScreen();
    if (img) {
        for (var i = 0; i < TPLS.length && !p; i++) {
            p = images.findImage(img, TPLS[i], { threshold: THRESHOLD });
        }
        if (!p) p = findConfirm(img);
    }
    if (p) {
        log("找到「确定」: (" + p.x + ", " + p.y + ")");
        if (!click(p.x, p.y)) toastLog("点击失败，请确认无障碍服务已开启");
        sleep(CLICK_REST);
    } else {
        sleep(POLL_MS);
    }
}

/** OCR 兜底：匹配「确定(倒计时)」或纯「确定」字样，返回按钮中心 {x, y}；最多每 2 秒一次 */
function findConfirm(img) {
    var now = Date.now();
    if (!ocrOn || now - lastOcrAt < 2000) return null;
    lastOcrAt = now;
    var items = ocr.recognize(img);
    for (var i = 0; i < items.length; i++) {
        var t = String(items[i].text).replace(/\s/g, "");
        if (/^确定[(（]\d+[)）]$/.test(t) || t === "确定") {
            return { x: items[i].centerX(), y: items[i].centerY() };
        }
    }
    return null;
}
