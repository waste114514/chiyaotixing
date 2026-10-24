# 吃药提醒（yaodian）

面向长辈的本地吃药提醒：按「第 1 顿·饭前 → 第 1 顿·饭后 → 第 2 顿·饭前……」的顺序推进，不依赖固定饭点。
完全离线，无 `INTERNET` 权限，数据只存在手机本地。

## 特性

- **餐次状态机**：按顺序提醒；空阶段自动跳过；逐种确认、全部确认、跳过本项。
- **两种界面**：正常版与长辈版（长辈版高对比、大字、隐藏次要入口）。
- **常驻通知**：通知栏显示当前阶段和剩余药品，一键确认；被划掉自动补发。
- **锁屏卡片**：基于无障碍 `TYPE_ACCESSIBILITY_OVERLAY`，锁屏可直接确认（不读取屏幕内容）。
- **撤销**：10 分钟内可撤销最近一次确认。
- **记录**：按天结算（已完成 / 已跳过 / 没吃完 / 未确认），保留 14 天以上。
- **备份**：导出 / 恢复到本地 JSON 文件（系统文件选择器，不申请存储权限）。
- **药品照片**：可选，本地保存，作为辅助识别；不替代药名文字。
- **权限克制**：只有通知、开机自启、震动；无网络、无相机、无存储权限。

## 隐私

见 [PRIVACY.md](PRIVACY.md)。一句话：不联网、不上传、不收集。

备份文件是明文 JSON（含药名和服药记录），请自行妥善保管。

## 安装

- 系统要求：Android 8.0（API 26）及以上。
- 从 Releases 下载 APK，安装时允许「安装未知应用」。
- 建议核对 APK 的 SHA-256 与发布说明是否一致。

## 自行构建

前置：

- JDK 21
- Android SDK（compileSdk 36、对应 build-tools、platform-tools）
- Node.js 20+（本项目使用 Capacitor 8）

```bash
npm install
npx cap sync android

# 调试包
cd android
./gradlew assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

正式包需要签名配置：复制 `keystore/keystore.properties.example` 为 `keystore/keystore.properties`，
填写自己的 keystore 路径与密码，然后：

```bash
cd android
./gradlew assembleRelease
```

**keystore 与 `keystore.properties` 已加入 .gitignore，请勿提交或公开。**

## 目录结构

```
web/           前端（原生 HTML/CSS/JS，无框架）
dev/           电脑浏览器预览用的 mock 页面（不打包）
android/       Capacitor Android 工程与原生模块
  app/src/main/java/com/family/yaodian/
    MealEngine.java            状态机（确认/撤销/日切/药品）
    MealData.java              阶段与视图数据
    Notifier.java              常驻通知
    MedActionReceiver.java     通知按钮
    LockScreenCardService.java 锁屏卡片（无障碍）
    MedPhoto.java              药品照片
    BackupManager.java         备份导出/恢复
    Store.java                 本地 JSON 存储（device-protected）
docs/          测试说明与第三方许可
keystore/      签名配置（不入库）
```

数据文件（应用私有目录 `files/med_data/`）：

- `plan.json`：餐次、药品、日界线、字号、界面模式、照片显示方式
- `state.json`：当天阶段与确认记录（含一步撤销）
- `events-YYYY-MM.jsonl`：追加事件与每日结算
- `photos/`：药品照片（压缩后的 JPEG）

## 许可

- 本项目代码：[MIT](LICENSE)
- 设置图标：[Lucide](https://lucide.dev)（ISC 许可）
- 第三方清单：[docs/第三方许可.md](docs/第三方许可.md)

## 免责声明

本应用只负责提醒和记录，不提供医疗建议，用药方案请遵医嘱。