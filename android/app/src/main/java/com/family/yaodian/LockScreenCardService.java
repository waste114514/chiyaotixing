package com.family.yaodian;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 锁屏吃药卡片。
 *
 * 用 TYPE_ACCESSIBILITY_OVERLAY 在锁屏上画一张卡片：当前阶段、药品列表、
 * 逐种确认和「全部已吃 / 吃完后确认」。不读取屏幕内容。
 */
public class LockScreenCardService extends AccessibilityService {

    private static final String TAG = "MedApp";
    private static final int GREEN = Color.parseColor("#1F7A4D");
    private static final int GREEN_DARK = Color.parseColor("#145C39");
    private static final int INK = Color.parseColor("#1A1A1A");
    private static final int MUTED = Color.parseColor("#5F5A50");
    private static final int LINE = Color.parseColor("#E7DFD0");
    private static final int CONFIRMED_BG = Color.parseColor("#E8F5EC");
    private static final int ROW_BG = Color.parseColor("#F7F3EA");

    private static volatile boolean connected = false;
    private static volatile boolean paused = false;
    private static volatile LockScreenCardService instance;

    private WindowManager windowManager;
    private LinearLayout cardView;
    private TextView progressView;
    private TextView titleView;
    private TextView subtitleView;
    private LinearLayout medsContainer;
    private ScrollView medsScroll;
    private Button actionButton;
    private TextView footerView;
    private BroadcastReceiver screenReceiver;

    private long previewUntil = 0;
    private long holdRefreshUntil = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = this::refreshCard;
    private final MealEngine.Listener stateListener = () -> handler.post(refreshRunnable);

    public static boolean isConnected() {
        return connected;
    }

    public static void setPaused(boolean value) {
        paused = value;
    }

    public static void requestRefresh() {
        LockScreenCardService service = instance;
        if (service != null) {
            service.handler.post(service::refreshCard);
        }
    }

    /** 解锁状态下临时预览 8 秒，方便家属确认卡片长什么样。 */
    public static void preview(Context context) {
        LockScreenCardService service = instance;
        if (service == null) return;
        service.previewUntil = SystemClock.uptimeMillis() + 8000;
        service.handler.post(service::refreshCard);
        service.handler.postDelayed(() -> {
            if (SystemClock.uptimeMillis() >= service.previewUntil) {
                service.previewUntil = 0;
                service.refreshCard();
            }
        }, 8200);
    }

