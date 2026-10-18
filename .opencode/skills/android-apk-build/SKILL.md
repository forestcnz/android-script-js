---
name: android-apk-build
description: 为 android-script-js 构建（打包）APK。当用户说「打包 apk / 打 apk / 构建 APK / 出个包 / assemble / 构建 debug 包 / 构建 release 包 / APK 多少大了 / 装不上」，或需要确认本机 Gradle/SDK 环境、排查 Gradle 构建失败、校验产物包名版本号签名时使用本技能。
---

# Android 打包技能（android-apk-build）

本技能指导 AI 在 `android-script-js` 工程上完成一次 **可安装的 APK 构建**，并给出产物校验结论。

## 0. 关键前提（先读，能避免大部分返工）

1. **仓库里没有 `gradlew` 脚本**。`gradle/wrapper/` 下只有 `gradle-wrapper.properties`，
   没有 `gradlew` / `gradlew.bat`，**不要直接调 `./gradlew`**。用 PATH 里的全局 `gradle`：
   ```powershell
   gradle :app:assembleDebug --console=plain
   ```
   本机为 `D:\gradle\gradle-9.6.1\bin\gradle.bat`，与 wrapper 声明的 9.6.1 一致。
2. **默认只出 debug 包**。`app/build.gradle.kts` 的 `release` **没有任何 `signingConfig`**，
   工程内也不存在 `.jks` / `.keystore`，所以直接跑 `assembleRelease` 会产出
   **未签名、无法安装** 的 APK。CI（`.github/workflows/android-sdxn-release.yml`）同样
   发布 debug 包。因此「打包」的默认目标就是 **`:app:assembleDebug`**。
3. **JDK**：本机 Java 21 可直接用；工程 `compileOptions` 锁 `VERSION_17`，CI 用 temurin 17，两者均可。

## 1. 标准流程

### 步骤 1：确认环境（首次或环境变动后）

```powershell
# SDK 路径来自 local.properties（本机为 D:\Android）
Get-Content local.properties
# 构建工具版本（需 36.x，与 compileSdk=36 对齐）
Get-ChildItem D:\Android\build-tools | Select-Object Name
# Gradle
(Get-Command gradle).Source
gradle --version
```

若 `local.properties` 缺失或 `sdk.dir` 失效，写入本机实际 SDK 路径（该文件已被 `.gitignore` 忽略，不要提交）：
```properties
sdk.dir=D\:\\Android
```

### 步骤 2：构建

构建耗时约 **1.5 分钟**（38 个 task）。建议放后台跑，避免占用前台的等待窗口：

```powershell
gradle :app:assembleDebug --console=plain 2>&1 | Tee-Object -FilePath build-assemble.log
```

加 `--console=plain` 是为了**不要 ANSI 颜色转义码**，否则日志抓取和 `Tee-Object` 结果里全是乱码。

指定版本号（`build.gradle.kts` 通过 `providers.gradleProperty` 读取，默认 `1` / `1.0.0-sdxn`）：
```powershell
gradle :app:assembleDebug -PappVersionCode=42 -PappVersionName=1.0.42-sdxn --console=plain
```

### 步骤 3：校验产物（**必做**）

产物路径：`app/build/outputs/apk/debug/app-debug.apk`（约 **36–38 MB**，主要来自 MLKit 中文 OCR 模型）。

包名 / 版本 / SDK 区间：
```powershell
& D:\Android\build-tools\36.0.0\aapt2.exe dump badging `
  app\build\outputs\apk\debug\app-debug.apk | Select-Object -First 8
```
预期 `package: name='com.sdxn.assistant'`，`minSdkVersion:'24'`，`targetSdkVersion:'35'`。

签名是否有效：
```powershell
& D:\Android\build-tools\36.0.0\apksigner.bat verify --print-certs `
  app\build\outputs\apk\debug\app-debug.apk
```
应输出 `Signer #1 certificate DN: C=US, O=Android, CN=Android Debug`，无 `DOES NOT VERIFY`。
（`apksigner` 在 JDK 21 下会打印 conscrypt 的 native-access 警告，**属正常噪音，不影响校验结论**。）

### 步骤 4：交付信息

向用户汇报时给出：**APK 绝对路径、大小、包名、versionCode/versionName、min/targetSdk、
签名类型、构建耗时、日志文件路径**。

## 2. 用户要「正式签名 release 包」时

不要自作主张生成 keystore，先确认意图；确认后再执行：

1. `keytool -genkeypair -v -keystore release.jks -storetype JKS -keyalg RSA -keysize 2048 -validity 10000 -alias <alias>`
2. 在 `app/build.gradle.kts` 加 `signingConfigs { release { ... } }`，从 `local.properties` 或
   环境变量读密钥库口令（**口令绝不硬编码入库**，且 `release.jks` 需加进 `.gitignore`）；
3. `buildTypes.release.signingConfig = signingConfigs.getByName("release")`；
4. `gradle :app:assembleRelease --console=plain`，产物在 `app/build/outputs/apk/release/`。

安装提示：换签名后与旧包签名不一致，需**先卸载**旧版本再装。

## 3. 已知告警（**无害，不要试图修**）

| 告警 | 位置 | 说明 |
|---|---|---|
| `'constructor(...)': ApkSigner.SignerConfig.Builder' is deprecated` | `core/pack/ApkPackager.kt:183` | apksig 8.7.3 的 API 弃用提示，属任务导出重签链路，不影响宿主 APK 构建 |
| `Deprecated Gradle features were used ... incompatible with Gradle 10` | 构建尾 | Gradle 9.6.1 自身特性，与本工程配置无关 |
| `Starting a Gradle Daemon, 2 busy Daemons could not be reused` | 构建头 | 并发构建残留，可用 `gradle --status` 查看 |

## 4. 排错

| 现象 | 处理 |
|---|---|
| `SDK location not found` | `local.properties` 缺 `sdk.dir` 或路径转义错误（Windows 需 `D\:\\Android`） |
| `compileSdk 36` 相关 AAR 元数据报错 | quickjs-kt 锁 **1.0.5**（1.0.8+ 用 Kotlin 2.4 编译，超出 AGP 9 内置 Kotlin 2.2 的元数据上限），**不要随意升级** |
| 导出的独立 APK 安装报 `INSTALL_FAILED_INVALID_APK` | 必须保留 `packaging { jniLibs { useLegacyPackaging = true } }` + 导出链路 4 字节对齐（见 `docs/COVERAGE.md` 4.6-b） |
| 端口/daemon 冲突 | `gradle --stop` 后重试 |
| 需彻底重来 | `gradle :app:clean --console=plain`（耗时明显变长，确认需要再执行） |

## 5. 不要做的事

- 不要 `git commit` / `push` / 打 tag，除非用户明确要求；打包是本地动作。
- 不要顺手改 `versionCode` / `versionName`，用户没指定就用默认值。
- 不要把 `build-assemble.log` 之外的临时文件写进工程根目录（该日志可留作交付凭据）。
- 不要执行 `gradle clean` 作为「修复构建失败」的常规手段，先读真实报错。