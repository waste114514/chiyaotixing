package com.family.yaodian;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 纯粹的数据拼装：阶段、今日视图、历史视图、日期工具。 */
public final class MealData {

    public static final String BEFORE = "before";
    public static final String AFTER = "after";

    public static final class Stage {
        public final String key;
        public final String mealId;
        public final String mealLabel;
        public final String timing;
        public final String title;
        public final String subtitle;
        public final String confirmLabel;
        public final int mealIndex;
        public final JSONArray medicines;

        Stage(String key, String mealId, String mealLabel, String timing,
              String title, String subtitle, String confirmLabel,
              int mealIndex, JSONArray medicines) {
            this.key = key;
            this.mealId = mealId;
            this.mealLabel = mealLabel;
            this.timing = timing;
            this.title = title;
            this.subtitle = subtitle;
            this.confirmLabel = confirmLabel;
            this.mealIndex = mealIndex;
            this.medicines = medicines;
        }
    }

    private MealData() {
    }

    public static List<Stage> buildStages(JSONObject plan) {
        List<Stage> stages = new ArrayList<>();
        JSONArray meals = plan.optJSONArray("meals");
        JSONArray medicines = plan.optJSONArray("medicines");
        if (meals == null) return stages;

        for (int i = 0; i < meals.length(); i++) {
            JSONObject meal = meals.optJSONObject(i);
            if (meal == null) continue;
            String mealId = meal.optString("id", "meal-" + (i + 1));
            String mealLabel = meal.optString("label", "第 " + (i + 1) + " 顿");

            for (String timing : new String[]{BEFORE, AFTER}) {
                JSONArray list = new JSONArray();
                if (medicines != null) {
                    for (int j = 0; j < medicines.length(); j++) {
                        JSONObject med = medicines.optJSONObject(j);
                        if (med == null) continue;
                        if (!med.optBoolean("enabled", true)) continue;
                        if (!medicineAppliesToMeal(med, mealId)) continue;
                        if (!timing.equals(med.optString("timing", BEFORE))) continue;
                        try {
                            JSONObject item = new JSONObject();
                            item.put("id", med.optString("id"));
                            item.put("name", med.optString("name"));
                            item.put("amount", med.optString("amount"));
                            item.put("photo", med.optString("photo", ""));
                            list.put(item);
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (list.length() == 0) continue;

                boolean before = BEFORE.equals(timing);
                String title = mealLabel + " · " + (before ? "饭前" : "饭后");
                String subtitle = before ? "吃饭前吃" : "吃完饭后吃";
                String confirmLabel;
                if (before) {
                    confirmLabel = list.length() > 1 ? "全部已吃" : "已吃";
                } else {
                    confirmLabel = "吃完后确认";
                }
                stages.add(new Stage(
                        mealId + "|" + timing,
                        mealId,
                        mealLabel,
                        timing,
                        title,
                        subtitle,
                        confirmLabel,
                        i + 1,
                        list
                ));
            }
        }
        return stages;
    }

    private static boolean medicineAppliesToMeal(JSONObject med, String mealId) {
        JSONArray ids = med.optJSONArray("mealIds");
        if (ids != null) {
            for (int i = 0; i < ids.length(); i++) {
                if (mealId.equals(ids.optString(i))) return true;
            }
            return false;
        }
        return mealId.equals(med.optString("mealId"));
    }

    public static Stage findStage(List<Stage> stages, String key) {
        if (key == null || key.isEmpty()) return null;
        for (Stage stage : stages) {
            if (key.equals(stage.key)) return stage;
        }
        return null;
    }

    public static int indexOf(List<Stage> stages, String key) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    public static Stage firstUnresolved(List<Stage> stages, JSONObject results) {
        for (Stage stage : stages) {
            if (results == null || !results.has(stage.key)) return stage;
        }
        return null;
    }

    public static JSONObject newState(String date, List<Stage> stages) {
        JSONObject state = new JSONObject();
        try {
            state.put("schema", 1);
            state.put("date", date);
            state.put("results", new JSONObject());
            state.put("confirmed", new JSONObject());
            state.put("dismissStageKey", "");
            state.put("dismissCount", 0);
            state.put("updatedAt", nowIso());
            if (stages.isEmpty()) {
                state.put("stageKey", "");
                state.put("finished", true);
            } else {
                state.put("stageKey", stages.get(0).key);
                state.put("finished", false);
            }
        } catch (Exception ignored) {
        }
        return state;
    }

    public static JSONObject stageJson(Stage stage, JSONObject confirmedMap) {
        JSONObject out = new JSONObject();
        try {
            out.put("key", stage.key);
            out.put("mealId", stage.mealId);
            out.put("mealLabel", stage.mealLabel);
            out.put("timing", stage.timing);
            out.put("title", stage.title);
            out.put("subtitle", stage.subtitle);
            out.put("confirmLabel", stage.confirmLabel);
            out.put("mealIndex", stage.mealIndex);
            out.put("medicines", medicinesWithConfirmed(stage, confirmedMap));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static JSONArray medicinesWithConfirmed(Stage stage, JSONObject confirmedMap) {
        JSONArray out = new JSONArray();
        JSONArray ids = confirmedMap == null ? null : confirmedMap.optJSONArray(stage.key);
        for (int i = 0; i < stage.medicines.length(); i++) {
            JSONObject med = stage.medicines.optJSONObject(i);
            if (med == null) continue;
            try {
                JSONObject item = new JSONObject(med.toString());
                item.put("confirmed", contains(ids, med.optString("id")));
                out.put(item);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONArray snapshot(Stage stage, JSONArray confirmedIds) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < stage.medicines.length(); i++) {
            JSONObject med = stage.medicines.optJSONObject(i);
            if (med == null) continue;
            try {
                JSONObject item = new JSONObject(med.toString());
                item.put("confirmed", contains(confirmedIds, med.optString("id")));
                out.put(item);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static boolean allConfirmed(Stage stage, JSONArray confirmedIds) {
        if (stage.medicines.length() == 0) return false;
        for (int i = 0; i < stage.medicines.length(); i++) {
            JSONObject med = stage.medicines.optJSONObject(i);
            if (med == null) return false;
            if (!contains(confirmedIds, med.optString("id"))) return false;
        }
        return true;
    }

    public static int countConfirmed(Stage stage, JSONArray confirmedIds) {
        int count = 0;
        for (int i = 0; i < stage.medicines.length(); i++) {
            JSONObject med = stage.medicines.optJSONObject(i);
            if (med != null && contains(confirmedIds, med.optString("id"))) count++;
        }
        return count;
    }

    public static boolean contains(JSONArray array, String value) {
        if (array == null || value == null) return false;
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i))) return true;
        }
        return false;
    }

    public static JSONArray filterIds(JSONArray ids, Stage stage) {
        JSONArray out = new JSONArray();
        if (ids == null) return out;
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i);
            for (int j = 0; j < stage.medicines.length(); j++) {
                JSONObject med = stage.medicines.optJSONObject(j);
                if (med != null && id.equals(med.optString("id"))) {
                    out.put(id);
                    break;
                }
            }
        }
        return out;
    }

    public static JSONObject buildToday(JSONObject plan, JSONObject state, List<Stage> stages) {
        JSONObject out = new JSONObject();
        try {
            JSONObject results = state.optJSONObject("results");
            if (results == null) results = new JSONObject();
            JSONObject confirmed = state.optJSONObject("confirmed");
            if (confirmed == null) confirmed = new JSONObject();

            String currentKey = state.optString("stageKey", "");
            boolean finished = state.optBoolean("finished", false);
            int currentIndex = indexOf(stages, currentKey);

            int totalMeds = 0;
            JSONArray planMeds = plan.optJSONArray("medicines");
            if (planMeds != null) {
                for (int i = 0; i < planMeds.length(); i++) {
                    JSONObject med = planMeds.optJSONObject(i);
                    if (med != null && med.optBoolean("enabled", true)) totalMeds++;
                }
            }

            JSONArray outStages = new JSONArray();
            int done = 0;
            int skipped = 0;
            for (int i = 0; i < stages.size(); i++) {
                Stage stage = stages.get(i);
                JSONObject result = results.optJSONObject(stage.key);
                String status;
                JSONArray meds;
                String at = "";

                if (result != null) {
                    status = result.optString("status", "done");
                    meds = result.optJSONArray("medicines");
                    if (meds == null) meds = medicinesWithConfirmed(stage, confirmed);
                    at = result.optString("at", "");
                } else if (!finished && stage.key.equals(currentKey)) {
                    status = "current";
                    meds = medicinesWithConfirmed(stage, confirmed);
                } else if (!finished && currentIndex >= 0 && i < currentIndex) {
                    status = "missed";
                    meds = medicinesWithConfirmed(stage, confirmed);
                } else {
                    status = "upcoming";
                    meds = medicinesWithConfirmed(stage, confirmed);
                }

                if ("done".equals(status)) done++;
                else if ("skipped".equals(status)) skipped++;

                JSONObject item = stageJson(stage, confirmed);
                item.put("status", status);
                item.put("statusLabel", statusLabel(status));
                item.put("at", at);
                item.put("medicines", meds);
                outStages.put(item);
            }

            JSONObject current = null;
            if (!finished && totalMeds > 0) {
                Stage stage = findStage(stages, currentKey);
                if (stage != null) {
                    current = stageJson(stage, confirmed);
                    JSONArray meds = current.optJSONArray("medicines");
                    int confirmedCount = 0;
                    if (meds != null) {
                        for (int i = 0; i < meds.length(); i++) {
                            if (meds.optJSONObject(i).optBoolean("confirmed")) confirmedCount++;
                        }
                    }
                    current.put("confirmedCount", confirmedCount);
                    current.put("remaining", stage.medicines.length() - confirmedCount);
                    current.put("stageIndex", indexOf(stages, stage.key) + 1);
                    current.put("stageCount", stages.size());
                }
            }

            JSONObject counts = new JSONObject();
            counts.put("total", stages.size());
            counts.put("done", done);
            counts.put("skipped", skipped);
            counts.put("remaining", Math.max(0, stages.size() - done - skipped));

            out.put("ok", true);
            out.put("date", state.optString("date", ""));
            out.put("dateLabel", dateLabel(state.optString("date", "")));
            out.put("mealCount", plan.optJSONArray("meals") == null
                    ? 0 : plan.optJSONArray("meals").length());
            out.put("hasMeds", !stages.isEmpty());
            out.put("totalMeds", totalMeds);
            out.put("fontScale", plan.optDouble("fontScale", 1.0));
            out.put("uiMode", plan.optString("uiMode", "normal"));
            out.put("photoMode", plan.optString("photoMode", "off"));
            out.put("finished", finished);
            out.put("current", current == null ? JSONObject.NULL : current);
            out.put("stages", outStages);
            out.put("counts", counts);

            JSONObject undo = state.optJSONObject("lastUndo");
            JSONObject undoOut = null;
            if (undo != null && state.optString("date").equals(undo.optString("date"))) {
                long at = undo.optLong("atMillis", 0L);
                long left = 10 * 60 * 1000L - (System.currentTimeMillis() - at);
                if (at > 0L && left > 0L) {
                    undoOut = new JSONObject();
                    undoOut.put("available", true);
                    undoOut.put("label", undo.optString("title"));
                    undoOut.put("kind", undo.optString("kind"));
                    undoOut.put("secondsLeft", left / 1000L);
                }
            }
            out.put("undo", undoOut == null ? JSONObject.NULL : undoOut);
        } catch (Exception ignored) {
        }
        return out;
    }

    public static JSONObject buildHistory(Context context, int limit) {
        JSONObject out = new JSONObject();
        JSONArray days = Store.readDayEnds(context, limit);
        try {
            for (int i = 0; i < days.length(); i++) {
                JSONObject day = days.optJSONObject(i);
                if (day == null) continue;
                day.put("dateLabel", dateLabel(day.optString("date", "")));
            }
            out.put("ok", true);
            out.put("days", days);
        } catch (Exception ignored) {
        }
        return out;
    }

    public static String statusLabel(String status) {
        if ("done".equals(status)) return "已完成";
        if ("skipped".equals(status)) return "已跳过";
        if ("current".equals(status)) return "现在吃";
        if ("missed".equals(status)) return "未确认";
        if ("partial".equals(status)) return "没吃完";
        return "还没到";
    }

    public static String logicalDate(JSONObject plan) {
        int hour = 4;
        int minute = 0;
        String boundary = plan == null ? "04:00" : plan.optString("dayBoundary", "04:00");
        try {
            String[] parts = boundary.split(":");
            hour = Integer.parseInt(parts[0]);
            minute = Integer.parseInt(parts[1]);
        } catch (Exception ignored) {
        }
        Calendar now = Calendar.getInstance();
        Calendar cut = (Calendar) now.clone();
        cut.set(Calendar.HOUR_OF_DAY, hour);
        cut.set(Calendar.MINUTE, minute);
        cut.set(Calendar.SECOND, 0);
        cut.set(Calendar.MILLISECOND, 0);
        if (now.before(cut)) {
            cut.add(Calendar.DAY_OF_YEAR, -1);
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(cut.getTime());
    }

    public static String dateLabel(String date) {
        if (date == null || date.isEmpty()) return "";
        try {
            Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date);
            if (parsed == null) return date;
            return new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(parsed);
        } catch (Exception ignored) {
            return date;
        }
    }

    public static String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CHINA)
                .format(new Date());
    }

    public static JSONObject event(String type, String date) {
        JSONObject event = new JSONObject();
        try {
            event.put("at", nowIso());
            event.put("date", date);
            event.put("type", type);
        } catch (Exception ignored) {
        }
        return event;
    }

    public static JSONArray names(Stage stage, JSONArray confirmedIds) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < stage.medicines.length(); i++) {
            JSONObject med = stage.medicines.optJSONObject(i);
            if (med == null) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("name", med.optString("name"));
                item.put("amount", med.optString("amount"));
                item.put("confirmed", contains(confirmedIds, med.optString("id")));
            } catch (Exception ignored) {
            }
            out.put(item);
        }
        return out;
    }
}
