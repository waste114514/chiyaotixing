package com.family.yaodian;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 备份导出与恢复。
 *
 * 导出为一个 JSON 文件（plan + state + 全部 events），
 * 通过系统文件选择器保存/读取，不需要存储权限，也不联网。
 */
public final class BackupManager {

    private static final String TAG = "MedApp";
    private static final String APP = "yaodian";
    private static final int FORMAT = 1;

    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_EVENTS = 100000;
    private static final int MAX_MEDICINES = 200;
    private static final int MAX_MEALS = 6;

    public static final class Backup {
        public final JSONObject plan;
        public final JSONObject state;
        public final JSONArray events;
        public final JSONObject summary;

        Backup(JSONObject plan, JSONObject state, JSONArray events, JSONObject summary) {
            this.plan = plan;
            this.state = state;
            this.events = events;
            this.summary = summary;
        }
    }

    private BackupManager() {
    }

    /** 组装当前数据的完整备份。 */
    public static JSONObject build(Context context) throws Exception {
        JSONObject out = new JSONObject();
        out.put("app", APP);
        out.put("format", FORMAT);
        out.put("exportedAt", MealData.nowIso());
        out.put("versionName", versionName(context));

        JSONObject plan = Store.readPlan(context);
        if (plan == null) plan = Store.defaultPlan();
        JSONObject state = Store.readState(context);
        if (state == null) {
            state = MealData.newState(
                    MealData.logicalDate(plan), MealData.buildStages(plan));
        }
        out.put("plan", plan);
        out.put("state", state);
        out.put("events", Store.readAllEvents(context));
        return out;
    }

