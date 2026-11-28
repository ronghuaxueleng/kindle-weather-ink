package com.github.ronghuaxueleng.remoteeink;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/** Annual holiday arrangements supplied by Timor and cached independently of weather. */
@SuppressWarnings("deprecation")
public final class HolidayCalendar {
    private static final long UPDATE_INTERVAL = 24 * 60 * 60 * 1000L;
    private static final long RETRY_INTERVAL = 60 * 60 * 1000L;
    private final Context context;
    private final SharedPreferences preferences;
    private final Map<Integer, YearData> years = new HashMap<>();
    private final Map<Integer, Long> attempts = new HashMap<>();
    private final Map<Integer, String> errors = new HashMap<>();
    private Request request;

    public static final class Day {
        public final String name;
        public final boolean work;

        private Day(String name, boolean work) { this.name = name; this.work = work; }
    }

    private static final class YearData {
        final Map<String, Day> days;
        final long savedAt;

        YearData(Map<String, Day> days, long savedAt) { this.days = days; this.savedAt = savedAt; }
    }

    public HolidayCalendar(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("timor_holidays", Context.MODE_PRIVATE);
    }

    public void load(int year) {
        if (years.containsKey(year)) return;
        String body = preferences.getString("year_" + year, null);
        YearData cached = null;
        if (body != null) {
            try { cached = parse(body, year, preferences.getLong("saved_at_" + year, 0)); }
            catch (Exception e) { errors.put(year, "本地放假安排不可用"); }
        }
        years.put(year, cached);
    }

    public Day get(String date) {
        YearData data = years.get(Integer.parseInt(date.substring(0, 4)));
        return data == null ? null : data.days.get(date);
    }

    public boolean hasYear(int year) { return years.get(year) != null; }

    public String status(int year) {
        if (request != null && request.year == year) {
            return hasYear(year) ? "放假安排：提莫 · 正在更新" : "正在获取放假安排…";
        }
        if (hasYear(year)) return "放假安排：提莫 · 按国务院安排整理"
                + (errors.containsKey(year) ? " · 使用本地缓存" : "");
        String error = errors.get(year);
        return error == null ? "尚未获取该年份放假安排" : error + "，暂不标记休班";
    }

    public void requestYear(int year, Runnable onChanged) {
        load(year);
        if (request != null && request.year == year) return;
        cancel();
        long now = System.currentTimeMillis();
        YearData cached = years.get(year);
        if (cached != null && now >= cached.savedAt && now - cached.savedAt < UPDATE_INTERVAL) return;
        Long attempted = attempts.get(year);
        if (attempted != null && now >= attempted && now - attempted < RETRY_INTERVAL) return;
        attempts.put(year, now);
        request = new Request(year, onChanged);
        request.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void cancel() {
        if (request == null) return;
        attempts.remove(request.year);
        request.cancel(true);
        request.http.cancel();
        request = null;
    }

    private static YearData parse(String body, int year, long savedAt) throws Exception {
        JSONObject root = new JSONObject(body);
        if (root.optInt("code", -1) != 0) throw new IOException("放假安排获取失败");
        JSONObject holidays = root.getJSONObject("holiday");
        if (holidays.length() == 0) throw new IOException("该年份暂无放假安排");
        Map<String, Day> days = new HashMap<>();
        Iterator<String> keys = holidays.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject item = holidays.getJSONObject(key);
            String date = item.getString("date"), name = item.getString("name").trim();
            if (!DashboardJson.isIsoDay(date) || !date.startsWith(year + "-")
                    || !date.substring(5).equals(key) || name.isEmpty()
                    || !(item.get("holiday") instanceof Boolean)) throw new IOException("放假安排数据无效");
            days.put(date, new Day(name, !item.getBoolean("holiday")));
        }
        return new YearData(days, savedAt);
    }

    private final class Request extends AsyncTask<Void, Void, YearData> {
        final int year;
        final Runnable onChanged;
        final JsonHttpClient http = new JsonHttpClient(context, "", "");
        String error;

        Request(int year, Runnable onChanged) { this.year = year; this.onChanged = onChanged; }

        @Override protected YearData doInBackground(Void... ignored) {
            try {
                String body = http.fetch("https://timor.tech/api/holiday/year/" + year + "/", 8000);
                YearData data = parse(body, year, System.currentTimeMillis());
                if (isCancelled()) return null;
                preferences.edit().putString("year_" + year, body).putLong("saved_at_" + year, data.savedAt).apply();
                return data;
            } catch (Exception e) {
                error = "放假安排获取失败";
                return null;
            }
        }

        @Override protected void onPostExecute(YearData data) {
            if (request != this || isCancelled()) return;
            request = null;
            if (data != null) { years.put(year, data); errors.remove(year); }
            else errors.put(year, error);
            onChanged.run();
        }
    }
}
