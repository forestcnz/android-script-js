// 内置示例：找图点击演示
// 用法：
//   1) 首次运行：申请截屏授权，并把当前屏幕保存为 images/screen.png；
//   2) 用任意图片工具从 screen.png 裁出要点击的按钮图片，存成 images/tpl.png；
//   3) 再次运行：脚本会在屏幕上找到该按钮并点击它。
// 相关 API 见 docs/API_REFERENCE.md 第 3 节。

if (!requestScreenCapture()) {           // 申请录屏授权（找图依赖）
    toastLog("请先授权屏幕录制");
    exit();
}

var tpl = images.read("images/tpl.png");
if (!tpl) {
    // 还没有模板：保存一张屏幕截图，供用户裁剪模板
    var shot = captureScreen();
    if (!shot) { toastLog("截屏失败"); exit(); }
    images.save(shot, "images/screen.png");
    toastLog("已保存 images/screen.png，请裁出目标按钮存为 images/tpl.png");
    log("屏幕尺寸: " + images.width(shot) + "x" + images.height(shot));
    exit();
}

log("模板尺寸: " + images.width(tpl) + "x" + images.height(tpl));

// 等待目标出现（最多 10 秒，期间可被停止按钮打断）
var p = waitImage("images/tpl.png", 10, 0.5, { threshold: 0.9 });
if (!p) {
    toastLog("未找到目标图片");
    exit();
}
log("找到目标: (" + p.x + ", " + p.y + ")");

// 找图结果可直接点击
if (click(p.x, p.y)) {
    toastLog("已点击 (" + p.x + ", " + p.y + ")");
} else {
    toastLog("点击失败：请确认无障碍服务已开启");
}

// 等目标消失（例如点击后界面切换），最多 5 秒
if (waitImageVanish("images/tpl.png", 5, 0.5, { threshold: 0.9 })) {
    log("目标已消失，界面已切换");
} else {
    log("目标仍在屏幕上");
}
log("找图演示完成");
