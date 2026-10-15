// 内置任务：神雕侠侣手游启动检查
// config.json 中的 target 负责拉起游戏；这里确认自动化前置能力并给出反馈。

auto.waitFor(5000);
if (!auto.service) {
    toastLog("请先开启神雕侠侣助手的无障碍服务");
    exit();
}

sleep(3000);
log("神雕侠侣手游已拉起，助手手势能力正常");
toastLog("神雕侠侣助手就绪");
