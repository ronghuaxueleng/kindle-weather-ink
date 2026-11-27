package com.github.ronghuaxueleng.remoteeink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts weather.com.cn's JSON assignments without executing JavaScript. */
public final class ChinaWeatherJson {
    private static final TimeZone ZONE = TimeZone.getTimeZone("Asia/Shanghai");
    private static final String[] LABELS = {"晴", "多云", "阴", "阵雨", "雷阵雨", "雷阵雨伴有冰雹",
            "雨夹雪", "小雨", "中雨", "大雨", "暴雨", "大暴雨", "特大暴雨", "阵雪", "小雪",
            "中雪", "大雪", "暴雪", "雾", "冻雨", "沙尘暴", "小到中雨", "中到大雨", "大到暴雨",
            "暴雨到大暴雨", "大暴雨到特大暴雨", "小到中雪", "中到大雪", "大到暴雪", "浮尘", "扬沙", "强沙尘暴"};

    private ChinaWeatherJson() {}

    static Object assignment(String body, String variable) throws JSONException {
        Matcher match = Pattern.compile("\\bvar\\s+" + Pattern.quote(variable) + "\\s*=\\s*").matcher(body);
        if (!match.find()) throw new JSONException("缺少 " + variable);
        return new JSONTokener(body.substring(match.end())).nextValue();
    }

    public static JSONObject current(String body, String station, String city, long now) throws JSONException {
        boolean domestic = station.startsWith("101");
        Object value = assignment(body, domestic ? "dataSK" : "obs_forg");
        if (!(value instanceof JSONObject)) throw new JSONException("天气实况不是 JSON 对象");
        JSONObject sk = domestic ? (JSONObject) value : ((JSONObject) value).getJSONObject("o");
        if (domestic && !station.equals(sk.optString("city"))) throw new JSONException("天气站号不匹配");
        Double temperature = number(sk.optString("temp"));
        if (temperature == null) throw new JSONException("天气实况缺少温度");
        JSONObject current = new JSONObject();
        current.put("temperature", temperature);
        current.put("humidity", number(sk.optString("SD").replace("%", "")));
        current.put("wind_direction_label", sk.optString("WD", "--"));
        String level = sk.optString("WS");
        // Ranges and “<3级” are not an exact observed wind level.
        if (level.matches("[0-9]+级?")) current.put("wind_level", Integer.parseInt(level.replace("级", "")));
        String speed = sk.optString("wse");
        if (speed.endsWith("km/h")) {
            Double kmh = number(speed.substring(0, speed.length() - 4));
            if (kmh != null) current.put("wind_ms", kmh / 3.6);
        }
        current.put("code", wmoCode(sk.optString("weathercode")));
        current.put("label", sk.optString("weather", "--"));
        // Foreign observations provide only a clock time, so their date is unknown.
        String observed = domestic ? observationTime(sk.optString("date"), sk.optString("time"), now) : null;
        current.put("observation_time", observed);
        JSONObject root = new JSONObject();
        root.put("schema", DashboardJson.SCHEMA_ID);
        root.put("date", format(now, "yyyy-MM-dd"));
        root.put("city", domestic ? sk.optString("cityname", city) : city);
        root.put("timezone", "Asia/Shanghai");
        root.put("generated_at", format(now, "yyyy-MM-dd'T'HH:mm:ssZ"));
        root.put("weather_updated_at", observed);
        root.put("weather_source", "中国天气网（" + station + "）");
        root.put("current", current);
        JSONObject air = new JSONObject();
        // aqi_pm25 is not verified to be a concentration; never display it as µg/m³.
        air.put("aqi", number(sk.optString("aqi")));
        air.put("aqi_system", "CN");
        root.put("air", air);
        root.put("air_source", domestic ? "中国天气网（AQI，未提供独立观测时间）" : "--");
        return root;
    }

