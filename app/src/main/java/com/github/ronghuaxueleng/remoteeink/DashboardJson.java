package com.github.ronghuaxueleng.remoteeink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Dashboard JSON protocol parser and immutable data models. */
public final class DashboardJson {
    public static final String SCHEMA_ID = "kindle-dashboard-json/1";
    public static final String MISSING_TEXT = "--";
    public static final int FORECAST_DAYS = 7;
    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");

    private DashboardJson() {}

    public static DashboardData parse(String json) {
        return parse(json, System.currentTimeMillis());
    }

    public static DashboardData parse(String json, long now) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("天气 JSON 为空");
        }
        try {
            JSONObject root = new JSONObject(json);
            if (!SCHEMA_ID.equals(text(root, "schema", null))) {
                throw new IllegalArgumentException("不支持的 schema，需要 " + SCHEMA_ID);
            }
            return new DashboardData(root, now);
        } catch (JSONException e) {
            throw new IllegalArgumentException("无效 JSON：" + e.getMessage(), e);
        }
    }

    private static Object raw(JSONObject o, String key) {
        return o == null || o.isNull(key) ? null : o.opt(key);
    }
    private static String text(JSONObject o, String key, String fallback) {
        Object value = raw(o, key);
        return value instanceof String && !((String) value).trim().isEmpty()
                ? (String) value : fallback;
    }
    private static Double number(JSONObject o, String key) {
        Object value = raw(o, key);
        if (!(value instanceof Number)) return null;
        double d = ((Number) value).doubleValue();
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }
    private static Integer integer(JSONObject o, String key) {
        Double d = number(o, key);
        return d != null && d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE
                ? d.intValue() : null;
    }
    private static Boolean bool(JSONObject o, String key) {
        Object value = raw(o, key);
        return value instanceof Boolean ? (Boolean) value : null;
    }
    private static List<String> strings(JSONObject o, String key) {
        JSONArray array = o == null ? null : o.optJSONArray(key);
        List<String> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Object v = array.opt(i);
                if (v instanceof String) result.add((String) v);
            }
        }
        return Collections.unmodifiableList(result);
    }
    private static List<AlmanacHour> hours(JSONObject o, String key) {
        JSONArray array = o == null ? null : o.optJSONArray(key);
        List<AlmanacHour> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject v = array.optJSONObject(i);
                if (v != null) result.add(new AlmanacHour(v));
            }
        }
        return Collections.unmodifiableList(result);
    }
    private static SimpleDateFormat dayFormat() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(SHANGHAI);
        format.setLenient(false);
        return format;
    }
    static boolean isIsoDay(String day) {
        if (day == null || !day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return false;
        ParsePosition position = new ParsePosition(0);
        Date date = dayFormat().parse(day, position);
        return date != null && position.getIndex() == day.length();
    }
    static String addDays(String day, int count) {
        if (!isIsoDay(day)) throw new IllegalArgumentException("无效日期：" + day);
        Calendar calendar = Calendar.getInstance(SHANGHAI);
        calendar.setTime(dayFormat().parse(day, new ParsePosition(0)));
        calendar.add(Calendar.DAY_OF_MONTH, count);
        return dayFormat().format(calendar.getTime());
    }
    private static List<ForecastDay> forecast(JSONObject root, long now) {
        Map<String, JSONObject> byDate = new LinkedHashMap<>();
        JSONArray array = root.optJSONArray("forecast");
        String first = null;
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                String date = text(o, "date", null);
                if (isIsoDay(date) && !byDate.containsKey(date)) {
                    byDate.put(date, o);
                    if (first == null || date.compareTo(first) < 0) first = date;
                }
            }
        }
        if (first == null) first = addDays(dayFormat().format(new Date(now)), 1);
        List<ForecastDay> days = new ArrayList<>(FORECAST_DAYS);
        for (int i = 0; i < FORECAST_DAYS; i++) {
            String date = addDays(first, i);
            days.add(new ForecastDay(date, byDate.get(date)));
        }
        return Collections.unmodifiableList(days);
    }

    public static final class DashboardData {
        public final String schema, date, city, timezone, generatedAt;
        public final String weatherUpdatedAt, airUpdatedAt, weatherError, airError;
        public final String weatherSource, airSource;
        public final Boolean weatherStale, airStale;
        public final Current current;
        public final Air air;
        public final List<ForecastDay> forecast;
        public final Almanac almanac;

        private DashboardData(JSONObject o, long now) {
            schema = SCHEMA_ID;
            date = text(o, "date", null);
            city = text(o, "city", MISSING_TEXT);
            timezone = text(o, "timezone", "Asia/Shanghai");
            generatedAt = text(o, "generated_at", null);
            weatherUpdatedAt = text(o, "weather_updated_at", null);
            airUpdatedAt = text(o, "air_updated_at", null);
            weatherError = text(o, "weather_error", null);
            airError = text(o, "air_error", null);
            weatherSource = text(o, "weather_source", MISSING_TEXT);
            airSource = text(o, "air_source", MISSING_TEXT);
            weatherStale = bool(o, "weather_stale");
            airStale = bool(o, "air_stale");
            current = new Current(o.optJSONObject("current"));
            air = new Air(o.optJSONObject("air"));
            forecast = DashboardJson.forecast(o, now);
            almanac = new Almanac(o.optJSONObject("almanac"));
        }
        public boolean isWeatherStale() { return Boolean.TRUE.equals(weatherStale); }
        public boolean isAirStale() { return Boolean.TRUE.equals(airStale); }
        public String firstForecastDate() { return forecast.isEmpty() ? null : forecast.get(0).date; }
    }

    public static final class Current {
        public final Double temperature, feelsLike, high, low, humidity, wind, windMs, gust;
        public final Double windDirection, uvIndex;
        public final Integer windLevel, code;
        public final String windDirectionLabel, label, observationTime, sunrise, sunset;
        private Current(JSONObject o) {
            temperature = number(o, "temperature"); feelsLike = number(o, "feels_like");
            high = number(o, "high"); low = number(o, "low"); humidity = number(o, "humidity");
            wind = number(o, "wind"); windMs = number(o, "wind_ms"); gust = number(o, "gust");
            windDirection = number(o, "wind_direction"); uvIndex = number(o, "uv_index");
            windLevel = integer(o, "wind_level"); code = integer(o, "code");
            windDirectionLabel = text(o, "wind_direction_label", MISSING_TEXT);
            label = text(o, "label", MISSING_TEXT);
            observationTime = text(o, "observation_time", null);
            sunrise = text(o, "sunrise", null); sunset = text(o, "sunset", null);
        }
    }
    public static final class Air {
        public final Double pm25, aqi;
        public final String aqiSystem, pm25Grade, observationTime;
        private Air(JSONObject o) {
            pm25 = number(o, "pm2_5"); aqi = number(o, "aqi");
            aqiSystem = text(o, "aqi_system", MISSING_TEXT);
            pm25Grade = text(o, "pm2_5_grade", MISSING_TEXT);
            observationTime = text(o, "observation_time", null);
        }
    }
    public static final class ForecastDay {
        public final String date, label, weekday;
        public final Integer code, windLevel;
        public final Double high, low, rainProbability, wind, gust, windMs;
        private ForecastDay(String day, JSONObject o) {
            date = day; code = integer(o, "code"); label = text(o, "label", MISSING_TEXT);
            weekday = text(o, "weekday", null); high = number(o, "high"); low = number(o, "low");
            rainProbability = number(o, "rain_probability"); wind = number(o, "wind");
            gust = number(o, "gust"); windMs = number(o, "wind_ms"); windLevel = integer(o, "wind_level");
        }
    }
    public static final class Almanac {
        public final String lunar, ganzhi, term, festival, termStatus;
        public final List<String> goodTimes, badTimes, yi, ji;
        public final List<AlmanacHour> goodHours, badHours;
        private Almanac(JSONObject o) {
            lunar = text(o, "lunar", MISSING_TEXT); ganzhi = text(o, "ganzhi", MISSING_TEXT);
            term = text(o, "term", MISSING_TEXT); festival = text(o, "festival", MISSING_TEXT);
            termStatus = text(o, "term_status", null);
            goodTimes = strings(o, "good_times"); badTimes = strings(o, "bad_times");
            yi = strings(o, "yi"); ji = strings(o, "ji");
            goodHours = hours(o, "good_hours"); badHours = hours(o, "bad_hours");
        }
    }
    public static final class AlmanacHour {
        public final String name;
        public final Integer startHour, endHour;
        private AlmanacHour(JSONObject o) {
            name = text(o, "name", MISSING_TEXT);
            startHour = integer(o, "start_hour"); endHour = integer(o, "end_hour");
        }
    }
}
