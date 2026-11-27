package com.github.ronghuaxueleng.remoteeink;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** MSN exposes wxforecast air data through its weather overview response. */
public final class MsnWeatherJson {
    // Public web-client key from MSN's weather page metadata request.
    private static final String WEB_CLIENT_KEY = "j5i4gDqHL6nGYwx5wi5kRhXjtf2c5qgFX9fzfk0TOo";
    private static final Map<String, String> COORDINATES;
    static {
        Map<String, String> locations = new LinkedHashMap<>();
        // Verified with MSN weather/locations/search; Tongzhou is the user's MSN location.
        String[] ids = {"beijing", "shanghai", "guangzhou", "shenzhen", "hangzhou", "chengdu",
                "wuhan", "taian", "yantai", "weihai", "xinjiapo", "xinwen", "beijing_tongzhou_langqingyuan"};
        String[] coordinates = {"39.907,116.397", "31.23,121.473", "23.129,113.264", "22.543,114.057",
                "30.245,120.21", "30.572,104.066", "30.592,114.305", "36.2,117.086", "37.464,121.447",
                "37.513,122.12", "1.289,103.851", "35.909,117.768", "39.89799880981445,116.68499755859375"};
        for (int i = 0; i < ids.length; i++) locations.put(ids[i], coordinates[i]);
        COORDINATES = Collections.unmodifiableMap(locations);
    }

    private MsnWeatherJson() {}

    public static String endpoint(String cityId) {
        String coordinates = COORDINATES.get(cityId);
        if (coordinates == null) coordinates = COORDINATES.get("beijing");
        String[] parts = coordinates.split(",");
        return "https://assets.msn.cn/service/weather/overview?apikey=" + WEB_CLIENT_KEY
                + "&ocid=msftweather&market=zh-cn&locale=zh-cn&units=C&wrapodata=false&days=1"
                + "&region=" + ("xinjiapo".equals(cityId) ? "SG" : "CN")
                + "&lat=" + parts[0] + "&lon=" + parts[1];
    }

    public static void merge(JSONObject root, String body, String cityId) throws JSONException {
        JSONObject response = new JSONObject(body);
        JSONObject result = response.getJSONArray("responses").getJSONObject(0);
        // Check the requested position, since MSN may otherwise use an IP-based default location.
        String selected = COORDINATES.get(cityId);
        if (selected == null) selected = COORDINATES.get("beijing");
        String[] expected = selected.split(",");
        JSONObject used = response.getJSONObject("userProfile").getJSONObject("used").getJSONObject("geoCoordinates");
        if (Math.abs(used.getDouble("latitude") - Double.parseDouble(expected[0])) > 0.01
                || Math.abs(used.getDouble("longitude") - Double.parseDouble(expected[1])) > 0.01) {
            throw new JSONException("MSN 返回的地点不匹配");
        }
        JSONObject weather = result.getJSONArray("weather").getJSONObject(0);
        String provider = weather.getJSONObject("aqiProvider").getString("name");
        JSONObject current = weather.getJSONObject("current");
        Object aqi = current.opt("aqi");
        if (!(aqi instanceof Number)) throw new JSONException("MSN 缺少 AQI");
        JSONObject air = new JSONObject();
        air.put("aqi", aqi);
        // Preserve the provider's scale and wording; do not assume China's AQI scale.
        air.put("aqi_system", "MSN");
        air.put("aqi_label", current.optString("aqiSeverity", "--"));
        air.put("primary_pollutant", current.optString("primaryPollutant", "--"));
        String observed = current.optString("aqiValidTime", null);
        air.put("observation_time", observed);
        root.put("air", air);
        root.put("air_updated_at", observed);
        root.put("air_source", "MSN / " + provider);
        JSONObject dashboardCurrent = root.getJSONObject("current");
        Object feels = current.opt("feels");
        if (feels instanceof Number && "°C".equals(response.getJSONObject("units").optString("temperature"))) {
            dashboardCurrent.put("feels_like", feels);
            dashboardCurrent.put("feels_like_source", "MSN");
            dashboardCurrent.put("feels_like_observation_time", current.optString("created", null));
        }
    }
}
