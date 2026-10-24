package com.family.yaodian;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本地 JSON 存储。
 *
 * plan.json / state.json / events-YYYY-MM.jsonl 都放在 device-protected storage，
 * 这样重启后未解锁时，通知和锁屏卡片也能读到药品和当前阶段。
 */
public final class Store {

    private static final String TAG = "MedApp";
    private static final String DIR = "med_data";
    private static final String PLAN_FILE = "plan.json";
    private static final String STATE_FILE = "state.json";

    private Store() {
    }

    private static File dir(Context context) {
        Context storage = context;
        try {
            Context device = context.createDeviceProtectedStorageContext();
            if (device != null) {
                storage = device;
            }
        } catch (Exception error) {
            Log.w(TAG, "device protected storage unavailable", error);
        }
        File dir = new File(storage.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create data dir " + dir);
        }
        return dir;
    }

    public static synchronized JSONObject readPlan(Context context) {
        return readJson(new File(dir(context), PLAN_FILE));
    }

    public static synchronized void writePlan(Context context, JSONObject plan) {
        writeJson(new File(dir(context), PLAN_FILE), plan);
    }

    public static synchronized JSONObject readState(Context context) {
        return readJson(new File(dir(context), STATE_FILE));
    }

    public static synchronized void writeState(Context context, JSONObject state) {
        writeJson(new File(dir(context), STATE_FILE), state);
    }

    /** 追加一条审计事件；按事件日期分月。 */
    public static synchronized void appendEvent(Context context, JSONObject event) {
        if (event == null) return;
        String at = event.optString("at", "");
        String month;
        if (at.length() >= 7) {
            month = at.substring(0, 7);
        } else {
            month = new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(new Date());
        }
        File file = new File(dir(context), "events-" + month + ".jsonl");
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((event.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception error) {
            Log.w(TAG, "append event failed", error);
        }
    }

    /** 读取全部事件，按月份文件名升序、行序；备份用。 */
    public static synchronized JSONArray readAllEvents(Context context) {
        JSONArray result = new JSONArray();
        File[] files = dir(context).listFiles(
                (d, name) -> name.startsWith("events-") && name.endsWith(".jsonl")
        );
        if (files == null || files.length == 0) return result;

        List<File> sorted = new ArrayList<>();
        for (File file : files) sorted.add(file);
        sorted.sort((a, b) -> a.getName().compareTo(b.getName()));

        for (File file : sorted) {
            for (String line : readLines(file)) {
                try {
                    result.put(new JSONObject(line));
                } catch (Exception ignored) {
                    // 跳过损坏行。
                }
                if (result.length() >= 100000) return result;
            }
        }
        return result;
    }

    /** 用备份中的事件整体替换本机事件；先删旧文件，再按月份重写。 */
    public static synchronized void replaceEvents(Context context, JSONArray events) {
        File[] files = dir(context).listFiles(
                (d, name) -> name.startsWith("events-") && name.endsWith(".jsonl")
        );
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        if (events == null || events.length() == 0) return;

        Map<String, StringBuilder> byMonth = new LinkedHashMap<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            String at = event.optString("at", "");
            String month = at.length() >= 7 && at.charAt(4) == '-'
                    ? at.substring(0, 7)
                    : new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(new Date());
            StringBuilder builder = byMonth.get(month);
            if (builder == null) {
                builder = new StringBuilder();
                byMonth.put(month, builder);
            }
            builder.append(event.toString()).append('\n');
        }
        for (Map.Entry<String, StringBuilder> entry : byMonth.entrySet()) {
            try {
                writeText(new File(dir(context), "events-" + entry.getKey() + ".jsonl"),
                        entry.getValue().toString());
            } catch (Exception error) {
                Log.w(TAG, "rewrite events failed: " + entry.getKey(), error);
            }
        }
    }

    /** 读取最近若干天的 day_end 结算记录，按日期倒序。 */
    public static synchronized JSONArray readDayEnds(Context context, int limit) {
        JSONArray result = new JSONArray();
        int max = Math.max(1, Math.min(limit, 60));
        File[] files = dir(context).listFiles(
                (d, name) -> name.startsWith("events-") && name.endsWith(".jsonl")
        );
        if (files == null || files.length == 0) return result;

        List<File> sorted = new ArrayList<>();
        for (File file : files) sorted.add(file);
        sorted.sort((a, b) -> b.getName().compareTo(a.getName()));

        Set<String> seen = new HashSet<>();
        for (File file : sorted) {
            List<String> lines = readLines(file);
            for (int i = lines.size() - 1; i >= 0; i--) {
                try {
                    JSONObject event = new JSONObject(lines.get(i));
                    if (!"day_end".equals(event.optString("type"))) continue;
                    String date = event.optString("date");
                    if (date.isEmpty() || !seen.add(date)) continue;
                    result.put(event);
                    if (result.length() >= max) return result;
                } catch (Exception ignored) {
                    // 单行损坏不影响其他记录。
                }
            }
        }
        return result;
    }

    public static JSONObject defaultPlan() {
        JSONObject plan = new JSONObject();
        try {
            plan.put("schema", 1);
            plan.put("dayBoundary", "04:00");
            plan.put("fontScale", 1.0);
            plan.put("uiMode", "normal");
            plan.put("photoMode", "off");
            JSONArray meals = new JSONArray();
            for (int i = 1; i <= 3; i++) {
                JSONObject meal = new JSONObject();
                meal.put("id", "meal-" + i);
                meal.put("label", "第 " + i + " 顿");
                meals.put(meal);
            }
            plan.put("meals", meals);
            plan.put("medicines", new JSONArray());
        } catch (Exception error) {
            Log.e(TAG, "default plan failed", error);
        }
        return plan;
    }

    private static JSONObject readJson(File file) {
        if (!file.exists()) return null;
        String text = readText(file);
        if (text == null || text.trim().isEmpty()) return null;
        try {
            return new JSONObject(text);
        } catch (Exception error) {
            Log.w(TAG, "read json failed: " + file.getName(), error);
            return null;
        }
    }

    private static void writeJson(File file, JSONObject value) {
        if (value == null) return;
        try {
            writeText(file, value.toString());
        } catch (Exception error) {
            Log.w(TAG, "write json failed: " + file.getName(), error);
        }
    }

    private static String readText(File file) {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } catch (Exception error) {
            Log.w(TAG, "read text failed: " + file.getName(), error);
            return null;
        }
    }

    private static List<String> readLines(File file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
        } catch (Exception error) {
            Log.w(TAG, "read lines failed: " + file.getName(), error);
        }
        return lines;
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(parent, file.getName() + ".tmp");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
                out.flush();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
