package com.family.yaodian;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class UserPresentReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
            Log.i("MedApp", "UserPresentReceiver");
            MealEngine.ensureDay(context);
            Notifier.ensurePosted(context);
        }
    }
}
