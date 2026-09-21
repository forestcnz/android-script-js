// 内置示例：签到脚手架
// 用途：作为「打开目标 App → 等按钮出现 → 点击签到」的起手模板，按注释替换为你的目标即可。
// 坐标约定：若在 config.json 里填了 designSize，脚本坐标按该基准分辨率编写，运行时自动换算。

auto.waitFor(5000);                          // 等待无障碍服务（坐标操作依赖）
if (!auto.service) { toastLog("请先开启无障碍服务"); exit(); }

// 找图/找色依赖录屏授权；未授权时降级为纯坐标流程
var canFindImage = requestScreenCapture();
log(canFindImage ? "截屏已就绪" : "无截屏能力，改为纯坐标流程");

// 1) 打开目标应用（也可在 config.json 配置 target，由宿主运行前自动拉起）
//    app.launch("com.example.app");  ← 当前版本请用 config.json 的 target 字段

// 2) 等签到按钮出现并点击；没有模板图时用坐标兜底
var clicked = false;
if (canFindImage) {
    var p = waitImage("images/sign_button.png", 8, 0.5, { threshold: 0.9 });
    if (p) {
        click(p.x, p.y);
        clicked = true;
        log("已按图找到签到按钮: (" + p.x + ", " + p.y + ")");
    } else {
        log("未匹配到签到按钮图，使用坐标兜底");
    }
}
if (!clicked) {
    click(540, 1200);                        // TODO: 替换为你的签到按钮坐标
    log("已按坐标点击 (540, 1200)");
}
sleep(1500);

// 3) 校验：出现「已签到」文字或按钮消失即视为成功
if (canFindImage) {
    var done = waitImage("images/signed.png", 3, 0.5, { threshold: 0.9 });
    log(done ? "签到成功 ✓" : "未检测到成功标记，请检查素材或坐标");
} else {
    log("无截屏能力，跳过结果校验");
}
