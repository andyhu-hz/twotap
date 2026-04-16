# TwoTap

在 Android 上用**音量加 + 音量减**组合键，通过无障碍服务在屏幕上**模拟双指触碰 → 左指抬起、右指保持按住**，再按一次组合键结束（或在对齐模式下用多一次组合键松手）。适用于需要「双指起手式 + 单指长按」的游戏或界面操作。

**包名**：`com.example.twotap`  
**最低系统**：Android 8.0（API 26）

---

## 安装

1. 在仓库的 **Releases** 页面下载 **`twotap.apk`**（或自行构建得到的同名文件）。
2. 在系统设置中允许「安装未知来源应用」（若系统提示）。
3. 安装完成后打开一次 **TwoTap** 应用图标。

---

## 开启无障碍（必须）

1. 打开应用后会提示前往**无障碍**设置；若服务已开启，会 Toast 提示当前触发方式。
2. 在 **已安装的应用 / 已下载的应用** 中找到 **「TwoTap 双指触摸服务」**（名称以 `res/values/strings.xml` 为准）。
3. 打开开关，按系统提示确认。
4. 建议：**关闭再打开一次**该服务，确保 `FLAG_REQUEST_FILTER_KEY_EVENTS` 等配置生效。

未开启无障碍时，组合键**不会**生效。

---

## 使用方法

### 组合键

在约 **1.2 秒**内先后按下 **音量加** 与 **音量减**（**顺序不限**），即视为一次「双键」。

- 服务会**消费**该次按键事件（`onKeyEvent` 返回 `true`），一般不会再调系统音量；若仍听到音量变化，与 ROM 实现有关。

### 第一次双键：开始

进入「按住」流程：先右指短按，再双指同按，然后尝试用系统 API **续接**右指长按（具体见仓库内 `DESIGN.md`）。

### 第二次双键：结束（逻辑仍为「按住中」时）

当内部状态为 **HOLDING** 时，再按一次双键会进入 **RELEASING**，由续接链派发短手势抬起右指，然后进入约 **250 ms** 冷却；冷却内再按双键会被忽略（不改变状态）。

### 若续接被系统取消（常见于部分 ColorOS / OPLUS 机型）

日志里会出现「续接链断裂」类说明：此时**逻辑上已回到空闲**，但屏幕上**可能仍像按着**。此时：

- **下一次**双键会被当作**仅补抬手**（对齐 / 松手），**不会**再开一轮阶段 1。
- 再下一次双键才恢复为「新开始」。

因此在本类设备上，实际节奏可能是：**开始 →（若断裂）→ 补抬手双键 → 再开始**。详见 `DESIGN.md`。

---

## 自行构建

需要 **JDK 17** 与 **Android SDK**（与 `compileSdk` 一致）。

### Release 签名（打正式包前）

1. 在项目根目录复制 **`keystore.properties.example`** 为 **`keystore.properties`**（该文件名已写入 `.gitignore`，**勿提交到 Git**）。
2. 填写 `storeFile`（`.jks` 的绝对路径）、`storePassword`、`keyAlias`、`keyPassword`。
3. 若没有 keystore，可在项目根执行（口令与别名请与 `keystore.properties` 一致；**私钥文件 `keystore/twotap.jks` 已加入 .gitignore**）：

```bash
mkdir -p keystore
keytool -genkeypair -v -storetype JKS -keystore keystore/twotap.jks -alias twotap \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的仓库口令 -keypass 你的密钥口令 \
  -dname "CN=TwoTap, OU=App, O=Twotap, L=, ST=, C=US"
```

4. 确认 `storeFile` 指向的 `.jks` 在磁盘上存在后再执行 `assembleRelease`。

若缺少 `keystore.properties`，`assembleRelease` 仍会构建，但**不会**使用上述正式签名（本机调试时可能用默认调试签名，具体以 Gradle/AGP 行为为准）。配置齐全后才会用 `signingConfigs.release` 签名。

```bash
# 在项目根目录（若已配置 gradlew）
./gradlew :app:assembleRelease
```

生成 APK 路径一般为：

- `app/build/outputs/apk/release/twotap.apk`
- `app/build/outputs/apk/debug/twotap-debug.apk`

---

## 调试日志

使用 Logcat，过滤 Tag：**`TwoTap`**。

---

## 隐私与安全说明

本应用通过 **AccessibilityService** 的 **`dispatchGesture`** 向**当前前台界面**注入触摸，不收集屏幕内容；仅用于实现上述手势。请仅在信任的环境下开启无障碍。

---

## 许可证

若仓库根目录未单独放置 `LICENSE`，发布前请自行补充许可证文件。
