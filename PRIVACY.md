# 隐私说明

吃药提醒是一个完全本地的应用。

## 不做什么

- 没有 `INTERNET` 权限，应用无法联网。
- 不收集、不上传任何数据；没有账号、没有统计、没有广告。
- 不读取通讯录、位置、短信、相机、麦克风。
- 无障碍服务只用于绘制锁屏卡片，声明 `canRetrieveWindowContent=false`，不读取屏幕内容。

## 数据存在哪里

数据保存在本机应用私有目录（device-protected storage）：

```
files/med_data/
├─ plan.json              药品、餐次、设置
├─ state.json             当天的确认状态
├─ events-YYYY-MM.jsonl   历史事件与每日结算
└─ photos/                药品照片（可选，压缩后的 JPEG）
```

- 卸载应用或「清除数据」会删除上述全部内容。
- 系统云备份未启用（`allowBackup=false`）。

## 权限说明

| 权限 | 用途 |
|---|---|
| `POST_NOTIFICATIONS` | 显示吃药提醒通知（Android 13+ 需要用户允许） |
| `RECEIVE_BOOT_COMPLETED` | 重启后恢复提醒 |
| `VIBRATE` | 通知震动能力 |

照片通过系统文件/相册选择器获取，只复制选中图片到应用目录；应用不申请相机和存储权限。

## 备份文件

导出的备份是明文 JSON，包含药名、用量和服药记录。文件放在用户自己选择的位置，请自行保管。

## 反馈

如果发现隐私相关问题，请提 issue。

## English

Medication Reminder is fully local.

- No `INTERNET` permission — the app cannot access the network.
- No collection, no upload, no accounts, no analytics, no ads.
- No access to contacts, location, SMS, camera, or microphone.
- The accessibility service is only used to draw the lock-screen card (`canRetrieveWindowContent=false`); it never reads screen content.
- All data is stored in the app's private storage: `files/med_data/plan.json`, `state.json`, `events-YYYY-MM.jsonl`, and `photos/`.
- Uninstalling or clearing app data removes everything; Android cloud backup is disabled (`allowBackup=false`).
- Permissions: `POST_NOTIFICATIONS` (reminders), `RECEIVE_BOOT_COMPLETED` (restore reminders after reboot), `VIBRATE`.
- Photos are obtained through the system file/photo picker; the app does not request camera or storage permissions.
- Exported backups are plain JSON containing medicine names and intake records. Keep them safe.