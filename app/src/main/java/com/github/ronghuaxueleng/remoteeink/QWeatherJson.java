package com.github.ronghuaxueleng.remoteeink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Adapts QWeather's city weather and air quality APIs to the dashboard protocol. */
public final class QWeatherJson {
    private static final Map<String, String> COORDINATES;
    static {
        Map<String, String> locations = new LinkedHashMap<>();
        // Preserve the coordinates previously verified with MSN's location search.
        String[] ids = {"beijing", "shanghai", "guangzhou", "shenzhen", "hangzhou", "chengdu",
                "wuhan", "taian", "yantai", "weihai", "xinjiapo", "xinwen", "beijing_tongzhou_langqingyuan"};
        String[] coordinates = {"39.907,116.397", "31.23,121.473", "23.129,113.264", "22.543,114.057",
                "30.245,120.21", "30.572,104.066", "30.592,114.305", "36.2,117.086", "37.464,121.447",
                "37.513,122.12", "1.289,103.851", "35.909,117.768", "39.89799880981445,116.68499755859375"};
        for (int i = 0; i < ids.length; i++) locations.put(ids[i], coordinates[i]);
        COORDINATES = Collections.unmodifiableMap(locations);
    }

    private QWeatherJson() {}

    public static String normalizeHost(String value) {
        String host = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (host.startsWith("https://")) host = host.substring(8);
        if (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        return host;
    }

    public static boolean validHost(String value) {
        return normalizeHost(value).matches("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+qweatherapi\\.com");
    }

    public static String endpoint(String host, String cityId, String product) {
        if (!validHost(host)) throw new IllegalArgumentException("请先设置和风天气 API Host");
        String coordinates = COORDINATES.get(cityId);
        if (coordinates == null) coordinates = COORDINATES.get("beijing");
        String[] parts = coordinates.split(","); // Stored as latitude, longitude.
        String base = "https://" + normalizeHost(host);
        if ("air".equals(product)) {
            // This API accepts at most two decimal places, rounded from the selected position.
            return base + String.format(Locale.US, "/airquality/v1/current/%.2f/%.2f?lang=zh",
                    Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        }
        if (!"now".equals(product) && !"10d".equals(product)) throw new IllegalArgumentException("未知天气产品");
        return base + "/v7/weather/" + product + String.format(Locale.US, "?location=%.2f,%.2f&lang=zh&unit=m",
                Double.parseDouble(parts[1]), Double.parseDouble(parts[0]));
    }

    private static JSONObject weatherResponse(String body) throws JSONException {
        JSONObject response = new JSONObject(body);
        String code = response.optString("code");
        if (!"200".equals(code)) throw new JSONException("和风天气返回状态 " + code);
        return response;
    }

    public static JSONObject current(String body, String city, long now) throws JSONException {
        JSONObject response = weatherResponse(body), source = response.getJSONObject("now");
        Double temperature = number(source.opt("temp"));
        if (temperature == null) throw new JSONException("和风天气实况缺少温度");
        JSONObject current = new JSONObject();
        current.put("temperature", temperature);
        current.put("feels_like", number(source.opt("feelsLike")));
        current.put("feels_like_source", "和风天气");
        current.put("feels_like_observation_time", source.optString("obsTime", null));
        current.put("humidity", number(source.opt("humidity")));
        current.put("wind_direction", number(source.opt("wind360")));
        current.put("wind_direction_label", source.optString("windDir", "--"));
        current.put("wind_level", windLevel(source.optString("windScale")));
        Double speed = number(source.opt("windSpeed"));
        if (speed != null) current.put("wind_ms", speed / 3.6);
        current.put("code", wmoCode(source.optString("icon")));
        current.put("label", source.optString("text", "--"));
        current.put("observation_time", source.optString("obsTime", null));
        JSONObject root = new JSONObject();
        root.put("schema", DashboardJson.SCHEMA_ID);
        root.put("date", format(now, "yyyy-MM-dd"));
        root.put("city", city);
        root.put("timezone", "Asia/Shanghai");
        root.put("generated_at", format(now, "yyyy-MM-dd'T'HH:mm:ssZ"));
        root.put("weather_updated_at", response.optString("updateTime", null));
        root.put("weather_source", "和风天气");
        root.put("current", current);
        return root;
    }

    public static void forecast(JSONObject root, String body) throws JSONException {
        JSONArray rows = weatherResponse(body).getJSONArray("daily"), forecast = new JSONArray();
        String today = root.getString("date"), tomorrow = DashboardJson.addDays(today, 1);
        String last = DashboardJson.addDays(today, DashboardJson.FORECAST_DAYS);
        Map<String, JSONObject> byDate = new LinkedHashMap<>();
        JSONObject current = root.getJSONObject("current");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String day = row.optString("fxDate");
            if (!DashboardJson.isIsoDay(day)) continue;
            if (today.equals(day)) {
                current.put("high", number(row.opt("tempMax")));
                current.put("low", number(row.opt("tempMin")));
                current.put("sunrise", row.optString("sunrise", null));
                current.put("sunset", row.optString("sunset", null));
                current.put("uv_index", number(row.opt("uvIndex")));
            }
            if (day.compareTo(tomorrow) < 0 || day.compareTo(last) > 0) continue;
            JSONObject item = new JSONObject();
            item.put("date", day);
            item.put("high", number(row.opt("tempMax")));
            item.put("low", number(row.opt("tempMin")));
            item.put("code", wmoCode(row.optString("iconDay")));
            String dayLabel = row.optString("textDay", "--"), nightLabel = row.optString("textNight", dayLabel);
            item.put("label", dayLabel.equals(nightLabel) ? dayLabel : dayLabel + "转" + nightLabel);
            item.put("wind_level", windLevel(row.optString("windScaleDay")));
            Double speed = number(row.opt("windSpeedDay"));
            if (speed != null) item.put("wind_ms", speed / 3.6);
            byDate.put(day, item);
        }
        if (byDate.isEmpty()) throw new JSONException("和风天气没有未来七天预报");
        // Include missing dates explicitly, so the parser always starts at tomorrow.
        for (int i = 1; i <= DashboardJson.FORECAST_DAYS; i++) {
            String day = DashboardJson.addDays(today, i);
            JSONObject item = byDate.get(day);
            forecast.put(item == null ? new JSONObject().put("date", day) : item);
        }
        root.put("forecast", forecast);
        if (byDate.size() < DashboardJson.FORECAST_DAYS) root.put("weather_error", "部分日期预报缺失");
    }

    public static void air(JSONObject root, String body) throws JSONException {
        JSONObject response = new JSONObject(body);
        JSONArray indexes = response.getJSONArray("indexes"), pollutants = response.getJSONArray("pollutants");
        if (indexes.length() == 0) throw new JSONException("和风天气缺少空气质量指数");
        JSONObject index = indexes.getJSONObject(0);
        for (int i = 0; i < indexes.length(); i++) {
            JSONObject candidate = indexes.getJSONObject(i);
            if ("cn-mee".equals(candidate.optString("code"))) { index = candidate; break; }
        }
        Double aqi = number(index.opt("aqi"));
        if (aqi == null) throw new JSONException("和风天气缺少空气质量数值");
        JSONObject air = new JSONObject();
        air.put("aqi", aqi);
        String system = index.getString("code");
        air.put("aqi_system", "cn-mee".equals(system) ? "CN" : system);
        air.put("aqi_name", index.getString("name"));
        air.put("aqi_label", index.optString("category", "--"));
        JSONObject primary = index.optJSONObject("primaryPollutant");
        if (primary != null) air.put("primary_pollutant", primary.optString("name", "--"));
        for (int i = 0; i < pollutants.length(); i++) {
            JSONObject pollutant = pollutants.getJSONObject(i);
            JSONObject concentration = pollutant.optJSONObject("concentration");
            if (concentration == null) continue;
            Double value = number(concentration.opt("value"));
            String unit = concentration.optString("unit");
            if ("pm2p5".equals(pollutant.optString("code")) && value != null
                    && ("μg/m³".equals(unit) || "μg/m3".equals(unit) || "µg/m³".equals(unit) || "µg/m3".equals(unit))) {
                air.put("pm2_5", value);
            }
            if (primary != null && primary.optString("code").equals(pollutant.optString("code")) && value != null) {
                air.put("primary_pollutant", pollutant.optString("name", "--") + " " + value + " " + unit);
            }
        }
        // The current air API has no observation timestamp; don't substitute the fetch time.
        root.put("air", air);
        root.put("air_source", "和风天气");
    }

    private static String format(long millis, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(millis));
    }

