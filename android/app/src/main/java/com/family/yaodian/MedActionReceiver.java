package com.family.yaodian;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** 通知上的「全部已吃 / 吃完后确认」和被划掉后的重发。 */
public class MedActionReceiver extends BroadcastReceiver {

    private static final String TAG = "MedApp";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final Context app = context.getApplicationContext();
        String action = intent.getAction();
        Log.i(TAG, "MedActionReceiver action=" + action);

        if (Notifier.ACTION_CONFIRM_ALL.equals(action)) {
            String stageKey = intent.getStringExtra(Notifier.EXTRA_STAGE_KEY);
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    MealEngine.confirmAll(app, stageKey);
                } finally {
                    pending.finish();
                }
            }).start();
        } else if (Notifier.ACTION_DISMISSED.equals(action)) {
            String stageKey = intent.getStringExtra(Notifier.EXTRA_STAGE_KEY);
            final PendingResult pending = goAsync();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    int count = MealEngine.noteDismiss(app, stageKey == null ? "" : stageKey);
                    if (count >= 1 && count <= 2) {
                        Notifier.post(app);
                    }
                } finally {
                    pending.finish();
                }
            }, 2000);
        }
    }
}