    public static void write(Context context, Uri uri, JSONObject backup) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        OutputStream stream = null;
        try {
            stream = resolver.openOutputStream(uri, "wt");
        } catch (Exception ignored) {
            // 部分提供方不支持 "wt"，退回 "w"。
        }
        if (stream == null) stream = resolver.openOutputStream(uri, "w");
        if (stream == null) throw new Exception("无法写入所选位置");
        try {
            stream.write(backup.toString().getBytes(StandardCharsets.UTF_8));
            stream.flush();
        } finally {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static String read(Context context, Uri uri) throws Exception {
        InputStream stream = context.getContentResolver().openInputStream(uri);
        if (stream == null) throw new Exception("无法读取所选文件");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        try {
            int n;
            while ((n = stream.read(buffer)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new Exception("备份文件太大，超过 20 MB");
                out.write(buffer, 0, n);
            }
        } finally {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 解析并清洗备份内容；结构不对时抛出带说明的异常。 */
    public static Backup parse(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) throw new Exception("备份文件是空的");
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (Exception error) {
            throw new Exception("这不是吃药提醒的备份文件");
        }
        if (!APP.equals(root.optString("app")) || root.optInt("format", 0) != FORMAT) {
            throw new Exception("备份格式不支持，请用本 App 导出的文件");
        }
        JSONObject rawPlan = root.optJSONObject("plan");
        if (rawPlan == null) throw new Exception("备份里没有药品数据");
        JSONObject plan = sanitizePlan(rawPlan);
        JSONObject state = sanitizeState(root.optJSONObject("state"), plan);
        JSONArray events = sanitizeEvents(root.optJSONArray("events"));

        JSONObject summary = new JSONObject();
        summary.put("exportedAt", root.optString("exportedAt", ""));
        summary.put("versionName", root.optString("versionName", ""));
        summary.put("mealCount", plan.optJSONArray("meals").length());
        summary.put("medicineCount", plan.optJSONArray("medicines").length());
        summary.put("eventCount", events.length());
        summary.put("dayCount", countDayEnds(events));
        summary.put("stateDate", state.optString("date", ""));
        return new Backup(plan, state, events, summary);
    }

    /** 覆盖本机数据并刷新通知/锁屏卡片。 */
    public static void apply(Context context, Backup backup) throws Exception {
        Store.writePlan(context, backup.plan);
        Store.writeState(context, backup.state);
        Store.replaceEvents(context, backup.events);
        // 备份不含照片文件，恢复后清掉旧照片，避免留下孤儿文件。
        MedPhoto.deleteAll(context);
        MealEngine.ensureDay(context);
        Notifier.ensurePosted(context);
        LockScreenCardService.requestRefresh();
        Log.i(TAG, "backup applied, medicines="
                + backup.plan.optJSONArray("medicines").length()
                + " events=" + backup.events.length());
    }

    private static JSONObject sanitizePlan(JSONObject raw) throws Exception {
        JSONArray rawMeals = raw.optJSONArray("meals");
        if (rawMeals == null || rawMeals.length() == 0) throw new Exception("备份里没有餐次设置");
        if (rawMeals.length() > MAX_MEALS) throw new Exception("备份里的餐次太多");

        JSONArray meals = new JSONArray();
        Set<String> knownMealIds = new HashSet<>();
        for (int i = 0; i < rawMeals.length(); i++) {
            JSONObject rawMeal = rawMeals.optJSONObject(i);
            if (rawMeal == null) throw new Exception("备份里的餐次数据不完整");
            String id = clean(rawMeal.optString("id", ""), 64);
            if (id.isEmpty()) id = "meal-" + (i + 1);
            if (!knownMealIds.add(id)) throw new Exception("备份里的餐次有重复");
            String label = clean(rawMeal.optString("label", ""), 20);
            if (label.isEmpty()) label = "第 " + (i + 1) + " 顿";
            JSONObject meal = new JSONObject();
            meal.put("id", id);
            meal.put("label", label);
            meals.put(meal);
        }

        JSONArray rawMeds = raw.optJSONArray("medicines");
        if (rawMeds == null) rawMeds = new JSONArray();
        if (rawMeds.length() > MAX_MEDICINES) throw new Exception("备份里的药品太多");

        JSONArray medicines = new JSONArray();
        Set<String> knownMedIds = new HashSet<>();
        for (int i = 0; i < rawMeds.length(); i++) {
            JSONObject rawMed = rawMeds.optJSONObject(i);
            if (rawMed == null) continue;
            String name = clean(rawMed.optString("name", ""), 30);
            String amount = clean(rawMed.optString("amount", ""), 20);
            if (name.isEmpty() || amount.isEmpty()) {
                throw new Exception("备份里有药品缺少药名或用量");
            }
            String id = clean(rawMed.optString("id", ""), 64);
            if (id.isEmpty()) id = "med-import-" + (i + 1);
            while (!knownMedIds.add(id)) id = id + "-1";

            JSONArray mealIds = new JSONArray();
            Set<String> seenMealIds = new HashSet<>();
            JSONArray rawMealIds = rawMed.optJSONArray("mealIds");
            if (rawMealIds != null) {
                for (int j = 0; j < rawMealIds.length(); j++) {
                    String mealId = clean(rawMealIds.optString(j, ""), 64);
                    if (knownMealIds.contains(mealId) && seenMealIds.add(mealId)) {
                        mealIds.put(mealId);
                    }
                }
            } else {
                String mealId = clean(rawMed.optString("mealId", ""), 64);
                if (knownMealIds.contains(mealId) && seenMealIds.add(mealId)) {
                    mealIds.put(mealId);
                }
            }
            if (mealIds.length() == 0) {
                throw new Exception("「" + name + "」没有对应的餐次，备份可能不完整");
            }

            String timing = MealData.AFTER.equals(rawMed.optString("timing"))
                    ? MealData.AFTER : MealData.BEFORE;
            JSONObject med = new JSONObject();
            med.put("id", id);
            med.put("name", name);
            med.put("amount", amount);
            med.put("mealId", mealIds.optString(0));
            med.put("mealIds", mealIds);
            med.put("timing", timing);
            med.put("enabled", rawMed.optBoolean("enabled", true));
            med.put("frequency", "daily");
            String createdAt = clean(rawMed.optString("createdAt", ""), 40);
            med.put("createdAt", createdAt.isEmpty() ? MealData.nowIso() : createdAt);
            medicines.put(med);
        }

        String boundary = raw.optString("dayBoundary", "04:00");
        if (!boundary.matches("\\d{1,2}:\\d{2}")) boundary = "04:00";
        double scale = raw.optDouble("fontScale", 1.0);
        scale = Math.max(1.0, Math.min(1.6, scale));
        String uiMode = "care".equals(raw.optString("uiMode")) ? "care" : "normal";
        String photoMode;
        String rawPhotoMode = raw.optString("photoMode", "off");
        if ("list".equals(rawPhotoMode) || "home".equals(rawPhotoMode)) photoMode = rawPhotoMode;
        else photoMode = "off";

        JSONObject plan = new JSONObject();
        plan.put("schema", 1);
        plan.put("dayBoundary", boundary);
        plan.put("fontScale", scale);
        plan.put("uiMode", uiMode);
        plan.put("photoMode", photoMode);
        plan.put("meals", meals);
        plan.put("medicines", medicines);
        return plan;
    }

    private static JSONObject sanitizeState(JSONObject raw, JSONObject plan) throws Exception {
        List<MealData.Stage> stages = MealData.buildStages(plan);
        String today = MealData.logicalDate(plan);

        JSONObject state = new JSONObject();
        state.put("schema", 1);
        String date = raw == null ? "" : raw.optString("date", "");
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}") || date.compareTo(today) > 0) {
            date = today;
        }
        state.put("date", date);

        Set<String> validKeys = new HashSet<>();
        for (MealData.Stage stage : stages) validKeys.add(stage.key);

        JSONObject resultsOut = new JSONObject();
        JSONObject rawResults = raw == null ? null : raw.optJSONObject("results");
        if (rawResults != null) {
            Iterator<String> keys = rawResults.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!validKeys.contains(key)) continue;
                JSONObject result = rawResults.optJSONObject(key);
                if (result == null) continue;
                JSONObject item = new JSONObject();
                String status = result.optString("status", "done");
                if (!"done".equals(status) && !"skipped".equals(status)) status = "done";
                item.put("status", status);
                item.put("at", clean(result.optString("at", ""), 40));
                JSONArray medsOut = new JSONArray();
                JSONArray meds = result.optJSONArray("medicines");
                if (meds != null) {
                    for (int i = 0; i < meds.length() && i < 100; i++) {
                        JSONObject med = meds.optJSONObject(i);
                        if (med == null) continue;
                        JSONObject item2 = new JSONObject();
                        item2.put("id", clean(med.optString("id", ""), 64));
                        item2.put("name", clean(med.optString("name", ""), 30));
                        item2.put("amount", clean(med.optString("amount", ""), 20));
                        item2.put("confirmed", med.optBoolean("confirmed", false));
                        medsOut.put(item2);
                    }
                }
                item.put("medicines", medsOut);
                resultsOut.put(key, item);
            }
        }

