// 内置任务：竞技场。请先打开游戏主界面，素材按当前游戏分辨率截取。
// 最多点击 15 次「随机战」；血条可选，找到后等待 5 秒，再检查下一步界面。
auto.waitFor(5000);
if (!auto.service) { toastLog("请先开启无障碍服务"); exit(); }
if (!requestScreenCapture()) { toastLog("请先授权屏幕录制"); exit(); }

var THRESHOLD = 0.8;
var MAX_BATTLES = 15;
var arena = images.read("images/竞技场.png");
var randomBattle = images.read("images/随机战.png");
var battleBar = images.read("images/战斗血条.png");
var defeat = images.read("images/战败装备强化.png");
if (!arena || !randomBattle || !battleBar || !defeat) {
    toastLog("竞技场素材不完整，请检查 images/ 下的 4 张图片");
    exit();
}

function screenshot() {
    var shot = captureScreen();
    if (!shot) throw new Error("截屏失败");
    return shot;
}

function find(tpl, timeoutSec) {
    var deadline = Date.now() + timeoutSec * 1000;
    while (true) {
        var shot = screenshot();
        var point;
        try {
            point = images.findImage(shot, tpl, { threshold: THRESHOLD });
        } finally {
            images.release(shot);
        }
        if (point) return point;
        if (Date.now() >= deadline) return null;
        sleep(700);
    }
}

function tap(label, x, y) {
    if (!click(x, y)) throw new Error(label + "点击失败，请检查无障碍服务");
    log(label);
}

function tapImage(label, point) {
    if (!clickRandom(point.x, point.y)) throw new Error(label + "点击失败，请检查无障碍服务");
    log(label + " (" + point.x + ", " + point.y + ")");
}

// 连续两帧看到同一结果才继续，避免界面切换瞬间误判。
function waitBattleResult(timeoutSec) {
    var deadline = Date.now() + timeoutSec * 1000;
    var previous = null;
    while (Date.now() < deadline) {
        var shot = screenshot();
        var result = null;
        try {
            if (images.findImage(shot, randomBattle, { threshold: THRESHOLD })) result = "ready";
            else if (images.findImage(shot, defeat, { threshold: THRESHOLD })) result = "defeat";
        } finally {
            images.release(shot);
        }
        if (result && result === previous) return result;
        previous = result;
        sleep(800);
    }
    return null;
}

var arenaOpened = false;
var battles = 0;
try {
    var entrance = find(arena, 15);
    for (var retry = 1; !entrance && retry <= 3; retry++) {
        log("未找到竞技场入口，第 " + retry + "/3 次返回临安重试");
        tap("点击地图", 1764, 25);
        sleep(1200);
        tap("点击临安", 1101, 762);
        sleep(2500);
        entrance = find(arena, 8);
    }
    if (entrance) {
        tapImage("进入竞技场", entrance);
        arenaOpened = true;
        sleep(1500);

        for (var i = 0; i < MAX_BATTLES; i++) {
            var button = find(randomBattle, 15);
            if (!button) throw new Error("未找到随机战按钮，已挑战 " + battles + " 次");
            tapImage("随机战 " + (i + 1) + "/" + MAX_BATTLES, button);
            battles++;
            sleep(1200);

            if (find(battleBar, 0)) {
                log("第 " + battles + " 次战斗检测到血条，等待 5 秒");
                sleep(5000);
            } else {
                log("第 " + battles + " 次战斗未检测到血条，继续检查结果");
            }

            var result = waitBattleResult(180);
            if (!result) throw new Error("第 " + battles + " 次战斗等待结果超时");
            if (result === "defeat") {
                log("检测到战败装备强化，关闭战败窗口");
                tap("竞技场-战败关闭", 1404, 340);
                sleep(1500);
            } else {
                log("随机战按钮重新出现，可以继续挑战");
            }
        }
    }
} catch (e) {
    toastLog("竞技场中断：" + e);
    throw e;
} finally {
    try {
        if (arenaOpened) {
            tap("关闭竞技场", 1666, 103);
            sleep(500);
        }
    } finally {
        images.release(arena);
        images.release(randomBattle);
        images.release(battleBar);
        images.release(defeat);
    }
}
if (arenaOpened) toastLog("竞技场完成：共点击 " + battles + " 次随机战");
else toastLog("返回临安重试 3 次后仍未找到竞技场入口，任务结束");
