// 内置任务：90恩仇。请先进入 90 恩仇副本，停在可看到第 16 层目标的界面。
// 顺序：16 层 → 传送 → 21 层 → 26 层；每层点击「华山之巅」5 次。
// 图片暂时未出现时持续等待；需要提前结束可点悬浮条的停止按钮。
auto.waitFor(5000);
if (!auto.service) { toastLog("请先开启无障碍服务"); exit(); }
if (!requestScreenCapture()) { toastLog("请先授权屏幕录制"); exit(); }

var THRESHOLD = 0.8;
var stage16 = images.read("images/90恩仇16.png");
var portal = images.read("images/90恩仇20_21传送.png");
var stage21 = images.read("images/90恩仇21.png");
var stage26 = images.read("images/90恩仇26.png");
var huashan = images.read("images/华山之巅.png");
var victoryClose = images.read("images/恩仇胜利关闭.png");
var leave = images.read("images/恩仇离开副本.png");
var templates = [stage16, portal, stage21, stage26, huashan, victoryClose, leave];
for (var t = 0; t < templates.length; t++) {
    if (!templates[t]) {
        toastLog("90恩仇素材不完整，请检查 images/ 下的 7 张图片");
        for (var r = 0; r < templates.length; r++) if (templates[r]) images.release(templates[r]);
        exit();
    }
}

function find(tpl, label) {
    var misses = 0;
    while (true) {
        var shot = captureScreen();
        if (shot) {
            var point;
            try {
                point = images.findImage(shot, tpl, { threshold: THRESHOLD });
            } finally {
                images.release(shot);
            }
            if (point) return point;
        }
        misses++;
        if (misses % 15 === 0) log("仍在等待" + label);
        sleep(700);
    }
}

function tapImage(label, tpl, restMs) {
    var point = find(tpl, label);
    if (!clickRandom(point.x, point.y)) throw new Error(label + "点击失败，请检查无障碍服务");
    log("已点击" + label + " (" + point.x + ", " + point.y + ")");
    sleep(restMs);
}

function tapVictoryClose() {
    for (var attempt = 1; attempt <= 3; attempt++) {
        var shot = captureScreen();
        var point = null;
        if (shot) {
            try {
                point = images.findImage(shot, victoryClose, { threshold: THRESHOLD });
            } finally {
                images.release(shot);
            }
        }
        if (point) {
            if (!clickRandom(point.x, point.y)) throw new Error("恩仇胜利关闭点击失败，请检查无障碍服务");
            log("已点击恩仇胜利关闭 (" + point.x + ", " + point.y + ")");
            sleep(1500);
            return;
        }
        if (attempt < 3) sleep(1500);
    }
    log("恩仇胜利关闭连续 3 次未找到，跳过并继续下一步");
}

function attackFive(stage) {
    for (var i = 1; i <= 5; i++) {
        tapImage(stage + " 华山之巅 " + i + "/5", huashan, 1200);
    }
}

try {
    tapImage("90恩仇16", stage16, 1500);
    attackFive("16 层");
    tapVictoryClose();

    tapImage("90恩仇20_21传送", portal, 2000);
    tapImage("90恩仇21", stage21, 1500);
    attackFive("21 层");
    tapVictoryClose();

    tapImage("90恩仇26", stage26, 1500);
    attackFive("26 层");
    tapImage("恩仇离开副本", leave, 1000);
    toastLog("90恩仇完成，已离开副本");
} catch (e) {
    toastLog("90恩仇中断：" + e);
    throw e;
} finally {
    for (var j = 0; j < templates.length; j++) images.release(templates[j]);
}