    private static Double number(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        try {
            double result = Double.parseDouble(value.toString());
            return Double.isNaN(result) || Double.isInfinite(result) ? null : result;
        } catch (NumberFormatException e) { return null; }
    }

    private static Integer windLevel(String value) {
        // A forecast range is not an exact wind level; the speed remains available.
        return value.matches("[0-9]{1,2}") ? Integer.valueOf(value) : null;
    }

    // Official condition table: qwd/dev-site/assets/table/weather-conditions.csv.
    // QWeather condition IDs -> WMO IDs understood by the existing Canvas icons.
    private static Integer wmoCode(String value) {
        int code;
        try { code = Integer.parseInt(value); }
        catch (NumberFormatException e) { return null; }
        switch (code) {
            case 100: case 150: return 0;
            case 102: return 1;
            case 101: case 103: case 151: case 152: case 153: return 2;
            case 104: return 3;
            case 300: case 350: return 80;
            case 301: case 351: return 82;
            case 302: case 303: return 95;
            case 304: return 99;
            case 305: case 309: case 314: case 399: return 61;
            case 306: case 315: return 63;
            case 307: case 308: case 310: case 311: case 312: case 316: case 317: case 318: return 65;
            case 313: return 66;
            case 400: case 408: case 499: return 71;
            case 401: case 409: return 73;
            case 402: case 403: case 410: return 75;
            case 404: case 405: case 406: case 456: return 68;
            case 407: case 457: return 85;
            case 500: case 501: case 502: case 509: case 510: case 511: case 512: case 513:
            case 514: case 515: return 45;
            default: return null;
        }
    }
}