    public static boolean isEnabled(Context context) {
        try {
            AccessibilityManager manager = (AccessibilityManager) context.getSystemService(
                    Context.ACCESSIBILITY_SERVICE
            );
            if (manager == null) return false;
            for (AccessibilityServiceInfo info
                    : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                ResolveInfo resolveInfo = info.getResolveInfo();
                if (resolveInfo == null || resolveInfo.serviceInfo == null) continue;
                ServiceInfo serviceInfo = resolveInfo.serviceInfo;
                if (!context.getPackageName().equals(serviceInfo.packageName)) continue;
                String className = serviceInfo.name;
                if (className == null) continue;
                if (className.startsWith(".")) {
                    className = serviceInfo.packageName + className;
                }
                if (LockScreenCardService.class.getName().equals(className)) {
                    return true;
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "check accessibility service failed", error);
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        instance = this;
        Log.i(TAG, "LockScreenCardService connected");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        MealEngine.addListener(stateListener);

        if (screenReceiver == null) {
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        hideCard();
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)
                            || Intent.ACTION_USER_PRESENT.equals(action)) {
                        handler.removeCallbacks(refreshRunnable);
                        handler.postDelayed(refreshRunnable, 500);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(screenReceiver, filter);
            }
        }
        refreshCard();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.removeCallbacks(refreshRunnable);
            handler.postDelayed(refreshRunnable, 500);
        }
    }

    @Override
    public void onInterrupt() {
        hideCard();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "LockScreenCardService destroyed");
        connected = false;
        instance = null;
        handler.removeCallbacksAndMessages(null);
        MealEngine.removeListener(stateListener);
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
            screenReceiver = null;
        }
        hideCard();
        super.onDestroy();
    }

    private void refreshCard() {
        if (paused) {
            hideCard();
            return;
        }
        if (SystemClock.uptimeMillis() < holdRefreshUntil) {
            handler.removeCallbacks(refreshRunnable);
            handler.postDelayed(refreshRunnable, 300);
            return;
        }

        JSONObject today = MealEngine.getToday(this);
        JSONObject current = today.optJSONObject("current");
        boolean active = today.optBoolean("hasMeds")
                && !today.optBoolean("finished")
                && current != null;
        if (!active) {
            hideCard();
            return;
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null || !powerManager.isInteractive()) {
            hideCard();
            return;
        }
        boolean preview = SystemClock.uptimeMillis() < previewUntil;
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        if (!preview && !locked) {
            hideCard();
            return;
        }

        if (cardView == null) {
            showCard();
        }
        updateCard(today, current);
    }

    private void showCard() {
        if (windowManager == null) return;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(18), dp(22), dp(18));
        card.setElevation(dp(12));
        card.setBackground(rounded(Color.WHITE, LINE, 24));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        progressView = new TextView(this);
        progressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        progressView.setTextColor(MUTED);
        topRow.addView(progressView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView openApp = new TextView(this);
        openApp.setText("打开 App ›");
        openApp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        openApp.setTextColor(GREEN_DARK);
        openApp.setTypeface(null, Typeface.BOLD);
        openApp.setPadding(dp(10), dp(8), 0, dp(8));
        openApp.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                hideCard();
            } catch (Exception error) {
                Log.w(TAG, "open app from card failed", error);
            }
        });
        topRow.addView(openApp);
        card.addView(topRow);

        titleView = new TextView(this);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        titleView.setTextColor(GREEN_DARK);
        titleView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(4);
        card.addView(titleView, titleParams);

        subtitleView = new TextView(this);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        subtitleView.setTextColor(INK);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(2);
        card.addView(subtitleView, subtitleParams);

        medsContainer = new LinearLayout(this);
        medsContainer.setOrientation(LinearLayout.VERTICAL);

        medsScroll = new ScrollView(this);
        medsScroll.setFillViewport(false);
        medsScroll.setVerticalScrollBarEnabled(false);
        medsScroll.addView(medsContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        scrollParams.topMargin = dp(10);
        card.addView(medsScroll, scrollParams);

        actionButton = new Button(this);
        actionButton.setAllCaps(false);
        actionButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        actionButton.setTextColor(Color.WHITE);
        actionButton.setTypeface(null, Typeface.BOLD);
        actionButton.setMinHeight(0);
        actionButton.setMinimumHeight(0);
        actionButton.setStateListAnimator(null);
        actionButton.setBackgroundTintList(null);
        actionButton.setBackground(rounded(GREEN, 0, 18));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(72)
        );
        actionParams.topMargin = dp(14);
        card.addView(actionButton, actionParams);

        footerView = new TextView(this);
        footerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        footerView.setTextColor(MUTED);
        footerView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        footerParams.topMargin = dp(8);
        card.addView(footerView, footerParams);

        int width = Math.min(
                getResources().getDisplayMetrics().widthPixels - dp(32),
                dp(560)
        );
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;

        try {
            windowManager.addView(card, params);
            cardView = card;
            Log.i(TAG, "lock card added");
        } catch (Exception error) {
            Log.w(TAG, "add lock card failed", error);
            cardView = null;
        }
    }

    private void updateCard(JSONObject today, JSONObject current) {
        if (cardView == null || titleView == null || medsContainer == null) return;

        int mealIndex = current.optInt("mealIndex", 1);
        int mealCount = today.optInt("mealCount", 1);
        progressView.setText("第 " + mealIndex + " 顿 · 共 " + mealCount + " 顿");
        titleView.setText(current.optString("title"));
        subtitleView.setText(current.optString("subtitle"));

        medsContainer.removeAllViews();
        JSONArray medicines = current.optJSONArray("medicines");
        int count = medicines == null ? 0 : medicines.length();
        String stageKey = current.optString("key");
        boolean before = MealData.BEFORE.equals(current.optString("timing"));

        if (medicines != null) {
            for (int i = 0; i < medicines.length(); i++) {
                JSONObject med = medicines.optJSONObject(i);
                if (med == null) continue;
                medsContainer.addView(buildMedRow(med, stageKey, i == 0));
            }
        }

        if (medsScroll != null) {
            ViewGroup.LayoutParams params = medsScroll.getLayoutParams();
            params.height = count > 3
                    ? dp(300)
                    : ViewGroup.LayoutParams.WRAP_CONTENT;
            medsScroll.setLayoutParams(params);
        }

        int remaining = current.optInt("remaining", count);
        if (remaining <= 0) {
            actionButton.setVisibility(View.GONE);
        } else {
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setText(current.optString("confirmLabel",
                    before ? "已吃" : "吃完后确认"));
            actionButton.setOnClickListener(v -> {
                actionButton.setOnClickListener(null);
                MealEngine.confirmAll(this, stageKey);
                handler.removeCallbacks(refreshRunnable);
                handler.postDelayed(refreshRunnable, 150);
            });
        }

        if (count > 1) {
            footerView.setText("点药名可以单独确认，全部确认后自动进入下一项");
        } else {
            footerView.setText(before ? "点上面的大按钮确认" : "吃完饭后点上面的大按钮");
        }
    }

    private View buildMedRow(JSONObject med, String stageKey, boolean first) {
        String medId = med.optString("id");
        String name = med.optString("name");
        String amount = med.optString("amount");
        boolean confirmed = med.optBoolean("confirmed");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setMinimumHeight(dp(70));
        row.setBackground(rounded(confirmed ? CONFIRMED_BG : ROW_BG, 0, 14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        if (!first) rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(texts, textsParams);

        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        nameView.setTextColor(INK);
        nameView.setTypeface(null, Typeface.BOLD);
        texts.addView(nameView);

        TextView amountView = new TextView(this);
        amountView.setText(amount);
        amountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        amountView.setTextColor(MUTED);
        texts.addView(amountView);

        TextView checkView = new TextView(this);
        checkView.setText(confirmed ? "✓" : "○");
        checkView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        checkView.setTextColor(confirmed ? GREEN : MUTED);
        checkView.setTypeface(null, Typeface.BOLD);
        checkView.setPadding(dp(12), 0, 0, 0);
        row.addView(checkView);

        row.setContentDescription(name + " " + amount + (confirmed ? "，已吃" : "，未吃"));
        if (!confirmed) {
            row.setOnClickListener(v -> {
                row.setOnClickListener(null);
                holdRefreshUntil = SystemClock.uptimeMillis() + 650;
                MealEngine.confirmMed(this, stageKey, medId);
                row.setBackground(rounded(CONFIRMED_BG, 0, 14));
                checkView.setText("✓");
                checkView.setTextColor(GREEN);
                row.setContentDescription(name + " " + amount + "，已吃");
                handler.removeCallbacks(refreshRunnable);
                handler.postDelayed(refreshRunnable, 700);
            });
        }
        return row;
    }

    private void hideCard() {
        if (cardView == null || windowManager == null) return;
        try {
            windowManager.removeView(cardView);
            Log.i(TAG, "lock card hidden");
        } catch (Exception ignored) {
        }
        cardView = null;
        progressView = null;
        titleView = null;
        subtitleView = null;
        medsContainer = null;
        medsScroll = null;
        actionButton = null;
        footerView = null;
    }

    private GradientDrawable rounded(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) drawable.setStroke(dp(2), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
