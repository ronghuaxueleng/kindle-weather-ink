package com.github.ronghuaxueleng.remoteeink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Explicitly labeled example data for viewing the app without a weather server. */
final class DemoData {
    private DemoData() {}

    static String create(String city) {
        try {
            TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
            Calendar now = Calendar.getInstance(zone);
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat instant = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            day.setTimeZone(zone); instant.setTimeZone(zone);
            String timestamp = instant.format(now.getTime());
            JSONObject root = new JSONObject();
            root.put("schema", DashboardJson.SCHEMA_ID);
            root.put("date", day.format(now.getTime()));
            root.put("city", city);
            root.put("timezone", "Asia/Shanghai");
            root.put("generated_at", timestamp);
            root.put("weather_updated_at", timestamp);
            root.put("air_updated_at", timestamp);
            root.put("weather_stale", false); root.put("air_stale", false);
            root.put("weather_source", "本地演示，非实时天气");
            root.put("air_source", "本地演示，非实时空气质量");
            JSONObject current = new JSONObject();
            current.put("temperature", 18); current.put("feels_like", 17);
            current.put("high", 22); current.put("low", 12); current.put("humidity", 46);
            current.put("wind", 10.8); current.put("wind_ms", 3.0); current.put("gust", 4.2);
            current.put("wind_level", 2); current.put("wind_direction", 45);
            current.put("wind_direction_label", "东北风");
            current.put("code", 2); current.put("label", "多云");
            current.put("observation_time", timestamp); current.put("uv_index", 3);
            root.put("current", current);
            JSONObject air = new JSONObject();
            air.put("pm2_5", 21); air.put("aqi", 35); air.put("aqi_system", "CN");
            air.put("aqi_label", "优（演示）"); air.put("primary_pollutant", "PM2.5 21 μg/m³");
            air.put("pm2_5_grade", "优"); air.put("observation_time", timestamp);
            root.put("air", air);
            JSONArray forecast = new JSONArray();
            int[] codes = {0, 2, 3, 61, 63, 2, 0};
            String[] labels = {"晴", "多云", "阴", "小雨", "中雨", "多云", "晴"};
            for (int i = 0; i < 7; i++) {
                now.add(Calendar.DAY_OF_MONTH, 1);
                JSONObject item = new JSONObject();
                item.put("date", day.format(now.getTime())); item.put("code", codes[i]);
                item.put("label", labels[i]); item.put("high", 22 - i % 4);
                item.put("low", 12 - i % 3); item.put("rain_probability", codes[i] >= 51 ? 70 : 10);
                item.put("wind_ms", 3); item.put("wind_level", 2);
                forecast.put(item);
            }
            root.put("forecast", forecast);
            JSONObject almanac = new JSONObject();
            almanac.put("lunar", "农历示例"); almanac.put("ganzhi", "干支示例");
            almanac.put("term", "演示"); almanac.put("festival", "示例数据");
            almanac.put("good_times", new JSONArray().put("子 23–01").put("卯 05–07").put("午 11–13"));
            almanac.put("bad_times", new JSONArray().put("丑 01–03").put("辰 07–09").put("酉 17–19"));
            almanac.put("yi", new JSONArray().put("阅读").put("休息").put("整理").put("散步"));
            almanac.put("ji", new JSONArray().put("此为界面示例").put("请以实际黄历为准"));
            root.put("almanac", almanac);
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to create demo data", e);
        }
    }
}
