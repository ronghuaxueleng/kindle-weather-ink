package com.github.ronghuaxueleng.remoteeink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final String DEFAULT_BASE_URL = "https://kindle.pptxgen.com";
    private static final int DEFAULT_INTERVAL = 1800;
    private static final int MAX_INTERVAL = 604800;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final List<DashboardFetcher> testRequests = new ArrayList<>();
    private SharedPreferences prefs;
    private DashboardCache cache;
    private DashboardCanvasView view;
    private DashboardFetcher request;
    private boolean active, receiverRegistered;
    private int generation, minuteCount;
    private final Runnable poll = this::refresh;
    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            view.setStaleFlags(); view.invalidate();
            if (DashboardLayout.FullRefreshPolicy.shouldFullRefreshOnMinuteTick(++minuteCount)) view.requestFullRefresh();
            scheduleMinuteTick();
        }
    };
    private final BroadcastReceiver deviceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateDeviceStatus(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        fullscreen();
        prefs = getSharedPreferences("dashboard", MODE_PRIVATE);
        cache = new DashboardCache(this);
        view = new DashboardCanvasView(this);
        setContentView(view);
        view.setActionListener(new DashboardCanvasView.ActionListener() {
            @Override public void onCityClick() { showCities(); }
            @Override public void onSettingsClick() { showSettings(); }
            @Override public void onWeatherClick() { showWeatherDetails(); }
            @Override public void onThemeClick() { showThemes(); }
        });
        view.setOnLongClickListener(v -> { showSettings(); return true; });
        loadDashboard();
        view.requestFullRefresh();
    }
    @Override protected void onResume() {
        super.onResume(); active = true; fullscreen();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(deviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(deviceReceiver, filter);
        receiverRegistered = true;
        updateDeviceStatus(); refresh();
        handler.removeCallbacks(minuteTick); scheduleMinuteTick();
    }
    @Override protected void onPause() {
        active = false;
        stopRefresh();
        handler.removeCallbacks(minuteTick);
        for (DashboardFetcher test : testRequests) test.cancelRequest();
        testRequests.clear();
        if (receiverRegistered) { unregisterReceiver(deviceReceiver); receiverRegistered = false; }
        super.onPause();
    }
    @Override protected void onDestroy() {
        disk.shutdown(); super.onDestroy();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); fullscreen(); view.invalidate();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus); if (focus) fullscreen();
    }
    private void fullscreen() {
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 19) flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }
    private String city() { return prefs.getString("selected_city", "beijing"); }
    private String baseUrl() {
        String saved = prefs.getString("json_base_url", DEFAULT_BASE_URL);
        return saved == null || saved.trim().isEmpty() ? DEFAULT_BASE_URL : saved.trim();
    }
    private boolean demoMode() { return prefs.getBoolean("demo_mode", false); }
    private String weatherHost() { return QWeatherJson.normalizeHost(prefs.getString("qweather_host", "")); }
    private String weatherKey() { return prefs.getString("qweather_api_key", ""); }
    private String cacheId() { return "qweather/1|" + weatherHost() + "|" + baseUrl() + "|" + city(); }
    private long intervalMillis() {
        return Math.max(60, Math.min(MAX_INTERVAL, prefs.getInt("interval_seconds", DEFAULT_INTERVAL))) * 1000L;
    }
    private void loadDashboard() {
        view.setLocationLabel(("beijing_tongzhou_langqingyuan".equals(city()) || "xinwen".equals(city()))
                ? DashboardFetcher.CITY_TO_SERVER_CITY.get(city()) : null);
        if (!demoMode()) {
            String cached = cache.load(cacheId());
            if (cached != null) {
                try { view.setData(DashboardJson.parse(cached)); view.setDemo(false); view.setStatus("已载入本地缓存"); return; }
                catch (IllegalArgumentException ignored) { /* Replaced with visibly labeled demo below. */ }
            }
        }
        showDemo();
    }
    private void showDemo() {
        view.setData(DashboardJson.parse(DemoData.create(DashboardFetcher.serverCityName(city()))));
        view.setDemo(true);
        view.setStatus("离线演示；关闭演示后自动获取实时天气");
    }
    private void scheduleMinuteTick() { handler.postDelayed(minuteTick, 60000 - System.currentTimeMillis() % 60000); }
    private void stopRefresh() {
        generation++; handler.removeCallbacks(poll);
        if (request != null) { request.cancelRequest(); request = null; }
    }
    private void refresh() {
        stopRefresh();
        if (!active || demoMode()) return;
        final int token = generation;
        final String selected = city(), identity = cacheId();
        view.setStatus("正在更新天气…");
        request = new DashboardFetcher(this, baseUrl(), selected, result -> {
            if (!active || token != generation || !selected.equals(city()) || isFinishing()) return;
            request = null;
            if (result != null && result.ok()) {
                view.setData(result.data != null ? result.data : DashboardJson.parse(result.json));
                view.setDemo(false);
                view.setStatus("天气：" + view.getData().weatherSource + " · 空气：" + view.getData().airSource
                        + " · 已更新" + (result.notice == null ? "" : "；" + result.notice));
                view.requestFullRefresh();
                disk.execute(() -> {
                    boolean saved = cache.save(identity, result.json);
                    if (!saved) handler.post(() -> {
                        if (active && token == generation) view.setStatus("天气已更新，但本地缓存保存失败");
                    });
                });
            } else {
                String error = result == null ? "获取天气失败" : result.error;
                view.setStatus(error + " · 保留已有画面"); view.setStaleFlags();
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            }
            if (active && token == generation) handler.postDelayed(poll, intervalMillis());
        });
        request.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
    private void showWeatherDetails() {
        DashboardJson.DashboardData data = view.getData();
        if (data == null) return;
        DashboardJson.Current current = data.current;
        String message = "天气：" + current.label + "\n气温：" + DashboardLayout.temperatureText(current)
                + "\n体感：" + DashboardLayout.feelsLikeText(current) + "\n" + DashboardLayout.highLowText(current)
                + "\n湿度：" + DashboardLayout.humidityText(current)
                + "\n风：" + DashboardLayout.windLineText(current) + " · " + DashboardLayout.windSpeedLineText(current)
                + "\n日出：" + (current.sunrise == null ? "--" : current.sunrise)
                + "\n日落：" + (current.sunset == null ? "--" : current.sunset)
                + "\n观测时间：" + (current.observationTime == null ? "--" : current.observationTime)
                + "\n来源：" + data.weatherSource;
        new AlertDialog.Builder(this).setTitle("今日天气").setMessage(message).setPositiveButton("关闭", null).show();
    }
    private void showThemes() {
        DashboardCanvasView.Theme[] themes = DashboardCanvasView.Theme.values();
        String[] labels = new String[themes.length];
        int selected = 0;
        for (int i = 0; i < themes.length; i++) {
            labels[i] = themes[i].label;
            if (themes[i] == view.getTheme()) selected = i;
        }
        new AlertDialog.Builder(this).setTitle("选择界面风格").setSingleChoiceItems(labels, selected, (dialog, index) -> {
            view.setTheme(themes[index]); dialog.dismiss();
        }).setNegativeButton("取消", null).show();
    }
    private void showCities() {
        String[] ids = DashboardFetcher.CITY_TO_SERVER_CITY.keySet().toArray(new String[0]);
        String[] names = DashboardFetcher.CITY_TO_SERVER_CITY.values().toArray(new String[0]);
        int selected = 0;
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(city())) selected = i;
        new AlertDialog.Builder(this).setTitle("选择城市").setSingleChoiceItems(names, selected, (dialog, index) -> {
            prefs.edit().putString("selected_city", ids[index]).apply();
            stopRefresh(); loadDashboard(); refresh(); dialog.dismiss();
        }).setNegativeButton("取消", null).show();
    }
    private void showSettings() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(12), dp(20), dp(12));
        TextView hostLabel = new TextView(this); hostLabel.setText("和风天气 API Host（控制台中的专属域名）"); content.addView(hostLabel);
        EditText host = new EditText(this); host.setSingleLine(true); host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        host.setHint("你的专属域名.qweatherapi.com"); host.setText(weatherHost()); content.addView(host);
        TextView keyLabel = new TextView(this); keyLabel.setText("和风天气 API Key（仅保存在本机，不是凭据 ID）"); content.addView(keyLabel);
        EditText key = new EditText(this); key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSaveEnabled(false); key.setText(weatherKey()); content.addView(key);
        TextView urlLabel = new TextView(this); urlLabel.setText("黄历接口地址（不含 /v1/dashboard）"); content.addView(urlLabel);
        EditText url = new EditText(this); url.setSingleLine(true); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(baseUrl()); content.addView(url);
        TextView intervalLabel = new TextView(this); intervalLabel.setText("刷新间隔，单位秒（60–604800，默认 1800）"); content.addView(intervalLabel);
        EditText interval = new EditText(this); interval.setSingleLine(true); interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        interval.setText(String.valueOf(prefs.getInt("interval_seconds", DEFAULT_INTERVAL))); content.addView(interval);
        addButton(content, "测试天气与黄历连接", () -> testServer(url.getText().toString().trim(),
                host.getText().toString(), key.getText().toString()));
        addButton(content, "选择城市", this::showCities);
        addButton(content, "切换界面风格", this::showThemes);
        addButton(content, "立即刷新已保存的配置", () -> {
            prefs.edit().putBoolean("demo_mode", false).apply(); refresh();
        });
        addButton(content, "离线演示（明确标注为示例数据）", () -> {
            prefs.edit().putBoolean("demo_mode", true).apply(); stopRefresh(); showDemo();
            view.requestFullRefresh(); Toast.makeText(this, "已切换离线演示，点击立即刷新可恢复联网", Toast.LENGTH_LONG).show();
        });
        TextView version = new TextView(this);
        version.setText("智能天气墨水屏 " + BuildConfig.VERSION_NAME + "\n长按画面打开设置，点击左上角切换城市。\n实况、体感温度、七日预报和空气质量由和风天气提供。空气卡片显示指数标准、PM2.5 浓度与等级。黄历使用上方接口，句子来自一言。未提供的指标显示 --。\n天气数据：和风天气 QWeather\nhttps://www.qweather.com/");
        version.setPadding(0, dp(12), 0, 0); content.addView(version);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("墨水看板设置").setView(scroll)
                .setNegativeButton("关闭", null).setPositiveButton("保存并连接", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = url.getText().toString().trim();
            if (!DashboardFetcher.validBaseUrl(address)) { url.setError("请输入有效 HTTP/HTTPS 地址，不含 ? 或 #"); return; }
            String hostValue = QWeatherJson.normalizeHost(host.getText().toString());
            String keyValue = key.getText().toString().trim();
            if (!QWeatherJson.validHost(hostValue)) { host.setError("请输入和风控制台中的 qweatherapi.com 专属域名"); return; }
            if (keyValue.isEmpty()) { key.setError("请输入 API Key"); return; }
            int seconds;
            try { seconds = Integer.parseInt(interval.getText().toString().trim()); }
            catch (NumberFormatException e) { interval.setError("请输入有效整数"); return; }
            if (seconds < 60 || seconds > MAX_INTERVAL) { interval.setError("范围为 60–604800 秒"); return; }
            prefs.edit().putString("json_base_url", address).putInt("interval_seconds", seconds)
                    .putString("qweather_host", hostValue).putString("qweather_api_key", keyValue)
                    .putBoolean("demo_mode", false).apply();
            stopRefresh(); loadDashboard(); refresh(); view.requestFullRefresh(); dialog.dismiss();
        }));
        dialog.show();
    }
    private void testServer(String address, String host, String key) {
        if (!DashboardFetcher.validBaseUrl(address)) { Toast.makeText(this, "服务器地址无效", Toast.LENGTH_LONG).show(); return; }
        if (!QWeatherJson.validHost(host) || key.trim().isEmpty()) {
            Toast.makeText(this, "请填写和风天气 API Host 和 API Key", Toast.LENGTH_LONG).show(); return;
        }
        if (testRequests.size() >= 2) { Toast.makeText(this, "正在测试，请稍候", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, "正在测试天气与黄历连接", Toast.LENGTH_SHORT).show();
        final DashboardFetcher[] task = new DashboardFetcher[1];
        task[0] = new DashboardFetcher(this, address, city(), host, key, result -> {
            testRequests.remove(task[0]);
            if (active && !isFinishing()) Toast.makeText(this, result.ok()
                    ? (result.notice == null ? "和风天气、空气与黄历连接正常" : "天气实况正常；" + result.notice)
                    : result.error, Toast.LENGTH_LONG).show();
        });
        testRequests.add(task[0]); task[0].executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
    private void addButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void updateDeviceStatus() {
        int battery = -1;
        Intent state = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (state != null) {
            int level = state.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = state.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) battery = Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
        }
        String label = "离线";
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
            if (network != null && network.isConnected()) {
                if (network.getType() == ConnectivityManager.TYPE_WIFI) {
                    label = "Wi-Fi";
                    WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
                    if (info != null) label += " " + (WifiManager.calculateSignalLevel(info.getRssi(), 4) + 1) + "/4";
                } else label = network.getType() == ConnectivityManager.TYPE_MOBILE ? "移动网络" : "已联网";
            }
        } catch (SecurityException ignored) { label = "网络 --"; }
        view.setDeviceStatus(label, battery);
    }
}
