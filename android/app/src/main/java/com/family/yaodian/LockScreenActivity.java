package com.family.yaodian;

import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 备用路线：全屏大字卡片。
 *
 * 用 setShowWhenLocked 显示在锁屏上，作为无障碍卡片不可用时的兜底。
 * 从设置页手动打开；打开期间暂停锁屏卡片，避免两层重叠。
 */
public class LockScreenActivity extends AppCompatActivity {

    private TextView titleView;
    private TextView textView;
    private Button actionButton;

    private String stageKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        LockScreenCardService.setPaused(true);
        LockScreenCardService.requestRefresh();

        setContentView(R.layout.activity_lock_screen);
        titleView = findViewById(R.id.lockTitle);
        textView = findViewById(R.id.lockText);
        actionButton = findViewById(R.id.lockAction);

        findViewById(R.id.lockClose).setOnClickListener(v -> finish());
        actionButton.setOnClickListener(v -> {
            if (stageKey.isEmpty()) {
                finish();
                return;
            }
            MealEngine.confirmAll(this, stageKey);
            refresh();
        });
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        LockScreenCardService.setPaused(false);
        LockScreenCardService.requestRefresh();
        super.onDestroy();
    }

    private void refresh() {
        JSONObject today = MealEngine.getToday(this);
        JSONObject current = today.optJSONObject("current");
        if (current == null) {
            stageKey = "";
            titleView.setText(today.optBoolean("hasMeds")
                    ? "今天的药都吃完了" : "还没有添加药品");
            textView.setText(today.optBoolean("hasMeds")
                    ? "可以关闭这个页面。"
                    : "请先在 App 里添加药品。");
            actionButton.setText("关闭");
            return;
        }

        stageKey = current.optString("key");
        titleView.setText(current.optString("title"));

        StringBuilder text = new StringBuilder(current.optString("subtitle"));
        text.append("：");
        JSONArray medicines = current.optJSONArray("medicines");
        if (medicines != null) {
            for (int i = 0; i < medicines.length(); i++) {
                JSONObject med = medicines.optJSONObject(i);
                if (med == null || med.optBoolean("confirmed")) continue;
                text.append("\n• ").append(med.optString("name"))
                        .append(" ").append(med.optString("amount"));
            }
        }
        textView.setText(text.toString());
        actionButton.setText(current.optString("confirmLabel", "已吃"));
    }
}
