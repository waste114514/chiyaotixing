package com.family.yaodian;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/** 常驻通知：显示当前阶段和剩余药品，一个按钮完成本阶段。 */
public final class Notifier {

    private static final String TAG = "MedApp";

    public static final String CHANNEL_ID = "meal_reminder_v1";
    public static final int NOTIFICATION_ID = 1001;
    public static final int TEST_ID = 1009;

    public static final String ACTION_CONFIRM_ALL = "com.family.yaodian.CONFIRM_ALL";
    public static final String ACTION_DISMISSED = "com.family.yaodian.DISMISSED";
    public static final String EXTRA_STAGE_KEY = "stage_key";

    private Notifier() {
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        // 清掉 M0 测试渠道，避免设置页里堆一排旧渠道。
        manager.deleteNotificationChannel("meal_ongoing_v2");
        manager.deleteNotificationChannel("meal_ongoing_loud");

        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (channel == null) {
            channel = new NotificationChannel(
                    CHANNEL_ID,
                    "药点（常驻）",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("按餐次推进的常驻吃药提醒");
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(true);
            manager.createNotificationChannel(channel);
        } else if (channel.getName() != null
                && !"药点（常驻）".contentEquals(channel.getName())) {
            // 应用改名后，更新已存在渠道的显示名（重要性等由用户控制，不改）。
            channel.setName("药点（常驻）");
            manager.createNotificationChannel(channel);
        }
    }

    public static void post(Context context) {
        ensureChannel(context);
        JSONObject today = MealEngine.getToday(context);
        JSONObject current = today.optJSONObject("current");
        if (!today.optBoolean("hasMeds")
                || today.optBoolean("finished")
                || current == null) {
            cancel(context);
            return;
        }
        if (!notificationsEnabled(context)) {
            Log.w(TAG, "notifications disabled, skip post");
            return;
        }

        String stageKey = current.optString("key");
        String timing = current.optString("timing", MealData.BEFORE);
        boolean before = MealData.BEFORE.equals(timing);
        String prefix = before ? "吃饭前吃" : "吃完饭后吃";

        JSONArray medicines = current.optJSONArray("medicines");
        StringBuilder inline = new StringBuilder();
        StringBuilder big = new StringBuilder();
        big.append(prefix).append("：");
        int remaining = 0;
        if (medicines != null) {
            for (int i = 0; i < medicines.length(); i++) {
                JSONObject med = medicines.optJSONObject(i);
                if (med == null || med.optBoolean("confirmed")) continue;
                if (remaining > 0) {
                    inline.append("、");
                }
                inline.append(med.optString("name")).append(" ").append(med.optString("amount"));
                big.append("\n• ").append(med.optString("name"))
                        .append(" ").append(med.optString("amount"));
                remaining++;
            }
        }
        if (remaining == 0) {
            cancel(context);
            return;
        }
        if (remaining < (medicines == null ? 0 : medicines.length())) {
            inline.insert(0, "（还剩 " + remaining + " 种）");
        }

        String title = current.optString("title");
        if (current.optInt("mealIndex") == 1) {
            title = "今天" + title;
        }
        String text = prefix + "：" + inline;
        String bigText = big.toString();

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                context, 101, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pill)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);

        Intent confirmIntent = new Intent(context, MedActionReceiver.class);
        confirmIntent.setAction(ACTION_CONFIRM_ALL);
        confirmIntent.putExtra(EXTRA_STAGE_KEY, stageKey);
        PendingIntent confirmPending = PendingIntent.getBroadcast(
                context, 200, confirmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        builder.addAction(0, current.optString("confirmLabel", "已吃"), confirmPending);

        Intent deleteIntent = new Intent(context, MedActionReceiver.class);
        deleteIntent.setAction(ACTION_DISMISSED);
        deleteIntent.putExtra(EXTRA_STAGE_KEY, stageKey);
        PendingIntent deletePending = PendingIntent.getBroadcast(
                context, 201, deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        builder.setDeleteIntent(deletePending);

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
            Log.i(TAG, "posted notification stage=" + stageKey + " remaining=" + remaining);
        } catch (Exception error) {
            Log.w(TAG, "notify failed", error);
        }
    }

    public static boolean postTest(Context context) {
        ensureChannel(context);
        if (!notificationsEnabled(context)) return false;

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                context, 102, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pill)
                .setContentTitle("测试通知")
                .setContentText("能看到这条通知，说明通知权限正常。")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openPending)
                .setOngoing(false)
                .setAutoCancel(true)
                .setShowWhen(true);
        try {
            NotificationManagerCompat.from(context).notify(TEST_ID, builder.build());
            return true;
        } catch (Exception error) {
            Log.w(TAG, "test notify failed", error);
            return false;
        }
    }

    public static void cancel(Context context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {
        }
    }

    public static void ensurePosted(Context context) {
        if (!notificationsEnabled(context)) return;
        try {
            post(context);
        } catch (Exception error) {
            Log.w(TAG, "ensurePosted failed", error);
        }
    }

    public static boolean notificationsEnabled(Context context) {
        try {
            return NotificationManagerCompat.from(context).areNotificationsEnabled();
        } catch (Exception error) {
            return false;
        }
    }

    public static int getChannelImportance(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return -1;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return -1;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel == null ? -1 : channel.getImportance();
    }
}
