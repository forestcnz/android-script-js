// task.js — <任务名>
// 数据来源：docs/skills/android-script-author
// 坐标基准：config.json 的 designSize = 1080x1920（未配置则按真机分辨率编写）
// 素材约定：images/ 下的模板图必须是目标设备原生分辨率的裁剪图
//
// 结构：等无障碍 → 申请截屏 → 进入目标界面 → 核心操作 → 结果校验
// 权限降级：无截屏能力时跳过所有找图/找色/OCR，走纯坐标流程

auto.waitFor(5000);                          // 等待无障碍服务（坐标操作依赖）
if (!auto.service) {
    toastLog("请先开启无障碍服务");
    exit();
}

var canImage = requestScreenCapture();       // 找图/找色/OCR 依赖录屏授权
log(canImage ? "截屏已就绪" : "无截屏能力，降级为纯坐标流程");

/**
 * 优先按模板图点击，找不到时用坐标兜底。
 * @param {string} tpl      模板图相对路径，如 "images/sign_button.png"
 * @param {number} timeout  找图超时（秒），默认 8
 * @param {number} fallbackX 兜底坐标 x；传 null 表示不兜底
 * @param {number} fallbackY 兜底坐标 y
 * @returns {boolean} 是否完成点击
 */
function tapImage(tpl, timeout, fallbackX, fallbackY) {
    if (canImage) {
        var p = waitImage(tpl, timeout || 8, 0.5, { threshold: 0.9 });
        if (p) {
            clickRandom(p.x, p.y);               // 随机偏移点击，防检测
            log("图匹点击 " + tpl + " (" + p.x + ", " + p.y + ")");
            return true;
        }
        log("未匹配到 " + tpl);
    }
    if (fallbackX != null) {
        clickRandom(fallbackX, fallbackY);
        log("坐标兜底 (" + fallbackX + ", " + fallbackY + ")");
        return true;
    }
    return false;
}

// 1) 进入目标界面：config.json 的 target 由宿主在运行前自动拉起目标应用
//    注意：exit() 不要写在 try 内，否则会被 catch 当成普通异常吞掉，脚本不会真正退出
if (!tapImage("images/entry.png", 10, null, null)) {
    toastLog("未进入目标界面，任务中止");
    exit();
}
sleep(1000);

try {
    // 2) 核心操作（TODO：替换为真实步骤）
    tapImage("images/sign_button.png", 8, 540, 1200);
    sleep(1500);

    // 3) 结果校验：出现成功标记图即视为完成
    var ok = !canImage || waitImage("images/signed.png", 3, 0.5, { threshold: 0.9 });
    log(ok ? "任务完成 ✓" : "未检测到成功标记，请检查素材或坐标");

    toastLog(ok ? "任务完成" : "任务结束（未确认成功）");
} catch (e) {
    toastLog("脚本异常: " + e);
    log("异常: " + e);
}
