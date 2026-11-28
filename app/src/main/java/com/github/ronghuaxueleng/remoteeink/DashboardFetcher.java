package com.github.ronghuaxueleng.remoteeink;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public final class DashboardFetcher extends AsyncTask<Void, Void, DashboardFetcher.Result> {
    private static final String QUOTE_ENDPOINT = "https://v1.hitokoto.cn/?c=d&c=i&c=k&min_length=8&max_length=36";
    public static final Map<String, String> CITY_TO_SERVER_CITY;
    static {
        Map<String, String> cities = new LinkedHashMap<>();
        String[] ids = {"beijing", "shanghai", "guangzhou", "shenzhen", "hangzhou", "chengdu",
                "wuhan", "taian", "yantai", "weihai", "xinjiapo", "xinwen"};
        String[] names = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "泰安", "烟台", "威海", "新加坡", "新汶"};
        for (int i = 0; i < ids.length; i++) cities.put(ids[i], names[i]);
        cities.put("xinwen", "新汶（新泰市预报）");
        cities.put("beijing_tongzhou_langqingyuan", "北京市通州区朗清园（通州区预报）");
        CITY_TO_SERVER_CITY = Collections.unmodifiableMap(cities);
    }
    public interface Listener { void onDashboardResult(Result result); }
    public static final class Result {
        public final String json, error, notice;
        public final int statusCode;
        public final DashboardJson.DashboardData data;
        public Result(String json, String error, int statusCode) {
            this(json, error, statusCode, null, null);
        }
        private Result(String json, String error, int statusCode, DashboardJson.DashboardData data, String notice) {
            this.json = json; this.error = error; this.statusCode = statusCode; this.data = data; this.notice = notice;
        }
        public boolean ok() { return json != null && error == null; }
    }
    private final String baseUrl, cityId, weatherHost, apiKey;
    private final Context context;
    private final Listener listener;
    private final JsonHttpClient http;

    public DashboardFetcher(Context context, String baseUrl, String cityId, Listener listener) {
        this(context, baseUrl, cityId,
                context.getSharedPreferences("dashboard", Context.MODE_PRIVATE).getString("qweather_host", ""),
                context.getSharedPreferences("dashboard", Context.MODE_PRIVATE).getString("qweather_api_key", ""), listener);
    }
    public DashboardFetcher(Context context, String baseUrl, String cityId, String weatherHost, String apiKey, Listener listener) {
        this.context = context.getApplicationContext();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.cityId = cityId;
        this.weatherHost = QWeatherJson.normalizeHost(weatherHost);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = new JsonHttpClient(this.context, this.weatherHost, this.apiKey);
        this.listener = listener;
    }
    public static String serverCityName(String cityId) {
        // Only almanac is taken from this service; its weather is never merged.
        if ("beijing_tongzhou_langqingyuan".equals(cityId)) return "北京";
        if ("xinwen".equals(cityId)) return "新汶";
        String name = CITY_TO_SERVER_CITY.get(cityId);
        return name == null ? "北京" : name;
    }
    public static boolean validBaseUrl(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        try {
            URL url = new URL(text.trim());
            return ("http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol()))
                    && !url.getHost().isEmpty() && url.getUserInfo() == null
                    && url.getQuery() == null && url.getRef() == null;
        } catch (Exception e) { return false; }
    }
    public String buildEndpoint() {
        if (!validBaseUrl(baseUrl)) return null;
        try {
            String base = baseUrl;
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            return base + "/v1/dashboard?city=" + URLEncoder.encode(serverCityName(cityId), "UTF-8");
        } catch (Exception e) { return null; }
    }
    @Override protected Result doInBackground(Void... ignored) {
        try {
            if (!QWeatherJson.validHost(weatherHost) || apiKey.isEmpty()) throw new IOException("请先在设置中填写和风天气 API Host 和 API Key");
            String cityName = CITY_TO_SERVER_CITY.get(cityId);
            if (cityName == null) cityName = serverCityName(cityId);
            JSONObject root = QWeatherJson.current(fetch(QWeatherJson.endpoint(weatherHost, cityId, "now"), 15000),
                    cityName, System.currentTimeMillis());
            String notice = null;
            try {
                QWeatherJson.forecast(root, fetch(QWeatherJson.endpoint(weatherHost, cityId, "10d"), 15000));
                if (root.has("weather_error")) notice = "部分日期预报缺失";
            } catch (Exception e) {
                if (isCancelled()) throw e;
                root.put("weather_error", "预报获取失败：" + e.getMessage());
                notice = "预报获取失败";
            }
            try {
                QWeatherJson.air(root, fetch(QWeatherJson.endpoint(weatherHost, cityId, "air"), 15000));
            } catch (Exception e) {
                if (isCancelled()) throw e;
                root.put("air_error", "和风空气质量获取失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
                notice = notice == null ? "空气质量获取失败" : notice + "，空气质量获取失败";
            }
            try {
                String endpoint = buildEndpoint();
                if (endpoint == null) throw new IOException("黄历服务地址无效");
                String body = fetch(endpoint, 8000);
                DashboardJson.parse(body);
                JSONObject server = new JSONObject(body);
                if (!root.getString("date").equals(server.optString("date"))
                        || server.optJSONObject("almanac") == null) throw new IOException("缺少当日黄历");
                root.put("almanac", server.getJSONObject("almanac"));
            } catch (Exception e) {
                if (isCancelled()) throw e;
                root.put("almanac_error", e.getMessage());
                notice = notice == null ? "黄历获取失败" : notice + "，黄历获取失败";
            }
            SharedPreferences quotes = context.getSharedPreferences("hitokoto", Context.MODE_PRIVATE);
            String quoteBody = quotes.getString("last_quote", null);
            try {
                String body = fetch(QUOTE_ENDPOINT, 8000);
                JSONObject quote = new JSONObject(body);
                if (!(quote.opt("hitokoto") instanceof String)
                        || quote.getString("hitokoto").trim().isEmpty()) throw new IOException("句子正文为空");
                quoteBody = body;
                quotes.edit().putString("last_quote", body).apply();
            } catch (Exception e) {
                if (isCancelled()) throw e;
                root.put("quote_error", e.getClass().getSimpleName() + " " + e.getMessage());
                String message = quoteBody == null ? "句子获取失败" : "句子获取失败，保留上次内容";
                notice = notice == null ? message : notice + "，" + message;
            }
            if (quoteBody != null) root.put("quote", new JSONObject(quoteBody));
            if (isCancelled()) return new Result(null, "已取消", 0);
            String json = root.toString();
            return new Result(json, null, 200, DashboardJson.parse(json), notice);
        } catch (SocketTimeoutException e) {
            return new Result(null, "天气请求超时", 0);
        } catch (Exception e) {
            return new Result(null, "天气获取失败：" + e.getClass().getSimpleName() + " " + e.getMessage(), 0);
        }
    }
    private String fetch(String endpoint, int timeout) throws Exception {
        return http.fetch(endpoint, timeout);
    }
    public void cancelRequest() {
        cancel(true);
        http.cancel();
    }

    @Override protected void onPostExecute(Result result) {
        if (!isCancelled() && listener != null) listener.onDashboardResult(result);
    }
}
