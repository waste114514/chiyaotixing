package com.family.yaodian;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 饭点状态机。
 *
 * 一天 = 若干阶段：第1顿·饭前 → 第1顿·饭后 → 第2顿·饭前 → …
 * 空阶段不生成；同一阶段可逐种确认，全部确认后推进；也可跳过当前阶段。
 */
public final class MealEngine {

    private static final String TAG = "MedApp";
    private static final Object LOCK = new Object();
    private static final long UNDO_WINDOW_MS = 10 * 60 * 1000L;

    public static final String ACTION_STATE_CHANGED = "com.family.yaodian.STATE_CHANGED";

    public interface Listener {
        void onStateChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private MealEngine() {
    }

    public static void addListener(Listener listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    public static JSONObject getPlan(Context context) {
        synchronized (LOCK) {
            try {
                return new JSONObject(planLocked(context).toString());
            } catch (Exception error) {
                return new JSONObject();
            }
        }
    }

    public static JSONObject getToday(Context context) {
        synchronized (LOCK) {
            boolean changed = ensureDayLocked(context);
            JSONObject plan = planLocked(context);
            JSONObject state = stateLocked(context);
            List<MealData.Stage> stages = MealData.buildStages(plan);
            changed |= reconcileLocked(context, plan, state, stages);
            if (changed) commit(context, state);
            return MealData.buildToday(plan, state, stages);
        }
    }

    public static JSONObject getHistory(Context context, int days) {
        return MealData.buildHistory(context, days);
    }

    public static void ensureDay(Context context) {
        synchronized (LOCK) {
            boolean changed = ensureDayLocked(context);
            JSONObject plan = planLocked(context);
            JSONObject state = stateLocked(context);
            List<MealData.Stage> stages = MealData.buildStages(plan);
            changed |= reconcileLocked(context, plan, state, stages);
            if (changed) commit(context, state);
        }
    }

    public static JSONObject confirmMed(Context context, String stageKey, String medId) {
        synchronized (LOCK) {
            boolean changed = ensureDayLocked(context);
            JSONObject plan = planLocked(context);
            JSONObject state = stateLocked(context);
            List<MealData.Stage> stages = MealData.buildStages(plan);
            changed |= reconcileLocked(context, plan, state, stages);

            MealData.Stage stage = MealData.findStage(stages, stageKey);
            if (stage != null
                    && stageKey.equals(state.optString("stageKey"))
                    && !state.optBoolean("finished", false)) {
                JSONArray ids = confirmedArray(state, stageKey);
                JSONObject med = findMed(stage, medId);
                if (med != null && !MealData.contains(ids, medId)) {
                    captureUndo(state, stage, "confirm", medId);
                    ids.put(medId);
                    appendMedEvent(context, state, "med_confirm", stage, med);
                    if (MealData.allConfirmed(stage, ids)) {
                        markDone(context, state, stage, ids);
                        advanceLocked(context, state, stages);
                    }
                    changed = true;
                }
            }
            if (changed) commit(context, state);
            return MealData.buildToday(plan, state, stages);
        }
    }

    public static JSONObject unconfirmMed(Context context, String stageKey, String medId) {
        synchronized (LOCK) {
            boolean changed = ensureDayLocked(context);
            JSONObject plan = planLocked(context);
            JSONObject state = stateLocked(context);
            List<MealData.Stage> stages = MealData.buildStages(plan);
            changed |= reconcileLocked(context, plan, state, stages);

            MealData.Stage stage = MealData.findStage(stages, stageKey);
            if (stage != null
                    && stageKey.equals(state.optString("stageKey"))
                    && !state.optBoolean("finished", false)) {
                JSONArray ids = confirmedArray(state, stageKey);
                if (MealData.contains(ids, medId)) {
                    JSONArray next = new JSONArray();
                    for (int i = 0; i < ids.length(); i++) {
                        if (!medId.equals(ids.optString(i))) next.put(ids.optString(i));
                    }
                    try {
                        JSONObject confirmed = state.optJSONObject("confirmed");
                        if (confirmed != null) confirmed.put(stageKey, next);
                    } catch (Exception error) {
                        Log.w(TAG, "unconfirm failed", error);
                    }
                    state.remove("lastUndo");
                    JSONObject med = findMed(stage, medId);
                    if (med != null) appendMedEvent(context, state, "med_unconfirm", stage, med);
                    changed = true;
                }
            }
            if (changed) commit(context, state);
            return MealData.buildToday(plan, state, stages);
        }
    }

    public static JSONObject confirmAll(Context context) {
        return confirmAll(context, "");
    }

    /** expectedStageKey 非空时用于防止旧通知重复点击跳到下一阶段。 */
    public static JSONObject confirmAll(Context context, String expectedStageKey) {
        synchronized (LOCK) {
            boolean changed = ensureDayLocked(context);
            JSONObject plan = planLocked(context);
            JSONObject state = stateLocked(context);
            List<MealData.Stage> stages = MealData.buildStages(plan);
            changed |= reconcileLocked(context, plan, state, stages);

            String currentKey = state.optString("stageKey", "");
            MealData.Stage stage = MealData.findStage(stages, currentKey);
            boolean matches = expectedStageKey == null || expectedStageKey.isEmpty()
                    || expectedStageKey.equals(currentKey);
            if (stage != null && matches && !state.optBoolean("finished", false)) {
                captureUndo(state, stage, "confirmAll", "");
                JSONArray ids = confirmedArray(state, currentKey);
                for (int i = 0; i < stage.medicines.length(); i++) {
                    String id = stage.medicines.optJSONObject(i).optString("id");
                    if (!MealData.contains(ids, id)) ids.put(id);
                }
                markDone(context, state, stage, ids);
                advanceLocked(context, state, stages);
                changed = true;
            }
            if (changed) commit(context, state);
            return MealData.buildToday(plan, state, stages);
        }
    }

    public static JSONObject skipStage(Context context) {
        synchronized (LOCK) {
            boolean changed = ensureDayLocked(context);
            JSONObject plan = planLocked(context);
            JSONObject state = stateLocked(context);
            List<MealData.Stage> stages = MealData.buildStages(plan);
            changed |= reconcileLocked(context, plan, state, stages);

            MealData.Stage stage = MealData.findStage(stages, state.optString("stageKey"));
            if (stage != null && !state.optBoolean("finished", false)) {
                captureUndo(state, stage, "skip", "");
                JSONArray ids = confirmedArray(state, stage.key);
                markSkipped(context, state, stage, ids);
                advanceLocked(context, state, stages);
                changed = true;
            }
            if (changed) commit(context, state);
            return MealData.buildToday(plan, state, stages);
        }
    }

    public static JSONObject resetToday(Context context) {
        synchronized (LOCK) {
            ensureDayLocked(context);
            JSONObject plan = planLocked(context);
            JSONObject state = stateLocked(context);
            List<MealData.Stage> stages = MealData.buildStages(plan);
            String date = state.optString("date", MealData.logicalDate(plan));
            JSONObject fresh = MealData.newState(date, stages);
            Store.writeState(context, fresh);
            Store.appendEvent(context, MealData.event("day_reset", date));
            afterChangeLocked(context);
            return MealData.buildToday(plan, fresh, stages);
        }
    }

    /** 撤销最近一次确认/全部确认/跳过；只保留一步，限 10 分钟。 */
    public static JSONObject undoLast(Context context) {
        synchronized (LOCK) {
            boolean changed = ensureDayLocked(context);
            JSONObject plan = planLocked(context);
            JSONObject state = stateLocked(context);
            List<MealData.Stage> stages = MealData.buildStages(plan);
            changed |= reconcileLocked(context, plan, state, stages);

            JSONObject undo = state.optJSONObject("lastUndo");
            if (undo == null) {
                if (changed) commit(context, state);
                return error("没有可以撤销的操作");
            }
            if (!state.optString("date").equals(undo.optString("date"))) {
                state.remove("lastUndo");
                commit(context, state);
                return error("已经跨天，不能撤销了");
            }
            long at = undo.optLong("atMillis", 0L);
            if (at <= 0L || System.currentTimeMillis() - at > UNDO_WINDOW_MS) {
                state.remove("lastUndo");
                commit(context, state);
                return error("超过 10 分钟，不能撤销了");
            }
            MealData.Stage stage = MealData.findStage(stages, undo.optString("stageKey"));
            if (stage == null) {
                state.remove("lastUndo");
                commit(context, state);
                return error("这一项已经不在了，不能撤销");
            }

            String prevStageKey = undo.optString("prevStageKey", stage.key);
            if (MealData.findStage(stages, prevStageKey) == null) prevStageKey = stage.key;
            try {
                state.put("stageKey", prevStageKey);
                state.put("finished", undo.optBoolean("prevFinished", false));

                JSONObject confirmed = state.optJSONObject("confirmed");
                if (confirmed == null) {
                    confirmed = new JSONObject();
                    state.put("confirmed", confirmed);
                }
                confirmed.put(stage.key,
                        MealData.filterIds(undo.optJSONArray("prevConfirmed"), stage));

                JSONObject results = state.optJSONObject("results");
                if (results != null) {
                    Object prevResult = undo.opt("prevResult");
                    if (prevResult instanceof JSONObject) {
                        results.put(stage.key, prevResult);
                    } else {
                        results.remove(stage.key);
                    }
                }
                state.remove("lastUndo");

                JSONObject event = MealData.event("undo", state.optString("date"));
                event.put("stageKey", stage.key);
                event.put("title", stage.title);
                event.put("kind", undo.optString("kind"));
                Store.appendEvent(context, event);
            } catch (Exception error) {
                Log.w(TAG, "undo failed", error);
                return error("撤销失败，请重试");
            }

            commit(context, state);
            return MealData.buildToday(plan, state, stages);
        }
    }

    public static JSONObject saveMedicine(Context context, String json) {
        synchronized (LOCK) {
            try {
                JSONObject input = new JSONObject(json == null ? "{}" : json);
                String name = input.optString("name", "").trim();
                String amount = input.optString("amount", "").trim();
                String timing = input.optString("timing", MealData.BEFORE);
                if (name.isEmpty()) return error("请填写药名");
                if (name.length() > 30) return error("药名最多 30 个字");
                if (amount.isEmpty()) return error("请填写一次吃多少");
                if (amount.length() > 20) return error("用量最多 20 个字");
                if (!MealData.BEFORE.equals(timing) && !MealData.AFTER.equals(timing)) {
                    timing = MealData.BEFORE;
                }

                boolean changed = ensureDayLocked(context);
                JSONObject plan = planLocked(context);
                JSONArray meals = plan.optJSONArray("meals");
                JSONArray requestedMealIds = input.optJSONArray("mealIds");
                if (requestedMealIds == null) {
                    requestedMealIds = new JSONArray();
                    String single = input.optString("mealId", "");
                    if (!single.isEmpty()) requestedMealIds.put(single);
                }
                JSONArray mealIds = new JSONArray();
                Set<String> seenMeals = new HashSet<>();
                for (int i = 0; i < requestedMealIds.length(); i++) {
                    String mealId = requestedMealIds.optString(i);
                    if (!mealExists(meals, mealId)) return error("请选择哪几顿吃");
                    if (seenMeals.add(mealId)) mealIds.put(mealId);
                }
                if (mealIds.length() == 0) return error("请选择哪几顿吃");

                JSONArray medicines = plan.optJSONArray("medicines");
                if (medicines == null) {
                    medicines = new JSONArray();
                    plan.put("medicines", medicines);
                }
                String id = input.optString("id", "").trim();
                boolean editing = !id.isEmpty();
                JSONObject existing = null;
                int existingIndex = -1;
                if (editing) {
                    for (int i = 0; i < medicines.length(); i++) {
                        JSONObject med = medicines.optJSONObject(i);
                        if (med != null && id.equals(med.optString("id"))) {
                            existing = med;
                            existingIndex = i;
                            break;
                        }
                    }
                    if (existing == null) return error("这条药已经不存在了");
                } else {
                    id = "med-" + System.currentTimeMillis() + "-"
                            + (int) (Math.random() * 1000);
                }

                JSONObject med = existing == null ? new JSONObject() : existing;
                med.put("id", id);
                med.put("name", name);
                med.put("amount", amount);
                med.put("mealId", mealIds.optString(0));
                med.put("mealIds", mealIds);
                med.put("timing", timing);
                med.put("enabled", true);
                med.put("frequency", "daily");
                if (!med.has("createdAt")) med.put("createdAt", MealData.nowIso());
                if (existing == null) medicines.put(med);
                else medicines.put(existingIndex, med);

                Store.writePlan(context, plan);
                JSONObject event = MealData.event("plan_medicine_saved",
                        stateDate(context, plan));
                event.put("action", editing ? "edit" : "add");
                event.put("medicineId", id);
                event.put("name", name);
                Store.appendEvent(context, event);

                JSONObject state = stateLocked(context);
                List<MealData.Stage> stages = MealData.buildStages(plan);
                changed |= reconcileLocked(context, plan, state, stages);
                commit(context, state);

                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("id", id);
                return out;
            } catch (Exception error) {
                Log.w(TAG, "save medicine failed", error);
                return error("保存失败，请重试");
            }
        }
    }

    public static JSONObject deleteMedicine(Context context, String id) {
        synchronized (LOCK) {
            try {
                boolean changed = ensureDayLocked(context);
                JSONObject plan = planLocked(context);
                JSONArray medicines = plan.optJSONArray("medicines");
                if (medicines == null || id == null || id.isEmpty()) return error("没有找到这条药");
                JSONArray next = new JSONArray();
                String name = "";
                String photo = "";
                boolean removed = false;
                for (int i = 0; i < medicines.length(); i++) {
                    JSONObject med = medicines.optJSONObject(i);
                    if (med == null) continue;
                    if (id.equals(med.optString("id"))) {
                        name = med.optString("name");
                        photo = med.optString("photo", "");
                        removed = true;
                    } else {
                        next.put(med);
                    }
                }
                if (!removed) return error("这条药已经不存在了");
                plan.put("medicines", next);
                Store.writePlan(context, plan);
                MedPhoto.delete(context, photo);

                JSONObject event = MealData.event("plan_medicine_deleted",
                        stateDate(context, plan));
                event.put("medicineId", id);
                event.put("name", name);
                Store.appendEvent(context, event);

                JSONObject state = stateLocked(context);
                List<MealData.Stage> stages = MealData.buildStages(plan);
                changed |= reconcileLocked(context, plan, state, stages);
                commit(context, state);

                JSONObject out = new JSONObject();
                out.put("ok", true);
                return out;
            } catch (Exception error) {
                Log.w(TAG, "delete medicine failed", error);
                return error("删除失败，请重试");
            }
        }
    }

    public static JSONObject setMealCount(Context context, int value) {
        synchronized (LOCK) {
            try {
                boolean changed = ensureDayLocked(context);
                int count = Math.max(1, Math.min(4, value));
                JSONObject plan = planLocked(context);
                JSONArray oldMeals = plan.optJSONArray("meals");
                if (oldMeals != null && oldMeals.length() == count) {
                    return ok();
                }

                Set<String> keptIds = new HashSet<>();
                JSONArray newMeals = new JSONArray();
                for (int i = 1; i <= count; i++) {
                    JSONObject meal = new JSONObject();
                    meal.put("id", "meal-" + i);
                    meal.put("label", "第 " + i + " 顿");
                    newMeals.put(meal);
                    keptIds.add("meal-" + i);
                }

                JSONArray medicines = plan.optJSONArray("medicines");
                if (medicines != null) {
                    for (int i = 0; i < medicines.length(); i++) {
                        JSONObject med = medicines.optJSONObject(i);
                        if (med == null || !med.optBoolean("enabled", true)) continue;
                        String offending = null;
                        JSONArray medMealIds = med.optJSONArray("mealIds");
                        if (medMealIds != null) {
                            for (int j = 0; j < medMealIds.length(); j++) {
                                String mid = medMealIds.optString(j);
                                if (!keptIds.contains(mid)) {
                                    offending = mid;
                                    break;
                                }
                            }
                        } else {
                            String mid = med.optString("mealId");
                            if (!keptIds.contains(mid)) offending = mid;
                        }
                        if (offending != null) {
                            return error("「" + med.optString("name")
                                    + "」还在第 " + mealNumber(offending)
                                    + " 顿，请先编辑这条药");
                        }
                    }
                }

                plan.put("meals", newMeals);
                Store.writePlan(context, plan);
                Store.appendEvent(context, MealData.event("plan_meal_count",
                        stateDate(context, plan)).put("count", count));

                JSONObject state = stateLocked(context);
                List<MealData.Stage> stages = MealData.buildStages(plan);
                changed |= reconcileLocked(context, plan, state, stages);
                commit(context, state);
                return ok();
            } catch (Exception error) {
                Log.w(TAG, "set meal count failed", error);
                return error("修改失败，请重试");
            }
        }
    }

    public static JSONObject setFontScale(Context context, String value) {
        synchronized (LOCK) {
            try {
                double scale = Double.parseDouble(value);
                scale = Math.max(1.0, Math.min(1.6, scale));
                JSONObject plan = planLocked(context);
                plan.put("fontScale", scale);
                Store.writePlan(context, plan);
                JSONObject out = ok();
                out.put("fontScale", scale);
                return out;
            } catch (Exception error) {
                return error("字号设置失败");
            }
        }
    }

    /** 界面模式：normal / care（长辈版）。 */
    public static JSONObject setUiMode(Context context, String value) {
        synchronized (LOCK) {
            try {
                String mode = "care".equals(value) ? "care" : "normal";
                JSONObject plan = planLocked(context);
                plan.put("uiMode", mode);
                Store.writePlan(context, plan);
                Store.appendEvent(context, MealData.event("plan_ui_mode",
                        stateDate(context, plan)).put("mode", mode));
                notifyListeners(context);
                JSONObject out = ok();
                out.put("uiMode", mode);
                return out;
            } catch (Exception error) {
                return error("界面模式设置失败");
            }
        }
    }

    /** 药品照片显示方式：off / list / home。 */
    public static JSONObject setPhotoMode(Context context, String value) {
        synchronized (LOCK) {
            try {
                String mode;
                if ("list".equals(value)) mode = "list";
                else if ("home".equals(value)) mode = "home";
                else mode = "off";
                JSONObject plan = planLocked(context);
                plan.put("photoMode", mode);
                Store.writePlan(context, plan);
                Store.appendEvent(context, MealData.event("plan_photo_mode",
                        stateDate(context, plan)).put("mode", mode));
                notifyListeners(context);
                JSONObject out = ok();
                out.put("photoMode", mode);
                return out;
            } catch (Exception error) {
                return error("照片显示设置失败");
            }
        }
    }

    /** 返回已连续划掉次数；-1 表示通知已过期。 */
    public static int noteDismiss(Context context, String stageKey) {
        synchronized (LOCK) {
            try {
                JSONObject plan = planLocked(context);
                JSONObject state = Store.readState(context);
                if (state == null) return -1;
                if (!MealData.logicalDate(plan).equals(state.optString("date"))) return -1;
                if (!stageKey.equals(state.optString("stageKey"))) return -1;
                int count = state.optInt("dismissCount", 0) + 1;
                state.put("dismissCount", count);
                state.put("dismissStageKey", stageKey);
                state.put("updatedAt", MealData.nowIso());
                Store.writeState(context, state);
                return count;
            } catch (Exception error) {
                return -1;
            }
        }
    }

    private static JSONObject planLocked(Context context) {
        JSONObject plan = Store.readPlan(context);
        if (plan == null || !plan.has("meals")) {
            plan = Store.defaultPlan();
            Store.writePlan(context, plan);
        }
        return plan;
    }

    private static JSONObject stateLocked(Context context) {
        JSONObject state = Store.readState(context);
        if (state == null) {
            JSONObject plan = planLocked(context);
            state = MealData.newState(MealData.logicalDate(plan), MealData.buildStages(plan));
            Store.writeState(context, state);
        }
        return state;
    }

    private static boolean ensureDayLocked(Context context) {
        JSONObject plan = planLocked(context);
        String today = MealData.logicalDate(plan);
        JSONObject state = Store.readState(context);
        if (state == null) {
            state = MealData.newState(today, MealData.buildStages(plan));
            Store.writeState(context, state);
            Store.appendEvent(context, MealData.event("day_start", today));
            return true;
        }
        String date = state.optString("date", "");
        if (!today.equals(date)) {
            if (!date.isEmpty()) finalizeDayLocked(context, plan, state);
            state = MealData.newState(today, MealData.buildStages(plan));
            Store.writeState(context, state);
            Store.appendEvent(context, MealData.event("day_start", today));
            return true;
        }
        return false;
    }

    private static boolean reconcileLocked(Context context, JSONObject plan,
                                           JSONObject state, List<MealData.Stage> stages) {
        boolean changed = false;
        try {
            JSONObject results = state.optJSONObject("results");
            if (results == null) {
                results = new JSONObject();
                state.put("results", results);
                changed = true;
            }
            JSONObject confirmed = state.optJSONObject("confirmed");
            if (confirmed == null) {
                confirmed = new JSONObject();
                state.put("confirmed", confirmed);
                changed = true;
            }

            Set<String> validKeys = new HashSet<>();
            for (MealData.Stage stage : stages) validKeys.add(stage.key);

            List<String> staleConfirmed = new ArrayList<>();
            Iterator<String> confirmedKeys = confirmed.keys();
            while (confirmedKeys.hasNext()) {
                String key = confirmedKeys.next();
                MealData.Stage stage = MealData.findStage(stages, key);
                if (!validKeys.contains(key) || stage == null) {
                    staleConfirmed.add(key);
                    continue;
                }
                JSONArray ids = confirmed.optJSONArray(key);
                JSONArray filtered = MealData.filterIds(ids, stage);
                if (ids == null || filtered.length() != ids.length()) {
                    confirmed.put(key, filtered);
                    changed = true;
                }
            }
            for (String key : staleConfirmed) {
                confirmed.remove(key);
                changed = true;
            }

            List<String> staleResults = new ArrayList<>();
            Iterator<String> resultKeys = results.keys();
            while (resultKeys.hasNext()) {
                String key = resultKeys.next();
                if (!validKeys.contains(key)) staleResults.add(key);
            }
            for (String key : staleResults) {
                results.remove(key);
                changed = true;
            }

            for (MealData.Stage stage : stages) {
                if (results.has(stage.key)) continue;
                JSONArray ids = confirmed.optJSONArray(stage.key);
                if (MealData.allConfirmed(stage, ids)) {
                    markDone(context, state, stage, ids);
                    changed = true;
                }
            }

            String currentKey = state.optString("stageKey", "");
            MealData.Stage current = MealData.findStage(stages, currentKey);
            boolean finished = state.optBoolean("finished", false);
            if (current != null && !results.has(current.key)) {
                if (finished) {
                    state.put("finished", false);
                    changed = true;
                }
            } else {
                MealData.Stage next = MealData.firstUnresolved(stages, results);
                String nextKey = next == null ? "" : next.key;
                if (!nextKey.equals(currentKey)) {
                    state.put("stageKey", nextKey);
                    changed = true;
                }
                boolean nextFinished = next == null;
                if (finished != nextFinished) {
                    state.put("finished", nextFinished);
                    changed = true;
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "reconcile failed", error);
        }
        return changed;
    }

    /** 在动作前拍一张快照，供 10 分钟内撤销。只保留最近一次。 */
    private static void captureUndo(JSONObject state, MealData.Stage stage,
                                    String kind, String medId) {
        try {
            JSONObject undo = new JSONObject();
            undo.put("at", MealData.nowIso());
            undo.put("atMillis", System.currentTimeMillis());
            undo.put("date", state.optString("date"));
            undo.put("kind", kind);
            undo.put("stageKey", stage.key);
            undo.put("title", stage.title);
            undo.put("medId", medId == null ? "" : medId);
            undo.put("prevStageKey", state.optString("stageKey"));
            undo.put("prevFinished", state.optBoolean("finished", false));

            JSONObject confirmed = state.optJSONObject("confirmed");
            JSONArray ids = confirmed == null ? null : confirmed.optJSONArray(stage.key);
            undo.put("prevConfirmed",
                    ids == null ? new JSONArray() : new JSONArray(ids.toString()));

            JSONObject results = state.optJSONObject("results");
            Object prevResult = results == null ? null : results.opt(stage.key);
            undo.put("prevResult", prevResult == null ? JSONObject.NULL : prevResult);

            state.put("lastUndo", undo);
        } catch (Exception error) {
            Log.w(TAG, "capture undo failed", error);
        }
    }

    private static void markDone(Context context, JSONObject state,
                                 MealData.Stage stage, JSONArray ids) {
        JSONObject results = state.optJSONObject("results");
        if (results == null || results.has(stage.key)) return;
        try {
            JSONObject result = new JSONObject();
            result.put("status", "done");
            result.put("at", MealData.nowIso());
            result.put("medicines", MealData.snapshot(stage, ids));
            results.put(stage.key, result);

            JSONObject event = MealData.event("stage_done", state.optString("date"));
            event.put("stageKey", stage.key);
            event.put("title", stage.title);
            event.put("medicines", MealData.snapshot(stage, ids));
            Store.appendEvent(context, event);
        } catch (Exception error) {
            Log.w(TAG, "mark done failed", error);
        }
    }

    private static void markSkipped(Context context, JSONObject state,
                                    MealData.Stage stage, JSONArray ids) {
        JSONObject results = state.optJSONObject("results");
        if (results == null) return;
        try {
            JSONObject result = new JSONObject();
            result.put("status", "skipped");
            result.put("at", MealData.nowIso());
            result.put("medicines", MealData.snapshot(stage, ids));
            results.put(stage.key, result);

            JSONObject event = MealData.event("stage_skipped", state.optString("date"));
            event.put("stageKey", stage.key);
            event.put("title", stage.title);
            event.put("medicines", MealData.snapshot(stage, ids));
            Store.appendEvent(context, event);
        } catch (Exception error) {
            Log.w(TAG, "mark skipped failed", error);
        }
    }

    private static void advanceLocked(Context context, JSONObject state,
                                      List<MealData.Stage> stages) {
        JSONObject results = state.optJSONObject("results");
        int currentIndex = MealData.indexOf(stages, state.optString("stageKey"));
        MealData.Stage next = null;
        int start = currentIndex < 0 ? 0 : currentIndex + 1;
        for (int i = start; i < stages.size(); i++) {
            if (results == null || !results.has(stages.get(i).key)) {
                next = stages.get(i);
                break;
            }
        }
        try {
            if (next == null) {
                state.put("stageKey", "");
                if (!state.optBoolean("finished", false)) {
                    state.put("finished", true);
                    Store.appendEvent(context,
                            MealData.event("day_done", state.optString("date")));
                }
            } else {
                state.put("stageKey", next.key);
                state.put("finished", false);
            }
            state.put("dismissStageKey", state.optString("stageKey", ""));
            state.put("dismissCount", 0);
        } catch (Exception error) {
            Log.w(TAG, "advance failed", error);
        }
    }

    private static void finalizeDayLocked(Context context, JSONObject plan, JSONObject state) {
        try {
            List<MealData.Stage> stages = MealData.buildStages(plan);
            if (stages.isEmpty()) return;
            JSONObject results = state.optJSONObject("results");
            JSONObject confirmed = state.optJSONObject("confirmed");
            if (results == null) results = new JSONObject();
            if (confirmed == null) confirmed = new JSONObject();

            JSONArray summary = new JSONArray();
            int done = 0;
            int skipped = 0;
            int partial = 0;
            int missed = 0;

            for (MealData.Stage stage : stages) {
                JSONObject result = results.optJSONObject(stage.key);
                String status;
                JSONArray meds;
                String at = "";

                if (result != null) {
                    status = result.optString("status", "done");
                    if (!"done".equals(status) && !"skipped".equals(status)) status = "missed";
                    meds = result.optJSONArray("medicines");
                    if (meds == null) {
                        meds = MealData.snapshot(stage, confirmed.optJSONArray(stage.key));
                    }
                    at = result.optString("at", "");
                } else {
                    JSONArray ids = confirmed.optJSONArray(stage.key);
                    int count = MealData.countConfirmed(stage, ids);
                    if (count == 0) status = "missed";
                    else if (count >= stage.medicines.length()) status = "done";
                    else status = "partial";
                    meds = MealData.snapshot(stage, ids);
                }

                if ("done".equals(status)) done++;
                else if ("skipped".equals(status)) skipped++;
                else if ("partial".equals(status)) partial++;
                else missed++;

                JSONObject item = new JSONObject();
                item.put("key", stage.key);
                item.put("title", stage.title);
                item.put("timing", stage.timing);
                item.put("status", status);
                item.put("at", at);
                item.put("medicines", meds);
                summary.put(item);
            }

            JSONObject day = MealData.event("day_end", state.optString("date"));
            day.put("stages", summary);
            day.put("done", done);
            day.put("skipped", skipped);
            day.put("partial", partial);
            day.put("missed", missed);
            day.put("total", summary.length());
            Store.appendEvent(context, day);
        } catch (Exception error) {
            Log.w(TAG, "finalize day failed", error);
        }
    }

    private static JSONArray confirmedArray(JSONObject state, String key) {
        JSONObject confirmed = state.optJSONObject("confirmed");
        if (confirmed == null) {
            confirmed = new JSONObject();
            try {
                state.put("confirmed", confirmed);
            } catch (Exception ignored) {
            }
        }
        JSONArray ids = confirmed.optJSONArray(key);
        if (ids == null) {
            ids = new JSONArray();
            try {
                confirmed.put(key, ids);
            } catch (Exception ignored) {
            }
        }
        return ids;
    }

    private static JSONObject findMed(MealData.Stage stage, String medId) {
        for (int i = 0; i < stage.medicines.length(); i++) {
            JSONObject med = stage.medicines.optJSONObject(i);
            if (med != null && medId.equals(med.optString("id"))) return med;
        }
        return null;
    }

    private static boolean mealExists(JSONArray meals, String mealId) {
        if (meals == null) return false;
        for (int i = 0; i < meals.length(); i++) {
            JSONObject meal = meals.optJSONObject(i);
            if (meal != null && mealId.equals(meal.optString("id"))) return true;
        }
        return false;
    }

    private static int mealNumber(String mealId) {
        try {
            return Integer.parseInt(mealId.replace("meal-", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String stateDate(Context context, JSONObject plan) {
        JSONObject state = Store.readState(context);
        if (state != null && !state.optString("date", "").isEmpty()) {
            return state.optString("date");
        }
        return MealData.logicalDate(plan);
    }

    private static void appendMedEvent(Context context, JSONObject state, String type,
                                       MealData.Stage stage, JSONObject med) {
        try {
            JSONObject event = MealData.event(type, state.optString("date"));
            event.put("stageKey", stage.key);
            event.put("title", stage.title);
            event.put("medicineId", med.optString("id"));
            event.put("name", med.optString("name"));
            event.put("amount", med.optString("amount"));
            Store.appendEvent(context, event);
        } catch (Exception ignored) {
        }
    }

    private static void commit(Context context, JSONObject state) {
        try {
            state.put("updatedAt", MealData.nowIso());
        } catch (Exception ignored) {
        }
        Store.writeState(context, state);
        afterChangeLocked(context);
    }

    private static void afterChangeLocked(Context context) {
        try {
            Notifier.post(context);
        } catch (Exception error) {
            Log.w(TAG, "post notification failed", error);
        }
        notifyListeners(context);
    }

    private static void notifyListeners(Context context) {
        for (Listener listener : LISTENERS) {
            try {
                listener.onStateChanged();
            } catch (Exception error) {
                Log.w(TAG, "state listener failed", error);
            }
        }
        try {
            Intent intent = new Intent(ACTION_STATE_CHANGED);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
        } catch (Exception error) {
            Log.w(TAG, "state broadcast failed", error);
        }
    }

    private static JSONObject ok() {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static JSONObject error(String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", message);
        } catch (Exception ignored) {
        }
        return out;
    }
}