    public static void forecast(JSONObject root, String body, long now) throws JSONException {
        Object value = assignment(body, "fc40");
        if (!(value instanceof JSONArray)) throw new JSONException("预报不是 JSON 数组");
        JSONArray rows = (JSONArray) value, forecast = new JSONArray();
        String today = format(now, "yyyy-MM-dd"), end = DashboardJson.addDays(today, 7);
        String tomorrow = DashboardJson.addDays(today, 1);
        boolean hasTomorrow = false;
        JSONObject current = root.getJSONObject("current");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String rawDate = row.optString("009");
            if (!rawDate.matches("[0-9]{8}")) continue;
            String day = rawDate.substring(0, 4) + "-" + rawDate.substring(4, 6) + "-" + rawDate.substring(6);
            if (!DashboardJson.isIsoDay(day)) continue;
            if (today.equals(day)) {
                current.put("high", number(row.optString("003")));
                current.put("low", number(row.optString("004")));
                current.put("sunrise", row.optString("014", null));
                current.put("sunset", row.optString("015", null));
            }
            if (day.compareTo(today) <= 0 || day.compareTo(end) > 0) continue;
            if (tomorrow.equals(day)) hasTomorrow = true;
            JSONObject item = new JSONObject();
            String daytime = row.optString("001"), nighttime = row.optString("002");
            String dayLabel = label(daytime), nightLabel = label(nighttime);
            item.put("date", day);
            item.put("high", number(row.optString("003")));
            item.put("low", number(row.optString("004")));
            item.put("code", wmoCode(daytime.isEmpty() ? nighttime : daytime));
            item.put("label", daytime.isEmpty() || dayLabel.equals(nightLabel)
                    ? nightLabel : dayLabel + "转" + nightLabel);
            forecast.put(item);
        }
        // Anchor the protocol parser to tomorrow even if the upstream omits that day.
        if (forecast.length() == 0) throw new JSONException("没有未来七天的预报");
        if (forecast.length() < 7) root.put("weather_error", "部分日期预报缺失");
        if (!hasTomorrow) forecast.put(new JSONObject().put("date", tomorrow));
        root.put("forecast", forecast);
    }

    private static Double number(String text) {
        if (!text.matches("-?[0-9]+(?:\\.[0-9]+)?")) return null;
        try {
            double value = Double.parseDouble(text);
            return Double.isInfinite(value) || Double.isNaN(value) ? null : value;
        } catch (NumberFormatException e) { return null; }
    }

    private static String format(long time, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(ZONE);
        return format.format(new Date(time));
    }

    private static String observationTime(String date, String time, long now) {
        Matcher match = Pattern.compile("^([0-9]{1,2})月([0-9]{1,2})日").matcher(date);
        if (!match.find() || !time.matches("[0-9]{2}:[0-9]{2}")) return null;
        Calendar calendar = Calendar.getInstance(ZONE);
        calendar.setTimeInMillis(now);
        int year = calendar.get(Calendar.YEAR);
        Long closest = null;
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-M-d HH:mm", Locale.US);
        parser.setTimeZone(ZONE); parser.setLenient(false);
        for (int y = year - 1; y <= year + 1; y++) {
            String text = y + "-" + match.group(1) + "-" + match.group(2) + " " + time;
            ParsePosition position = new ParsePosition(0);
            Date parsed = parser.parse(text, position);
            if (parsed != null && position.getIndex() == text.length()
                    && (closest == null || Math.abs(parsed.getTime() - now) < Math.abs(closest - now))) closest = parsed.getTime();
        }
        return closest == null ? null : format(closest, "yyyy-MM-dd'T'HH:mm:ssZ");
    }

    private static int weatherCode(String text) {
        String code = text.replaceFirst("^[dn]", "");
        try { return code.matches("[0-9]{1,3}") ? Integer.parseInt(code) : -1; }
        catch (NumberFormatException e) { return -1; }
    }

    private static String label(String text) {
        int code = weatherCode(text);
        if (code >= 0 && code < LABELS.length) return LABELS[code];
        switch (code) {
            case 32: return "浓雾";
            case 49: return "强浓雾";
            case 53: return "霾";
            case 54: return "中度霾";
            case 55: return "重度霾";
            case 56: return "严重霾";
            case 57: return "大雾";
            case 58: return "特强浓雾";
            case 301: return "雨";
            case 302: return "雪";
            default: return "--";
        }
    }

    // Adapt CMA codes to the WMO codes consumed by DashboardCanvasView's icons.
    static Integer wmoCode(String text) {
        switch (weatherCode(text)) {
            case 0: return 0;
            case 1: return 2;
            case 2: return 3;
            case 3: return 80;
            case 4: return 95;
            case 5: return 99;
            case 6: return 68;
            case 7: case 21: case 301: return 61;
            case 8: case 22: return 63;
            case 9: case 10: case 11: case 12: case 23: case 24: case 25: return 65;
            case 13: return 85;
            case 14: case 26: case 302: return 71;
            case 15: case 27: return 73;
            case 16: case 17: case 28: return 75;
            case 18: case 32: case 49: case 53: case 54: case 55: case 56: case 57: case 58: return 45;
            case 19: return 66;
            default: return null;
        }
    }
}