        JSONObject confirmedOut = new JSONObject();
        JSONObject rawConfirmed = raw == null ? null : raw.optJSONObject("confirmed");
        if (rawConfirmed != null) {
            Iterator<String> keys = rawConfirmed.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!validKeys.contains(key)) continue;
                JSONArray ids = rawConfirmed.optJSONArray(key);
                if (ids == null) continue;
                JSONArray idsOut = new JSONArray();
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < ids.length() && i < 100; i++) {
                    String id = clean(ids.optString(i, ""), 64);
                    if (!id.isEmpty() && seen.add(id)) idsOut.put(id);
                }
                confirmedOut.put(key, idsOut);
            }
        }

        // 已确认完但还没写结果的阶段，交给引擎在下次读取时补 done；
        // 当前阶段从第一个没有结果的阶段重新算起，忽略备份里的 stageKey。
        MealData.Stage next = MealData.firstUnresolved(stages, resultsOut);
        state.put("stageKey", next == null ? "" : next.key);
        state.put("finished", next == null);
        state.put("results", resultsOut);
        state.put("confirmed", confirmedOut);
        state.put("dismissStageKey", "");
        state.put("dismissCount", 0);
        state.put("updatedAt", MealData.nowIso());
        return state;
    }

    private static JSONArray sanitizeEvents(JSONArray raw) {
        JSONArray events = new JSONArray();
        if (raw == null) return events;
        for (int i = 0; i < raw.length() && events.length() < MAX_EVENTS; i++) {
            JSONObject event = raw.optJSONObject(i);
            if (event == null) continue;
            String type = clean(event.optString("type", ""), 40);
            if (type.isEmpty()) continue;
            events.put(event);
        }
        return events;
    }

    private static int countDayEnds(JSONArray events) {
        int count = 0;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event != null && "day_end".equals(event.optString("type"))) count++;
        }
        return count;
    }

    private static String versionName(Context context) {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (Exception error) {
            return "";
        }
    }

    private static String clean(String value, int maxLength) {
        if (value == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length() && builder.length() < maxLength; i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 && ch != '\t') continue;
            builder.append(ch);
        }
        return builder.toString().trim();
    }
}