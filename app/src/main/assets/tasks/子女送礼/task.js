// 内置任务：子女送礼。请先打开游戏主界面，并使用横屏布局。
// 坐标来自 docs/点击坐标.md，按游戏原始横屏坐标点击；运行前确认分辨率和布局一致。
auto.waitFor(5000);
if (!auto.service) { toastLog("请先开启无障碍服务"); exit(); }

function tap(label, x, y, waitMs) {
    if (!click(x, y)) throw new Error(label + "点击失败，请检查无障碍服务");
    log(label);
    sleep(waitMs);
}

var children = [
    [519, 189],
    [561, 349],
    [547, 493],
    [508, 628],
    [529, 775],
    [531, 905]
];

try {
    tap("进入家园", 1356, 694, 2500);
    tap("打开子女面板", 1676, 637, 1500);

    for (var i = 0; i < children.length; i++) {
        var child = children[i];
        log("开始给孩子" + (i + 1) + "送礼");
        tap("选择孩子" + (i + 1), child[0], child[1], 1800);
        tap("打开送礼窗口", 1049, 890, 1800);
        for (var count = 0; count < 5; count++) {
            tap("孩子" + (i + 1) + "送包子 " + (count + 1) + "/5", 538, 776, 650);
        }
        tap("关闭送礼窗口", 1666, 103, 1800);
    }

    tap("关闭子女界面", 1666, 103, 1500);
    toastLog("子女送礼完成：6 个孩子各送 5 个包子");
} catch (e) {
    toastLog("子女送礼中断：" + e);
    throw e;
}
