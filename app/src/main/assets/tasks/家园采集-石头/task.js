// 内置任务：家园采集-石头。坐标来自 docs/点击坐标.md，请确认游戏分辨率和布局一致。
// 必需图片未出现时持续等待；可通过悬浮条停止任务。
auto.waitFor(5000);
if (!auto.service) { toastLog("请先开启无障碍服务"); exit(); }
if (!requestScreenCapture()) { toastLog("请先授权屏幕录制"); exit(); }

var THRESHOLD = 0.8;
var backHill = images.read("images/家园后山按钮.png");
var resourceField = images.read("images/家园-送我去资源场.png");
var claim = images.read("images/家园-资源场-领取.png");
var resource = images.read("images/后山-石头.png");
var mine = images.read("images/后山开采资源.png");
var templates = [backHill, resourceField, claim, resource, mine];
for (var t = 0; t < templates.length; t++) {
    if (!templates[t]) {
        toastLog("家园采集-石头素材不完整，请检查 images/ 下的 5 张图片");
        for (var r = 0; r < templates.length; r++) if (templates[r]) images.release(templates[r]);
        exit();
    }
}

function findOnce(tpl) {
    var shot = captureScreen();
    if (!shot) return null;
    try {
        return images.findImage(shot, tpl, { threshold: THRESHOLD });
    } finally {
        images.release(shot);
    }
}

function waitForTemplate(tpl, label) {
    var misses = 0;
    while (true) {
        var point = findOnce(tpl);
        if (point) return point;
        misses++;
        if (misses % 15 === 0) log("仍在等待" + label);
        sleep(700);
    }
}

function tapAt(label, x, y, restMs) {
    if (!click(x, y)) throw new Error(label + "点击失败，请检查无障碍服务");
    log("已点击" + label);
    sleep(restMs);
}

function tapTemplate(label, tpl, restMs) {
    var point = waitForTemplate(tpl, label);
    if (!clickRandom(point.x, point.y)) throw new Error(label + "点击失败，请检查无障碍服务");
    log("已点击" + label + " (" + point.x + ", " + point.y + ")");
    sleep(restMs);
}

function claimIfAvailable() {
    for (var attempt = 1; attempt <= 3; attempt++) {
        var point = findOnce(claim);
        if (point) {
            if (!clickRandom(point.x, point.y)) throw new Error("资源场领取点击失败，请检查无障碍服务");
            log("已领取资源场奖励");
            sleep(1200);
            return;
        }
        if (attempt < 3) sleep(1200);
    }
    log("未找到资源场领取按钮，继续采集");
}

try {
    tapAt("地图", 1764, 25, 1200);
    tapAt("家园", 1356, 694, 2500);
    tapAt("家园地图", 1743, 565, 1200);
    tapTemplate("家园后山按钮", backHill, 1500);
    tapTemplate("送我去资源场", resourceField, 2500);
    claimIfAvailable();
    tapTemplate("后山-石头", resource, 5000);
    tapTemplate("后山开采资源", mine, 800);
    toastLog("家园采集-石头完成");
} catch (e) {
    toastLog("家园采集-石头中断：" + e);
    throw e;
} finally {
    for (var i = 0; i < templates.length; i++) images.release(templates[i]);
}
