package com.github.ronghuaxueleng.remoteeink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.List;

/** Native monochrome dashboard on a 1072 x 1448 logical canvas. */
public final class DashboardCanvasView extends View {
    public static final int LOGICAL_WIDTH = 1072, LOGICAL_HEIGHT = 1448;
    private static final String[] WEEKDAYS = {"日", "一", "二", "三", "四", "五", "六"};
    private static final String[] SEGMENTS = {"abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgecd", "abc", "abcdefg", "abfgcd"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final SeasonalCalendar seasonalCalendar = new SeasonalCalendar();
    private DashboardJson.DashboardData data;
    private boolean weatherStale, airStale, demo, longPressConsumed;
    private int fullRefreshPhase;
    private String network = "网络 --", status = "长按画面设置服务器", locationLabel;
    private int battery = -1;
    private ActionListener actions;
    public interface ActionListener { void onCityClick(); void onSettingsClick(); }
    public DashboardCanvasView(Context context) { super(context); init(); }
    public DashboardCanvasView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public DashboardCanvasView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); init(); }
    private void init() {
        setBackgroundColor(Color.WHITE);
        setClickable(true); setLongClickable(true);
        setContentDescription("天气墨水屏看板，点击左上角选择城市，点击右上角或长按画面打开设置");
    }
    public void setActionListener(ActionListener listener) { actions = listener; }
    public DashboardJson.DashboardData getData() { return data; }
    public void setData(DashboardJson.DashboardData value) { data = value; setStaleFlags(); invalidate(); }
    public void setDemo(boolean value) { demo = value; invalidate(); }
    public void setLocationLabel(String value) { locationLabel = value; invalidate(); }
    public void setStatus(String text) { status = text == null ? "" : text; invalidate(); }
    public void setDeviceStatus(String label, int percent) { network = label; battery = percent; invalidate(); }
    public boolean isWeatherStale() { return weatherStale; }
    public boolean isAirStale() { return airStale; }
    public void setStaleFlags() {
        long now = System.currentTimeMillis();
        boolean weather = data != null && (data.isWeatherStale()
                || DashboardLayout.WEATHER_STALE.equals(DashboardLayout.weatherNoticeText(data, now)));
        boolean air = DashboardLayout.isAirStale(data, now);
        setStaleFlags(weather, air);
    }
    public void setStaleFlags(boolean weather, boolean air) {
        boolean changed = weatherStale != weather || airStale != air;
        weatherStale = weather; airStale = air;
        if (changed) invalidate();
    }
    public void requestFullRefresh() { fullRefreshPhase = 1; invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (fullRefreshPhase == 1 || fullRefreshPhase == 2) {
            canvas.drawColor(fullRefreshPhase == 1 ? Color.BLACK : Color.WHITE);
            fullRefreshPhase++; postInvalidateDelayed(100); return;
        }
        fullRefreshPhase = 0;
        canvas.drawColor(Color.WHITE);
        float scale = Math.min(getWidth() / (float) LOGICAL_WIDTH, getHeight() / (float) LOGICAL_HEIGHT);
        canvas.save();
        canvas.translate((getWidth() - LOGICAL_WIDTH * scale) / 2f, (getHeight() - LOGICAL_HEIGHT * scale) / 2f);
        canvas.scale(scale, scale);
        header(canvas); clockAndWeather(canvas); metrics(canvas); forecast(canvas); almanac(canvas);
        fittedLeft(canvas, demo ? "演示数据 · " + status : status, 30, 1425, 18, 920);
        text(canvas, "Gary", 1040, 1425, 18, false, Paint.Align.RIGHT);
        canvas.restore();
    }
    private void header(Canvas c) {
        String cityLine = locationLabel == null ? DashboardLayout.cityLineText(data) : "● " + locationLabel + " ▼";
        fittedLeft(c, cityLine, 30, 51, 28, 650);
        text(c, network + "  " + (battery < 0 ? "--" : battery + "%") + "   ⚙", 1040, 51, 25, true, Paint.Align.RIGHT);
        line(c, 30, 76, 1040, 76);
        text(c, DashboardLayout.dateText(System.currentTimeMillis(), WEEKDAYS), 30, 114, 30, true, Paint.Align.LEFT);
    }
    private void clockAndWeather(Canvas c) {
        String clock = DashboardLayout.clockText(System.currentTimeMillis());
        float x = 34;
        for (int i = 0; i < clock.length(); i++) {
            char ch = clock.charAt(i);
            if (ch == ':') {
                fill(); c.drawRect(x + 5, 206, x + 20, 225, paint); c.drawRect(x + 5, 277, x + 20, 296, paint); x += 36;
            } else { digit(c, ch, x, 162, 106, 202); x += 118; }
        }
        DashboardJson.Almanac almanac = data == null ? null : data.almanac;
        fittedLeft(c, almanac == null ? "--" : almanac.lunar, 34, 410, 29, 500);
        fittedLeft(c, almanac == null ? "--" : almanac.ganzhi, 34, 452, 26, 500);
        DashboardJson.Current current = data == null ? null : data.current;
        weatherIcon(c, 690, 227, 106, current == null ? null : current.code);
        fitted(c, DashboardLayout.weatherLabelText(current), 875, 229, 37, 220);
        fitted(c, DashboardLayout.temperatureText(current), 801, 357, 91, 420);
        fitted(c, DashboardLayout.highLowText(current), 801, 408, 27, 420);
        String notice = demo ? "演示模式" : DashboardLayout.weatherNoticeText(data, System.currentTimeMillis());
        if (notice != null) fitted(c, notice, 801, 449, 22, 420);
        line(c, 30, 482, 1040, 482);
    }
    private void digit(Canvas c, char character, float x, float y, float w, float h) {
        if (character < '0' || character > '9') return;
        String parts = SEGMENTS[character - '0'];
        float t = 15, half = h / 2;
        fill();
        if (parts.indexOf('a') >= 0) c.drawRect(x + t, y, x + w - t, y + t, paint);
        if (parts.indexOf('g') >= 0) c.drawRect(x + t, y + half - t / 2, x + w - t, y + half + t / 2, paint);
        if (parts.indexOf('d') >= 0) c.drawRect(x + t, y + h - t, x + w - t, y + h, paint);
        if (parts.indexOf('f') >= 0) c.drawRect(x, y + t, x + t, y + half - t / 2, paint);
        if (parts.indexOf('e') >= 0) c.drawRect(x, y + half + t / 2, x + t, y + h - t, paint);
        if (parts.indexOf('b') >= 0) c.drawRect(x + w - t, y + t, x + w, y + half - t / 2, paint);
        if (parts.indexOf('c') >= 0) c.drawRect(x + w - t, y + half + t / 2, x + w, y + h - t, paint);
    }
    private void metrics(Canvas c) {
        String[] labels = {"风向 / 风速", "湿度", "空气质量", "体感温度"};
        DashboardJson.Current current = data == null ? null : data.current;
        DashboardJson.Air air = data == null ? null : data.air;
        for (int i = 0; i < 4; i++) {
            float center = 156 + 253 * i;
            if (i > 0) line(c, center - 126, 510, center - 126, 704);
            text(c, labels[i], center, 547, 31, true, Paint.Align.CENTER);
            if (i == 0) {
                fitted(c, DashboardLayout.windLineText(current), center, 613, 29, 230);
                fitted(c, DashboardLayout.windSpeedLineText(current), center, 669, 31, 230);
            } else if (i == 1) {
                fitted(c, DashboardLayout.humidityText(current), center, 646, 51, 230);
            } else if (i == 2) {
                fitted(c, "AQI " + DashboardLayout.phpNumber(air == null ? null : air.aqi), center, 605, 36, 230);
                fitted(c, air == null ? "--" : air.primaryPollutant, center, 646, 24, 230);
                fitted(c, air == null ? "--" : air.aqiLabel, center, 686, 23, 230);
            } else {
                fitted(c, DashboardLayout.feelsLikeText(current), center, 646, 48, 230);
            }
        }
        line(c, 30, 730, 1040, 730);
    }
    private void forecast(Canvas c) {
        text(c, DashboardLayout.FORECAST_TITLE, 30, 776, 30, true, Paint.Align.LEFT);
        float width = 1010f / 7;
        for (int i = 0; i < 7; i++) {
            float left = 30 + i * width, center = left + width / 2;
            if (i > 0) line(c, left, 801, left, 1010);
            DashboardJson.ForecastDay day = DashboardLayout.forecastDayAt(data, i);
            fitted(c, DashboardLayout.forecastWeekdayText(day, WEEKDAYS), center, 833, 28, width - 12);
            fitted(c, DashboardLayout.forecastDateText(day), center, 867, 24, width - 12);
            weatherIcon(c, center, 911, 48, day == null ? null : day.code);
            fitted(c, DashboardLayout.forecastLabelText(day), center, 966, 24, width - 12);
            fitted(c, DashboardLayout.forecastHighLowText(day), center, 1000, 24, width - 10);
        }
        line(c, 30, 1032, 1040, 1032);
    }
    private void almanac(Canvas c) {
        DashboardJson.Almanac a = data == null ? null : data.almanac;
        SeasonalCalendar.Snapshot season = seasonalCalendar.at(System.currentTimeMillis());
        line(c, 535, 1050, 535, 1125);
        fittedLeft(c, season.termLine, 30, 1074, 25, 480);
        fittedLeft(c, DashboardLayout.termPoemText(season.termName, season.rotationDay), 30, 1117, 22, 480);
        fittedLeft(c, "节日：" + (a == null ? "--" : a.festival), 565, 1074, 27, 475);
        fittedLeft(c, season.festivalCountdown, 565, 1117, 23, 475);
        line(c, 30, 1141, 1040, 1141);
        DashboardJson.Quote quote = data == null ? null : data.quote;
        fitted(c, quote == null ? "--" : quote.content, 535, 1182, 28, 1010);
        fittedLeft(c, quote == null ? "一言" : quote.attribution(), 30, 1220, 22, 1010);
        line(c, 30, 1240, 1040, 1240); line(c, 535, 1258, 535, 1390);
        text(c, "宜", 65, 1311, 51, true, Paint.Align.CENTER);
        text(c, "忌", 575, 1311, 51, true, Paint.Align.CENTER);
        List<String> yi = a == null ? java.util.Collections.emptyList() : a.yi;
        List<String> ji = a == null ? java.util.Collections.emptyList() : a.ji;
        DashboardLayout.YijiLayout layout = DashboardLayout.yiji(yi, ji, (value, size) -> {
            prepareText(size, false, Paint.Align.LEFT); return paint.measureText(value);
        });
        for (int i = 0; i < layout.yiLines.size(); i++) fittedLeft(c, layout.yiLines.get(i), 115, layout.firstBaseline + i * layout.lineStep, layout.size, 385);
        for (int i = 0; i < layout.jiLines.size(); i++) fittedLeft(c, layout.jiLines.get(i), 620, layout.firstBaseline + i * layout.lineStep, layout.size, 400);
        line(c, 30, 1400, 1040, 1400);
    }
    private void weatherIcon(Canvas c, float x, float y, float size, Integer code) {
        if (code == null) { text(c, "--", x, y + 10, 30, false, Paint.Align.CENTER); return; }
        c.save(); c.translate(x, y); c.scale(size / 100, size / 100);
        boolean clear = code == 0;
        boolean partly = code == 1 || code == 2;
        if (clear || partly) {
            stroke(4);
            float sunX = partly ? -19 : 0, sunY = partly ? -18 : 0;
            c.drawCircle(sunX, sunY, 23, paint);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                c.drawLine(sunX + (float) Math.cos(angle) * 32, sunY + (float) Math.sin(angle) * 32,
                        sunX + (float) Math.cos(angle) * 43, sunY + (float) Math.sin(angle) * 43, paint);
            }
        }
        if (!clear) {
            paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL);
            c.drawOval(bounds(-45, -13, 45, 27), paint);
            c.drawCircle(-12, -14, 23, paint); c.drawCircle(15, -8, 26, paint);
            stroke(4);
            Path cloud = new Path(); cloud.moveTo(-36, 25);
            cloud.cubicTo(-65, 20, -55, -11, -32, -10);
            cloud.cubicTo(-30, -42, 8, -47, 22, -20);
            cloud.cubicTo(50, -29, 64, 7, 43, 24); cloud.close(); c.drawPath(cloud, paint);
            if (code >= 51 && code <= 67 || code >= 80 && code <= 82) {
                for (int i = -1; i <= 1; i++) c.drawLine(i * 22 + 4, 35, i * 22 - 5, 51, paint);
            } else if (code == 68 || code == 69 || code >= 71 && code <= 77 || code == 85 || code == 86) {
                for (int i = -1; i <= 1; i++) { c.drawLine(i * 24 - 6, 44, i * 24 + 6, 44, paint); c.drawLine(i * 24, 38, i * 24, 50, paint); }
            } else if (code >= 95) {
                Path bolt = new Path(); bolt.moveTo(4, 30); bolt.lineTo(-12, 48); bolt.lineTo(1, 48); bolt.lineTo(-5, 63);
                c.drawPath(bolt, paint);
            } else if (code == 45 || code == 48) { c.drawLine(-38, 37, 38, 37, paint); c.drawLine(-27, 49, 27, 49, paint); }
        }
        c.restore();
    }
    private RectF bounds(float left, float top, float right, float bottom) { oval.set(left, top, right, bottom); return oval; }
    private void fill() { paint.reset(); paint.setAntiAlias(true); paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.FILL); }
    private void stroke(float width) { fill(); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); }
    private void prepareText(float size, boolean bold, Paint.Align align) {
        fill(); paint.setTextSize(size); paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); paint.setTextAlign(align);
    }
    private void text(Canvas c, String value, float x, float y, float size, boolean bold, Paint.Align align) {
        prepareText(size, bold, align); c.drawText(value == null ? "--" : value, x, y, paint);
    }
    private void fitted(Canvas c, String value, float x, float y, float size, float maxWidth) {
        prepareText(size, false, Paint.Align.CENTER);
        String t = value == null ? "--" : value;
        float width = paint.measureText(t); if (width > maxWidth) paint.setTextScaleX(maxWidth / width);
        c.drawText(t, x, y, paint);
    }
    private void fittedLeft(Canvas c, String value, float x, float y, float size, float maxWidth) {
        prepareText(size, false, Paint.Align.LEFT);
        float width = paint.measureText(value); if (width > maxWidth) paint.setTextScaleX(maxWidth / width);
        c.drawText(value, x, y, paint);
    }
    private void line(Canvas c, float x1, float y1, float x2, float y2) { stroke(2); c.drawLine(x1, y1, x2, y2, paint); }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean performLongClick() {
        longPressConsumed = true;
        return super.performLongClick();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) longPressConsumed = false;
        boolean handled = super.onTouchEvent(event);
        float scale = Math.min(getWidth() / (float) LOGICAL_WIDTH, getHeight() / (float) LOGICAL_HEIGHT);
        if (!longPressConsumed && scale > 0 && actions != null && event.getAction() == MotionEvent.ACTION_UP) {
            float x = (event.getX() - (getWidth() - LOGICAL_WIDTH * scale) / 2) / scale;
            float y = (event.getY() - (getHeight() - LOGICAL_HEIGHT * scale) / 2) / scale;
            if (y >= 0 && y <= 76 && x >= 0 && x <= LOGICAL_WIDTH) {
                performClick();
                if (x > 850) actions.onSettingsClick(); else if (x < 650) actions.onCityClick();
                return true;
            }
        }
        return handled;
    }
}
