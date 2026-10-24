package com.family.yaodian;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "MedApp";
    private static final int REQUEST_NOTIFICATIONS = 200;
    private static final int REQUEST_EXPORT = 300;
    private static final int REQUEST_IMPORT = 301;
    private static final int REQUEST_PHOTO = 302;

    private boolean stateReceiverRegistered = false;
    private BackupManager.Backup pendingImport;
    private String pendingPhotoMedId;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshWebView();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity onCreate");
        MealEngine.ensureDay(this);
        Notifier.ensureChannel(this);
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().addJavascriptInterface(new MedBridge(), "MedApp");
        }
        requestNotificationPermissionIfNeeded();
        Notifier.ensurePosted(this);
        registerStateReceiver();
        registerBackCallback();
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "MainActivity onResume");
        MealEngine.getToday(this);
        Notifier.ensurePosted(this);
        LockScreenCardService.requestRefresh();
        refreshWebView();
    }

    @Override
    public void onDestroy() {
        if (stateReceiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Exception ignored) {
            }
            stateReceiverRegistered = false;
        }
        super.onDestroy();
    }

    private void registerStateReceiver() {
        if (stateReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(MealEngine.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        stateReceiverRegistered = true;
    }

    private long lastBackAt = 0L;

    private void registerBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPressed();
            }
        });
    }

    private void handleBackPressed() {
        try {
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().evaluateJavascript(
                        "window.MedAppHandleBack ? window.MedAppHandleBack() : false",
                        value -> {
                            if ("true".equals(value)) return;
                            onBackNotHandled();
                        });
                return;
            }
        } catch (Exception error) {
            Log.w(TAG, "back to webview failed", error);
        }
        onBackNotHandled();
    }

    private void onBackNotHandled() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBackAt < 2000L) {
            finish();
            return;
        }
        lastBackAt = now;
        boolean shown = false;
        try {
            if (bridge != null && bridge.getWebView() != null) {
                callJs("window.MedAppToast && window.MedAppToast('再按一次退出')");
                shown = true;
            }
        } catch (Exception ignored) {
        }
        if (!shown) {
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshWebView() {
        try {
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().evaluateJavascript(
                        "window.MedAppRefresh && window.MedAppRefresh()", null);
            }
        } catch (Exception error) {
            Log.w(TAG, "refresh webview failed", error);
        }
    }

    private void callJs(String script) {
        try {
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().evaluateJavascript(script, null);
            }
        } catch (Exception error) {
            Log.w(TAG, "call js failed", error);
        }
    }

    private void notifyBackupResult(boolean success, String text) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", success);
            out.put("message", text == null ? "" : text);
        } catch (Exception ignored) {
        }
        final String script = "window.MedAppBackupResult && window.MedAppBackupResult("
                + out.toString() + ")";
        runOnUiThread(() -> callJs(script));
    }

    private void launchExportPicker() {
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "吃药提醒备份-"
                        + new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date())
                        + ".json");
                startActivityForResult(intent, REQUEST_EXPORT);
            } catch (Exception error) {
                Log.w(TAG, "open export picker failed", error);
                notifyBackupResult(false, "无法打开保存位置");
            }
        });
    }

    private void launchImportPicker() {
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                startActivityForResult(intent, REQUEST_IMPORT);
            } catch (Exception error) {
                Log.w(TAG, "open import picker failed", error);
                notifyBackupResult(false, "无法打开文件选择器");
            }
        });
    }

    private void launchPhotoPicker() {
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(intent, REQUEST_PHOTO);
            } catch (Exception error) {
                Log.w(TAG, "open photo picker failed", error);
                notifyPhotoResult(pendingPhotoMedId, false, "无法打开照片选择器");
                pendingPhotoMedId = null;
            }
        });
    }

    private void notifyPhotoResult(String medId, boolean success, String text) {
        JSONObject out = new JSONObject();
        try {
            out.put("medId", medId == null ? "" : medId);
            out.put("ok", success);
            out.put("message", text == null ? "" : text);
        } catch (Exception ignored) {
        }
        final String script = "window.MedAppPhotoResult && window.MedAppPhotoResult("
                + out.toString() + ")";
        runOnUiThread(() -> callJs(script));
    }

    private JSONObject findMedicine(JSONObject plan, String id) {
        if (plan == null || id == null) return null;
        JSONArray medicines = plan.optJSONArray("medicines");
        if (medicines == null) return null;
        for (int i = 0; i < medicines.length(); i++) {
            JSONObject med = medicines.optJSONObject(i);
            if (med != null && id.equals(med.optString("id"))) return med;
        }
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EXPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            final Uri uri = data.getData();
            new Thread(() -> {
                try {
                    JSONObject backup = BackupManager.build(MainActivity.this);
                    BackupManager.write(MainActivity.this, uri, backup);
                    Log.i(TAG, "backup exported");
                    notifyBackupResult(true, "备份已保存");
                } catch (Exception error) {
                    Log.w(TAG, "export failed", error);
                    notifyBackupResult(false, messageOf(error, "导出失败，请重试"));
                }
            }).start();
        } else if (requestCode == REQUEST_IMPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            final Uri uri = data.getData();
            new Thread(() -> {
                try {
                    String text = BackupManager.read(MainActivity.this, uri);
                    BackupManager.Backup backup = BackupManager.parse(text);
                    pendingImport = backup;
                    callJs("window.MedAppImportPreview && window.MedAppImportPreview("
                            + JSONObject.quote(backup.summary.toString()) + ")");
                } catch (Exception error) {
                    Log.w(TAG, "import parse failed", error);
                    notifyBackupResult(false, messageOf(error, "读取备份失败"));
                }
            }).start();
        } else if (requestCode == REQUEST_PHOTO) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                pendingPhotoMedId = null;
                return;
            }
            final Uri uri = data.getData();
            final String medId = pendingPhotoMedId;
            pendingPhotoMedId = null;
            if (medId == null || medId.isEmpty()) return;
            new Thread(() -> {
                try {
                    String name = MedPhoto.save(MainActivity.this, medId, uri);
                    JSONObject plan = Store.readPlan(MainActivity.this);
                    if (plan == null) plan = Store.defaultPlan();
                    JSONObject med = findMedicine(plan, medId);
                    if (med == null) throw new Exception("这条药已经不存在了");
                    String old = med.optString("photo", "");
                    med.put("photo", name);
                    Store.writePlan(MainActivity.this, plan);
                    if (!old.isEmpty() && !old.equals(name)) {
                        MedPhoto.delete(MainActivity.this, old);
                    }
                    Log.i(TAG, "photo saved for " + medId);
                    notifyPhotoResult(medId, true, "照片已保存");
                } catch (Exception error) {
                    Log.w(TAG, "save photo failed", error);
                    notifyPhotoResult(medId, false, messageOf(error, "照片保存失败"));
                }
            }).start();
        }
    }

    private String messageOf(Exception error, String fallback) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isEmpty() ? fallback : message;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            Notifier.post(this);
            refreshWebView();
        }
    }

    private void openSettings(Intent intent) {
        runOnUiThread(() -> {
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException error) {
                Log.w(TAG, "settings not found", error);
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (ActivityNotFoundException ignored) {
                    Log.w(TAG, "system settings not found", ignored);
                }
            }
        });
    }

    private String safe(JSONObject object) {
        return object == null ? "{}" : object.toString();
    }

    private String error(String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", message);
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    private String ok() {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    public class MedBridge {

        @JavascriptInterface
        public String getToday() {
            return safe(MealEngine.getToday(MainActivity.this));
        }

        @JavascriptInterface
        public String getHistory(int days) {
            return safe(MealEngine.getHistory(MainActivity.this, days));
        }

        @JavascriptInterface
        public String getPlan() {
            return safe(MealEngine.getPlan(MainActivity.this));
        }

        @JavascriptInterface
        public String getStatus() {
            try {
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("versionName", getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName);
                out.put("notificationsEnabled",
                        Notifier.notificationsEnabled(MainActivity.this));
                out.put("channelId", Notifier.CHANNEL_ID);
                out.put("channelImportance",
                        Notifier.getChannelImportance(MainActivity.this));
                out.put("accessibilityEnabled",
                        LockScreenCardService.isEnabled(MainActivity.this));
                out.put("accessibilityConnected",
                        LockScreenCardService.isConnected());
                out.put("packageName", getPackageName());
                return out.toString();
            } catch (Exception error) {
                return error("读取设置状态失败");
            }
        }

        @JavascriptInterface
        public String confirmMed(String stageKey, String medId) {
            return safe(MealEngine.confirmMed(MainActivity.this, stageKey, medId));
        }

        @JavascriptInterface
        public String unconfirmMed(String stageKey, String medId) {
            return safe(MealEngine.unconfirmMed(MainActivity.this, stageKey, medId));
        }

        @JavascriptInterface
        public String confirmAll() {
            return safe(MealEngine.confirmAll(MainActivity.this));
        }

        @JavascriptInterface
        public String skipStage() {
            return safe(MealEngine.skipStage(MainActivity.this));
        }

        @JavascriptInterface
        public String resetToday() {
            return safe(MealEngine.resetToday(MainActivity.this));
        }

        @JavascriptInterface
        public String undoLast() {
            return safe(MealEngine.undoLast(MainActivity.this));
        }

        @JavascriptInterface
        public String saveMedicine(String json) {
            return safe(MealEngine.saveMedicine(MainActivity.this, json));
        }

        @JavascriptInterface
        public String deleteMedicine(String id) {
            return safe(MealEngine.deleteMedicine(MainActivity.this, id));
        }

        @JavascriptInterface
        public String setMealCount(int count) {
            return safe(MealEngine.setMealCount(MainActivity.this, count));
        }

        @JavascriptInterface
        public String setFontScale(String scale) {
            return safe(MealEngine.setFontScale(MainActivity.this, scale));
        }

        @JavascriptInterface
        public String setUiMode(String mode) {
            return safe(MealEngine.setUiMode(MainActivity.this, mode));
        }

        @JavascriptInterface
        public String setPhotoMode(String mode) {
            return safe(MealEngine.setPhotoMode(MainActivity.this, mode));
        }

        @JavascriptInterface
        public String pickPhoto(String medId) {
            if (medId == null || medId.isEmpty()) return error("先保存这条药，再添加照片");
            pendingPhotoMedId = medId;
            launchPhotoPicker();
            return ok();
        }

        @JavascriptInterface
        public String getPhoto(String medId) {
            try {
                if (medId == null || medId.isEmpty()) return "";
                JSONObject plan = Store.readPlan(MainActivity.this);
                if (plan == null) return "";
                JSONObject med = findMedicine(plan, medId);
                if (med == null) return "";
                return MedPhoto.readDataUrl(MainActivity.this, med.optString("photo", ""));
            } catch (Exception error) {
                Log.w(TAG, "get photo failed", error);
                return "";
            }
        }

        @JavascriptInterface
        public String removePhoto(String medId) {
            try {
                if (medId == null || medId.isEmpty()) return error("没有找到这条药");
                JSONObject plan = Store.readPlan(MainActivity.this);
                if (plan == null) return error("读取药品失败");
                JSONObject med = findMedicine(plan, medId);
                if (med == null) return error("这条药已经不存在了");
                String old = med.optString("photo", "");
                med.put("photo", "");
                Store.writePlan(MainActivity.this, plan);
                MedPhoto.delete(MainActivity.this, old);
                return ok();
            } catch (Exception error) {
                Log.w(TAG, "remove photo failed", error);
                return error("删除照片失败");
            }
        }

        @JavascriptInterface
        public String exportBackup() {
            launchExportPicker();
            return ok();
        }

        @JavascriptInterface
        public String importBackup() {
            launchImportPicker();
            return ok();
        }

        @JavascriptInterface
        public String applyImport() {
            BackupManager.Backup backup = pendingImport;
            if (backup == null) return error("没有待恢复的备份，请重新选择文件");
            pendingImport = null;
            try {
                BackupManager.apply(MainActivity.this, backup);
                runOnUiThread(MainActivity.this::refreshWebView);
                return ok();
            } catch (Exception error) {
                Log.w(TAG, "apply import failed", error);
                return error(messageOf(error, "恢复失败，请重试"));
            }
        }

        @JavascriptInterface
        public void cancelImport() {
            pendingImport = null;
        }

        @JavascriptInterface
        public String sendTestNotification() {
            if (!Notifier.notificationsEnabled(MainActivity.this)) {
                return error("通知权限未开启，请先在设置里允许通知");
            }
            if (Notifier.postTest(MainActivity.this)) return ok();
            return error("测试通知发送失败");
        }

        @JavascriptInterface
        public String previewLockCard() {
            if (!LockScreenCardService.isEnabled(MainActivity.this)) {
                return error("请先开启「锁屏吃药卡片」无障碍服务");
            }
            if (!LockScreenCardService.isConnected()) {
                return error("锁屏卡片服务未连接，请按 Home 回桌面再打开 App");
            }
            LockScreenCardService.preview(MainActivity.this);
            return ok();
        }

        @JavascriptInterface
        public void openLockScreenCard() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(MainActivity.this, LockScreenActivity.class));
                } catch (Exception error) {
                    Log.w(TAG, "open lock screen card failed", error);
                }
            });
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }

        @JavascriptInterface
        public void openAppSettings() {
            openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName())));
        }
    }
}
