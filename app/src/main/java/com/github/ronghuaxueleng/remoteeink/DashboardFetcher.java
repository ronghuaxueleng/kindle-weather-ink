package com.github.ronghuaxueleng.remoteeink;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.SSLCertificateSocketFactory;
import android.os.AsyncTask;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

@SuppressWarnings("deprecation")
public final class DashboardFetcher extends AsyncTask<Void, Void, DashboardFetcher.Result> {
    private static final int MAX_BODY_BYTES = 1024 * 1024;
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
    private volatile HttpURLConnection activeConnection;
    private SSLSocketFactory socketFactory;

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
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            activeConnection = connection;
            if (isCancelled()) throw new IOException("已取消");
            if (Build.VERSION.SDK_INT < 20 && connection instanceof HttpsURLConnection) {
                if (socketFactory == null) socketFactory = tls12SocketFactory();
                ((HttpsURLConnection) connection).setSSLSocketFactory(socketFactory);
            }
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json, text/javascript, */*");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) KindleWeatherInk");
            if ("https".equals(connection.getURL().getProtocol()) && weatherHost.equals(connection.getURL().getHost())) {
                connection.setRequestProperty("X-QW-Api-Key", apiKey);
                // Don't forward the credential if the service redirects to another host.
                connection.setInstanceFollowRedirects(false);
            }
            connection.setRequestProperty("Accept-Encoding", "gzip");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException(errorText(code));
            if (connection.getContentLength() > MAX_BODY_BYTES) throw new IOException("服务器响应过大");
            try (InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                        ? new GZIPInputStream(connection.getInputStream()) : connection.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (isCancelled()) throw new IOException("已取消");
                    if (bytes.size() + count > MAX_BODY_BYTES) throw new IOException("服务器响应过大");
                    bytes.write(buffer, 0, count);
                }
                return new String(bytes.toByteArray(), "UTF-8");
            }
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
    private SSLSocketFactory tls12SocketFactory() throws GeneralSecurityException, IOException {
        TrustManagerFactory systemFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        systemFactory.init((KeyStore) null);
        X509TrustManager systemTrust = (X509TrustManager) systemFactory.getTrustManagers()[0];
        KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
        roots.load(null, null);
        // Official CA: https://letsencrypt.org/certs/isrg-root-x2.pem (expires 2040-09-17).
        try (InputStream input = context.getResources().openRawResource(R.raw.isrg_root_x2)) {
            roots.setCertificateEntry("isrg-root-x2", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        // Official CA: https://cacerts.digicert.com/DigiCertGlobalRootG2.crt (expires 2038-01-15).
        try (InputStream input = context.getResources().openRawResource(R.raw.digicert_global_root_g2)) {
            roots.setCertificateEntry("digicert-global-root-g2", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        TrustManagerFactory extraFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        extraFactory.init(roots);
        X509TrustManager extraTrust = (X509TrustManager) extraFactory.getTrustManagers()[0];
        X509TrustManager trust = new X509TrustManager() {
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try { systemTrust.checkServerTrusted(chain, authType); }
                catch (CertificateException e) { extraTrust.checkServerTrusted(chain, authType); }
            }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                systemTrust.checkClientTrusted(chain, authType);
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] system = systemTrust.getAcceptedIssuers();
                X509Certificate[] extra = extraTrust.getAcceptedIssuers();
                X509Certificate[] all = Arrays.copyOf(system, system.length + extra.length);
                System.arraycopy(extra, 0, all, system.length, extra.length);
                return all;
            }
        };
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] {trust}, null);
        return new Tls12SocketFactory(tls.getSocketFactory());
    }

    private static final class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket enableTls12(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                // Old OpenSSL can advertise TLS 1.0 when TLS 1.2 is added with TLS 1.1 disabled.
                ssl.setEnabledProtocols(new String[] {"TLSv1.2"});
            }
            return socket;
        }

        private Socket configure(Socket socket, String host) {
            // API 17-19's platform TLS sockets expose SNI through this public Android API.
            new SSLCertificateSocketFactory(15000).setHostname(socket, host);
            return enableTls12(socket);
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return enableTls12(delegate.createSocket()); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return configure(delegate.createSocket(socket, host, port, autoClose), host);
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return configure(delegate.createSocket(host, port), host);
        }
        @Override public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
            return configure(delegate.createSocket(host, port, localAddress, localPort), host);
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return enableTls12(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress localAddress, int localPort) throws IOException {
            return enableTls12(delegate.createSocket(host, port, localAddress, localPort));
        }
    }

    @Override protected void onPostExecute(Result result) {
        if (!isCancelled() && listener != null) listener.onDashboardResult(result);
    }
}
