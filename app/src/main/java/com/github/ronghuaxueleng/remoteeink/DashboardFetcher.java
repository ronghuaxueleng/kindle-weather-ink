package com.github.ronghuaxueleng.remoteeink;

import android.os.AsyncTask;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@SuppressWarnings("deprecation")
public final class DashboardFetcher extends AsyncTask<Void, Void, DashboardFetcher.Result> {
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    public static final Map<String, String> CITY_TO_SERVER_CITY;
    static {
        Map<String, String> cities = new LinkedHashMap<>();
        String[] ids = {"beijing", "shanghai", "guangzhou", "shenzhen", "hangzhou", "chengdu",
                "wuhan", "taian", "yantai", "weihai", "xinjiapo", "xinwen"};
        String[] names = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "泰安", "烟台", "威海", "新加坡", "新汶"};
        for (int i = 0; i < ids.length; i++) cities.put(ids[i], names[i]);
        cities.put("beijing_tongzhou_langqingyuan", "北京市通州区朗清园（北京参考）");
        CITY_TO_SERVER_CITY = Collections.unmodifiableMap(cities);
    }
    public interface Listener { void onDashboardResult(Result result); }
    public static final class Result {
        public final String json, error;
        public final int statusCode;
        public final DashboardJson.DashboardData data;
        public Result(String json, String error, int statusCode) {
            this(json, error, statusCode, null);
        }
        private Result(String json, String error, int statusCode, DashboardJson.DashboardData data) {
            this.json = json; this.error = error; this.statusCode = statusCode; this.data = data;
        }
        public boolean ok() { return json != null && error == null; }
    }
    private final String baseUrl, cityId;
    private final Listener listener;
    private volatile HttpURLConnection activeConnection;

    public DashboardFetcher(String baseUrl, String cityId, Listener listener) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.cityId = cityId;
        this.listener = listener;
    }
    public static String serverCityName(String cityId) {
        // The service supports Beijing city data, not a verified neighborhood forecast.
        if ("beijing_tongzhou_langqingyuan".equals(cityId)) return "北京";
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
        String endpoint = buildEndpoint();
        if (endpoint == null) return new Result(null, "服务器地址无效", 0);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            activeConnection = connection;
            if (isCancelled()) return new Result(null, "已取消", 0);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) return new Result(null, errorText(code), code);
            if (connection.getContentLength() > MAX_BODY_BYTES) return new Result(null, "服务器响应过大", code);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (isCancelled()) return new Result(null, "已取消", 0);
                    if (bytes.size() + count > MAX_BODY_BYTES) return new Result(null, "服务器响应过大", code);
                    bytes.write(buffer, 0, count);
                }
                String json = new String(bytes.toByteArray(), "UTF-8");
                DashboardJson.DashboardData data = DashboardJson.parse(json);
                return new Result(json, null, code, data);
            }
        } catch (SocketTimeoutException e) {
            return new Result(null, "连接或读取超时", 0);
        } catch (IllegalArgumentException e) {
            return new Result(null, "JSON 格式错误：" + e.getMessage(), 0);
        } catch (Exception e) {
            return new Result(null, "无法连接服务器（" + e.getClass().getSimpleName() + "）", 0);
        } finally {
            activeConnection = null;
            if (connection != null) connection.disconnect();
        }
    }
    public void cancelRequest() {
        cancel(true);
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }
    private static String errorText(int code) {
        if (code == 401 || code == 403) return "服务器拒绝访问（HTTP " + code + "）";
        if (code == 404) return "接口不存在（HTTP 404）";
        return "服务器返回 HTTP " + code;
    }
    @Override protected void onPostExecute(Result result) {
        if (!isCancelled() && listener != null) listener.onDashboardResult(result);
    }
}
