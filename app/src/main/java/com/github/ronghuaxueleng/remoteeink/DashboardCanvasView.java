package com.github.ronghuaxueleng.remoteeink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Switchable monochrome dashboard on a 1072 x 1448 logical canvas. */
public final class DashboardCanvasView extends View {
    public static final int LOGICAL_WIDTH = 1072, LOGICAL_HEIGHT = 1448;
    private static final float LEFT = 48, RIGHT = 1024, CENTER = 536;
    private static final String[] WEEKDAYS = {"日", "一", "二", "三", "四", "五", "六"};
    private static final String[] SEGMENTS = {"abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgecd", "abc", "abcdefg", "abfgcd"};
    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    public enum Theme {
        NEWSPAPER("现代报纸"), BOOK("书刊"), MINIMAL("极简"), RETRO("复古仪表");
        public final String label;
        Theme(String label) { this.label = label; }
    }
    private Theme theme = Theme.NEWSPAPER;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final SeasonalCalendar seasonalCalendar = new SeasonalCalendar();
    private DashboardJson.DashboardData data;
    private boolean weatherStale, airStale, demo, longPressConsumed, segmentedClock;
    private int fullRefreshPhase;
    private String network = "网络 --", status = "长按画面打开设置", locationLabel;
    private int battery = -1;
    private ActionListener actions;
    public interface ActionListener { void onCityClick(); void onSettingsClick(); void onWeatherClick(); void onThemeClick(); }
    public DashboardCanvasView(Context context) { super(context); init(); }
    public DashboardCanvasView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public DashboardCanvasView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); init(); }
    private void init() {
        setBackgroundColor(Color.WHITE);
        setClickable(true); setLongClickable(true);
        String saved = getContext().getSharedPreferences("presentation", Context.MODE_PRIVATE).getString("theme", Theme.NEWSPAPER.name());
        for (Theme candidate : Theme.values()) if (candidate.name().equals(saved)) theme = candidate;
        loadClockStyle();
        setContentDescription("天气日历，点击左上角选择地点，点击右上角或长按打开设置，点击风格名称切换主题，点击时钟切换字形，点击天气查看详情");
    }
    public Theme getTheme() { return theme; }
    public void setTheme(Theme value) {
        if (theme == value) return;
        theme = value;
        getContext().getSharedPreferences("presentation", Context.MODE_PRIVATE).edit().putString("theme", theme.name()).apply();
        loadClockStyle(); requestFullRefresh();
    }
    private void loadClockStyle() {
        android.content.SharedPreferences preferences = getContext().getSharedPreferences("presentation", Context.MODE_PRIVATE);
        boolean defaultStyle = theme == Theme.RETRO || (theme == Theme.NEWSPAPER && preferences.getBoolean("segmented_clock", false));
        segmentedClock = preferences.getBoolean("segmented_clock_" + theme.name(), defaultStyle);
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
        setStaleFlags(data != null && (data.isWeatherStale()
                || DashboardLayout.WEATHER_STALE.equals(DashboardLayout.weatherNoticeText(data, now))),
                DashboardLayout.isAirStale(data, now));
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
        long now = System.currentTimeMillis();
        DashboardLayout.Daylight sunlight = DashboardLayout.daylight(data, now);
        header(canvas); clockAndWeather(canvas, now, sunlight); daylight(canvas, sunlight);
        metrics(canvas); forecast(canvas); almanac(canvas, now);
        fittedLeft(canvas, demo ? "演示数据 · " + status : status, LEFT, 1438, 16, 900);
        text(canvas, DashboardLayout.WATERMARK_TEXT, RIGHT, 1438, 16, false, Paint.Align.RIGHT);
        canvas.restore();
    }
    private void header(Canvas c) {
        String city = DashboardLayout.compactCity(locationLabel != null ? locationLabel : data == null ? null : data.city);
        fittedLeft(c, city, LEFT, 51, 26, 630);
        prepareText(26, false, Paint.Align.LEFT);
        float arrow = Math.min(700, LEFT + paint.measureText(city) + 24);
        stroke(1.8f); c.drawLine(arrow - 5, 39, arrow, 44, paint); c.drawLine(arrow, 44, arrow + 5, 39, paint);
        boolean wifi = network.startsWith("Wi-Fi");
        if (wifi) {
            int bars = 4;
            if (network.matches(".*[1-4]/4.*")) bars = network.charAt(network.indexOf("/4") - 1) - '0';
            for (int i = 0; i < 4; i++) {
                if (i < bars) fill(); else stroke(1);
                c.drawRect(829 + i * 10, 48 - (i + 1) * 5, 835 + i * 10, 48, paint);
            }
        } else text(c, network, 867, 49, 19, false, Paint.Align.RIGHT);
        stroke(1.8f); c.drawRoundRect(bounds(891, 28, 938, 49), 3, 3, paint);
        fill(); c.drawRect(939, 34, 943, 43, paint);
        if (battery >= 0) c.drawRect(895, 32, 895 + 39 * battery / 100f, 45, paint);
        text(c, battery < 0 ? "--" : battery + "%", RIGHT, 49, 21, false, Paint.Align.RIGHT);
        line(c, LEFT, 76, RIGHT, 76);
    }
    private void clockAndWeather(Canvas c, long now, DashboardLayout.Daylight sunlight) {
        text(c, DashboardLayout.dateText(now, WEEKDAYS), LEFT, 119, 25, false, Paint.Align.LEFT);
        text(c, theme.label, RIGHT - 25, 119, 20, false, Paint.Align.RIGHT);
        stroke(1.8f);
        c.drawLine(RIGHT - 12, 108, RIGHT - 7, 113, paint);
        c.drawLine(RIGHT - 7, 113, RIGHT - 2, 108, paint);
        if (theme == Theme.BOOK) {
            text(c, "四时有序 · 日常有诗", CENTER, 151, 18, false, Paint.Align.CENTER);
            stroke(2); c.drawLine(LEFT, 134, 270, 134, paint); c.drawLine(802, 134, RIGHT, 134, paint);
        } else if (theme == Theme.RETRO) {
            stroke(1.8f); c.drawRect(LEFT, 149, 606, 421, paint); c.drawRect(626, 149, RIGHT, 421, paint);
            for (int i = 0; i <= 20; i++) {
                float x = LEFT + 18 + i * 26;
                c.drawLine(x, 159, x, i % 5 == 0 ? 170 : 165, paint);
            }
        }
        String clock = DashboardLayout.clockText(now);
        if (segmentedClock) {
            float x = LEFT + (theme == Theme.RETRO ? 28 : 3);
            for (int i = 0; i < clock.length(); i++) {
                char ch = clock.charAt(i);
                if (ch == ':') {
                    fill(); c.drawCircle(x + 9, 223, 4, paint); c.drawCircle(x + 9, 275, 4, paint); x += 30;
                } else { digit(c, ch, x, theme == Theme.RETRO ? 181 : 165, theme == Theme.RETRO ? 98 : 103, theme == Theme.RETRO ? 142 : 158); x += theme == Theme.RETRO ? 113 : 119; }
            }
        } else {
            prepareText(theme == Theme.BOOK ? 174 : 183, false, Paint.Align.LEFT);
            paint.setTypeface(theme == Theme.BOOK ? Typeface.create("serif", Typeface.NORMAL) : LIGHT);
            float width = paint.measureText(clock);
            if (width > 540) paint.setTextScaleX(540 / width);
            c.drawText(clock, theme == Theme.RETRO ? LEFT + 12 : LEFT - 7, 323, paint);
        }
        DashboardJson.Almanac a = data == null ? null : data.almanac;
        float calendarLeft = theme == Theme.RETRO ? LEFT + 20 : LEFT;
        fittedLeft(c, a == null ? "--" : a.lunar, calendarLeft, 369, 25, 515);
        fittedLeft(c, a == null ? "--" : a.ganzhi, calendarLeft, 406, 21, 515);
        DashboardJson.Current current = data == null ? null : data.current;
        weatherIcon(c, 743, 218, 88, current == null ? null : current.code, sunlight.available && sunlight.night);
        fitted(c, DashboardLayout.weatherLabelText(current), 924, 225, 29, 190);
        prepareText(88, false, Paint.Align.CENTER); paint.setTypeface(LIGHT);
        c.drawText(DashboardLayout.temperatureText(current), 852, 329, paint);
        fitted(c, DashboardLayout.highLowText(current), 852, 371, 22, 340);
        String notice = demo ? "演示模式" : DashboardLayout.weatherNoticeText(data, now);
        if (notice != null) fitted(c, notice, 852, 406, 18, 340);
    }
    private void digit(Canvas c, char character, float x, float y, float w, float h) {
        if (character < '0' || character > '9') return;
        String parts = SEGMENTS[character - '0'];
        float t = 5, half = h / 2;
        fill();
        if (parts.indexOf('a') >= 0) c.drawRect(x + t, y, x + w - t, y + t, paint);
        if (parts.indexOf('g') >= 0) c.drawRect(x + t, y + half - t / 2, x + w - t, y + half + t / 2, paint);
        if (parts.indexOf('d') >= 0) c.drawRect(x + t, y + h - t, x + w - t, y + h, paint);
        if (parts.indexOf('f') >= 0) c.drawRect(x, y + t, x + t, y + half - t / 2, paint);
        if (parts.indexOf('e') >= 0) c.drawRect(x, y + half + t / 2, x + t, y + h - t, paint);
        if (parts.indexOf('b') >= 0) c.drawRect(x + w - t, y + t, x + w, y + half - t / 2, paint);
        if (parts.indexOf('c') >= 0) c.drawRect(x + w - t, y + half + t / 2, x + w, y + h - t, paint);
    }
    private void daylight(Canvas c, DashboardLayout.Daylight sunlight) {
        text(c, "日出 " + sunlight.sunrise, LEFT, 454, 20, false, Paint.Align.LEFT);
        text(c, "日落 " + sunlight.sunset, RIGHT, 454, 20, false, Paint.Align.RIGHT);
        line(c, 266, 447, 806, 447);
        if (theme == Theme.RETRO) {
            stroke(1);
            for (int i = 0; i <= 24; i++) {
                float x = 266 + 540 * i / 24f;
                c.drawLine(x, 447, x, i % 6 == 0 ? 457 : 452, paint);
            }
        }
        if (sunlight.available) {
            float x = 266 + 540 * sunlight.progress;
            stroke(3); c.drawLine(266, 447, x, 447, paint);
            fill(); paint.setColor(Color.WHITE); c.drawCircle(x, 447, 10, paint);
            stroke(2); c.drawCircle(x, 447, 7, paint);
            if (!sunlight.night) { fill(); c.drawCircle(x, 447, 3, paint); }
        }
        fitted(c, sunlight.description, CENTER, 482, 18, 690);
        line(c, LEFT, 507, RIGHT, 507);
    }
    private void metrics(Canvas c) {
        DashboardJson.Current current = data == null ? null : data.current;
        DashboardJson.Air air = data == null ? null : data.air;
        String[] labels = {"风向 / 风速", "相对湿度", air == null ? "空气质量" : "空气 · " + air.aqiName, "体感温度"};
        if (theme == Theme.RETRO) { stroke(1.8f); c.drawRect(LEFT, 521, RIGHT, 661, paint); }
        for (int i = 0; i < 4; i++) {
            float center = LEFT + 122 + 244 * i;
            if (i > 0 && theme != Theme.MINIMAL) line(c, center - 122, 535, center - 122, 648);
            fitted(c, labels[i], center, 549, 21, 220);
            if (i == 0) {
                fitted(c, DashboardLayout.windLineText(current), center, 601, 28, 220);
                fitted(c, DashboardLayout.windSpeedLineText(current), center, 641, 21, 220);
            } else if (i == 1) {
                fitted(c, DashboardLayout.humidityText(current), center, 612, 43, 220);
            } else if (i == 2) {
                fitted(c, DashboardLayout.phpNumber(air == null ? null : air.aqi) + "  " + (air == null ? "--" : air.aqiLabel), center, 601, 35, 220);
                fitted(c, air == null ? "--" : air.pm25 == null ? air.primaryPollutant
                        : "PM2.5 " + DashboardLayout.pmText(air), center, 641, 19, 220);
            } else fitted(c, DashboardLayout.feelsLikeText(current), center, 612, 43, 220);
        }
        line(c, LEFT, 680, RIGHT, 680);
    }
    private void forecast(Canvas c) {
        text(c, theme == Theme.RETRO ? "07 / 七日趋势" : "未来七天", LEFT, 724, 26, theme == Theme.BOOK, Paint.Align.LEFT);
        stroke(2.4f); c.drawLine(795, 716, 826, 716, paint); fill(); c.drawCircle(811, 716, 3, paint);
        text(c, "最高", 838, 723, 18, false, Paint.Align.LEFT);
        stroke(1.6f); paint.setPathEffect(new DashPathEffect(new float[] {5, 5}, 0)); c.drawLine(921, 716, 952, 716, paint);
        stroke(1.6f); c.drawCircle(936, 716, 3, paint); text(c, "最低", 963, 723, 18, false, Paint.Align.LEFT);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 7; i++) {
            DashboardJson.ForecastDay day = DashboardLayout.forecastDayAt(data, i);
            if (day == null) continue;
            if (day.high != null) { min = Math.min(min, day.high); max = Math.max(max, day.high); }
            if (day.low != null) { min = Math.min(min, day.low); max = Math.max(max, day.low); }
        }
        float width = (RIGHT - LEFT) / 7;
        if (theme == Theme.RETRO) {
            stroke(1); paint.setColor(0xffb0b0b0);
            for (int i = 0; i <= 7; i++) c.drawLine(LEFT + width * i, 742, LEFT + width * i, 1027, paint);
            for (int i = 0; i < 3; i++) c.drawLine(LEFT, 925 + i * 38, RIGHT, 925 + i * 38, paint);
        }
        for (int i = 0; i < 7; i++) {
            float center = LEFT + width * (i + 0.5f);
            DashboardJson.ForecastDay day = DashboardLayout.forecastDayAt(data, i);
            fitted(c, DashboardLayout.forecastWeekdayText(day, WEEKDAYS), center, 769, 23, width - 8);
            fitted(c, DashboardLayout.forecastDateText(day), center, 799, 19, width - 8);
            weatherIcon(c, center, 840, 39, day == null ? null : day.code, false);
            fitted(c, DashboardLayout.forecastLabelText(day), center, 883, 19, width - 10);
        }
        if (min != Double.POSITIVE_INFINITY) {
            double range = Math.max(4, max - min);
            double bottom = (min + max - range) / 2;
            temperatureCurve(c, true, bottom, range, width);
            temperatureCurve(c, false, bottom, range, width);
        } else fitted(c, "温度趋势暂缺", CENTER, 973, 22, 500);
        line(c, LEFT, 1042, RIGHT, 1042);
    }
    private void temperatureCurve(Canvas c, boolean high, double bottom, double range, float width) {
        Path path = new Path();
        boolean connected = false;
        for (int i = 0; i < 7; i++) {
            DashboardJson.ForecastDay day = DashboardLayout.forecastDayAt(data, i);
            Double value = day == null ? null : high ? day.high : day.low;
            if (value == null) { connected = false; continue; }
            float x = LEFT + width * (i + 0.5f), y = 1001 - (float) ((value - bottom) / range) * 76;
            if (connected) path.lineTo(x, y); else path.moveTo(x, y);
            connected = true;
        }
        stroke(high ? 2.6f : 1.8f);
        if (!high) paint.setPathEffect(new DashPathEffect(new float[] {6, 7}, 0));
        c.drawPath(path, paint);
        for (int i = 0; i < 7; i++) {
            DashboardJson.ForecastDay day = DashboardLayout.forecastDayAt(data, i);
            Double value = day == null ? null : high ? day.high : day.low;
            float x = LEFT + width * (i + 0.5f);
            if (value == null) { fitted(c, "--", x, high ? 929 : 1022, 19, width - 8); continue; }
            float y = 1001 - (float) ((value - bottom) / range) * 76;
            fill(); if (!high) paint.setColor(Color.WHITE);
            c.drawCircle(x, y, 4.5f, paint);
            if (!high) { stroke(1.6f); c.drawCircle(x, y, 4.5f, paint); }
            fitted(c, DashboardLayout.phpNumber(value) + "°", x, high ? y - 13 : y + 28, 22, width - 8);
        }
    }
    private void almanac(Canvas c, long now) {
        DashboardJson.Almanac a = data == null ? null : data.almanac;
        SeasonalCalendar.Snapshot season = seasonalCalendar.at(now);
        line(c, CENTER, 1064, CENTER, 1140);
        fittedLeft(c, season.termLine, LEFT, 1080, 23, 460);
        if (theme != Theme.MINIMAL) seasonalTwig(c, 70, 1120, season.termName);
        String poem = DashboardLayout.termPoemText(season.termName, season.rotationDay);
        List<String> poemLines = new ArrayList<>();
        int pause = poem.indexOf('，');
        if (pause > 0) {
            poemLines.add(poem.substring(0, pause + 1)); poemLines.add(poem.substring(pause + 1));
        } else poemLines = wrapText(poem, 19, 403, 2);
        for (int i = 0; i < poemLines.size(); i++) fittedLeft(c, poemLines.get(i), theme == Theme.MINIMAL ? LEFT : 102, 1113 + i * 27, 19, 403);
        fittedLeft(c, "节日 · " + (a == null ? "--" : a.festival), 570, 1080, 23, 454);
        fittedLeft(c, season.festivalCountdown, 570, 1123, 21, 454);
        line(c, LEFT, 1158, RIGHT, 1158);
        DashboardJson.Quote quote = data == null ? null : data.quote;
        List<String> quoteLines = wrapText(quote == null ? "--" : quote.content, 28, 900, 2);
        float baseline = quoteLines.size() > 1 ? 1199 : 1215;
        if (theme == Theme.BOOK) {
            text(c, "“", LEFT + 2, 1207, 46, false, Paint.Align.LEFT);
            text(c, "”", RIGHT - 2, 1244, 46, false, Paint.Align.RIGHT);
        }
        for (int i = 0; i < quoteLines.size(); i++) fitted(c, quoteLines.get(i), CENTER, baseline + i * 34, 28, theme == Theme.BOOK ? 860 : 930);
        prepareText(18, false, Paint.Align.RIGHT);
        String credit = quote == null ? "一言" : quote.attribution();
        if (paint.measureText(credit) > 930) paint.setTextScaleX(930 / paint.measureText(credit));
        c.drawText(credit, RIGHT - 16, 1255, paint);
        line(c, LEFT, 1274, RIGHT, 1274); line(c, CENTER, 1292, CENTER, 1396);
        text(c, "宜", 70, 1310, 31, false, Paint.Align.CENTER);
        text(c, "忌", 581, 1310, 31, false, Paint.Align.CENTER);
        List<String> yi = a == null ? java.util.Collections.emptyList() : a.yi;
        List<String> ji = a == null ? java.util.Collections.emptyList() : a.ji;
        DashboardLayout.YijiLayout layout = DashboardLayout.yiji(yi, ji, (value, size) -> {
            prepareText(size, false, Paint.Align.LEFT); return paint.measureText(value);
        });
        for (int i = 0; i < layout.yiLines.size(); i++) fittedLeft(c, layout.yiLines.get(i), 113, 1306 + i * 28, Math.min(25, layout.size), 390);
        for (int i = 0; i < layout.jiLines.size(); i++) fittedLeft(c, layout.jiLines.get(i), 625, 1306 + i * 28, Math.min(25, layout.size), 399);
        line(c, LEFT, 1412, RIGHT, 1412);
    }
    private List<String> wrapText(String value, float size, float maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        String rest = value == null ? "--" : value;
        prepareText(size, false, Paint.Align.LEFT);
        while (!rest.isEmpty() && lines.size() < maxLines) {
            if (lines.size() == maxLines - 1) { lines.add(rest); break; }
            int count = Math.max(1, paint.breakText(rest, true, maxWidth, null));
            if (count < rest.length()) {
                for (int i = count - 1; i >= count / 2; i--) {
                    if ("，。；！？、 ".indexOf(rest.charAt(i)) >= 0) { count = i + 1; break; }
                }
            }
            lines.add(rest.substring(0, count).trim()); rest = rest.substring(count).trim();
        }
        return lines;
    }
    private void seasonalTwig(Canvas c, float x, float y, String term) {
        c.save(); c.translate(x, y);
        stroke(1.6f); c.drawLine(-12, 22, 9, -21, paint); c.drawLine(-3, 4, -18, -7, paint); c.drawLine(4, -10, 19, -15, paint);
        boolean winter = "立冬小雪大雪冬至小寒大寒".contains(term);
        if (winter) {
            snowflake(c, -17, -10, 5); snowflake(c, 16, -19, 5); snowflake(c, -3, -25, 4);
        } else {
            c.drawOval(bounds(-23, -18, -10, -4), paint); c.drawOval(bounds(9, -26, 22, -13), paint);
            c.drawOval(bounds(-2, -29, 10, -16), paint);
        }
        c.restore();
    }
    private void snowflake(Canvas c, float x, float y, float size) {
        for (int i = 0; i < 3; i++) {
            double angle = i * Math.PI / 3;
            float dx = (float) Math.cos(angle) * size, dy = (float) Math.sin(angle) * size;
            c.drawLine(x - dx, y - dy, x + dx, y + dy, paint);
        }
    }
    private void weatherIcon(Canvas c, float x, float y, float size, Integer code, boolean night) {
        if (code == null) { fitted(c, "--", x, y + 8, 22, size); return; }
        c.save(); c.translate(x, y); c.scale(size / 100, size / 100);
        boolean clear = code == 0, partly = code == 1 || code == 2;
        if (clear || partly) {
            float sx = partly ? -19 : 0, sy = partly ? -18 : 0;
            stroke(2.5f);
            if (night) {
                Path moon = new Path(); moon.moveTo(sx + 16, sy - 30);
                moon.cubicTo(sx - 27, sy - 37, sx - 46, sy + 23, sx - 4, sy + 32);
                moon.cubicTo(sx + 15, sy + 38, sx + 30, sy + 24, sx + 31, sy + 12);
                moon.cubicTo(sx + 3, sy + 21, sx - 8, sy - 13, sx + 16, sy - 30);
                c.drawPath(moon, paint);
            } else {
                c.drawCircle(sx, sy, 22, paint);
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    c.drawLine(sx + (float) Math.cos(angle) * 31, sy + (float) Math.sin(angle) * 31,
                            sx + (float) Math.cos(angle) * 41, sy + (float) Math.sin(angle) * 41, paint);
                }
            }
        }
        if (!clear) {
            Path cloud = new Path(); cloud.moveTo(-36, 22);
            cloud.cubicTo(-61, 22, -57, -12, -33, -12);
            cloud.cubicTo(-30, -43, 7, -46, 22, -21);
            cloud.cubicTo(50, -28, 62, 9, 40, 22); cloud.close();
            fill(); paint.setColor(Color.WHITE); c.drawPath(cloud, paint);
            stroke(2.5f); c.drawPath(cloud, paint);
            if (code >= 51 && code <= 67 || code >= 80 && code <= 82) {
                for (int i = -1; i <= 1; i++) c.drawLine(i * 22 + 3, 34, i * 22 - 4, 48, paint);
            } else if (code == 68 || code == 69 || code >= 71 && code <= 77 || code == 85 || code == 86) {
                for (int i = -1; i <= 1; i++) snowflake(c, i * 24, 43, 6);
            } else if (code >= 95) {
                Path bolt = new Path(); bolt.moveTo(8, 31); bolt.lineTo(-8, 47); bolt.lineTo(5, 47); bolt.lineTo(-2, 61);
                c.drawPath(bolt, paint);
            } else if (code == 45 || code == 48) {
                c.drawLine(-34, 35, 34, 35, paint); c.drawLine(-24, 47, 24, 47, paint);
            }
        }
        c.restore();
    }
    private RectF bounds(float left, float top, float right, float bottom) { oval.set(left, top, right, bottom); return oval; }
    private void fill() { paint.reset(); paint.setAntiAlias(true); paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.FILL); }
    private void stroke(float width) { fill(); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); }
    private void prepareText(float size, boolean bold, Paint.Align align) {
        fill(); paint.setTextSize(size);
        Typeface face = theme == Theme.BOOK ? Typeface.create("serif", bold ? Typeface.BOLD : Typeface.NORMAL)
                : theme == Theme.RETRO ? Typeface.create("monospace", bold ? Typeface.BOLD : Typeface.NORMAL)
                : bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        paint.setTypeface(face); paint.setTextAlign(align);
    }
    private void text(Canvas c, String value, float x, float y, float size, boolean bold, Paint.Align align) {
        prepareText(size, bold, align); c.drawText(value == null ? "--" : value, x, y, paint);
    }
    private void fitted(Canvas c, String value, float x, float y, float size, float maxWidth) {
        fittedText(c, value, x, y, size, maxWidth, Paint.Align.CENTER);
    }
    private void fittedLeft(Canvas c, String value, float x, float y, float size, float maxWidth) {
        fittedText(c, value, x, y, size, maxWidth, Paint.Align.LEFT);
    }
    private void fittedText(Canvas c, String value, float x, float y, float size, float maxWidth, Paint.Align align) {
        prepareText(size, false, align);
        String content = value == null ? "--" : value;
        float width = paint.measureText(content);
        if (width > maxWidth) paint.setTextScaleX(maxWidth / width);
        c.drawText(content, x, y, paint);
    }
    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        boolean horizontal = y1 == y2;
        if (theme == Theme.MINIMAL && horizontal) {
            if (y1 == 447) { stroke(1); c.drawLine(x1, y1, x2, y2, paint); }
            else if (y1 == 680 || y1 == 1042 || y1 == 1274) {
                stroke(1); c.drawLine(CENTER - 22, y1, CENTER + 22, y2, paint);
            }
            return;
        }
        stroke(theme == Theme.RETRO ? 1.6f : 1); c.drawLine(x1, y1, x2, y2, paint);
        if (theme == Theme.BOOK && horizontal && (y1 == 76 || y1 == 680 || y1 == 1042)) {
            stroke(2); c.drawLine(x1, y1 - 5, x2, y2 - 5, paint);
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean performLongClick() { longPressConsumed = true; return super.performLongClick(); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) longPressConsumed = false;
        boolean handled = super.onTouchEvent(event);
        float scale = Math.min(getWidth() / (float) LOGICAL_WIDTH, getHeight() / (float) LOGICAL_HEIGHT);
        if (!longPressConsumed && scale > 0 && actions != null && event.getAction() == MotionEvent.ACTION_UP) {
            float x = (event.getX() - (getWidth() - LOGICAL_WIDTH * scale) / 2) / scale;
            float y = (event.getY() - (getHeight() - LOGICAL_HEIGHT * scale) / 2) / scale;
            if (x < 0 || x > LOGICAL_WIDTH || y < 0 || y > LOGICAL_HEIGHT) return handled;
            if (y <= 76) {
                performClick();
                if (x > 800) actions.onSettingsClick(); else if (x < 720) actions.onCityClick();
                return true;
            }
            if (y < 145 && x > 700) { performClick(); actions.onThemeClick(); return true; }
            if (y >= 145 && y <= 330 && x < 620) {
                performClick(); segmentedClock = !segmentedClock;
                getContext().getSharedPreferences("presentation", Context.MODE_PRIVATE).edit().putBoolean("segmented_clock_" + theme.name(), segmentedClock).apply();
                requestFullRefresh(); return true;
            }
            if (y >= 145 && y <= 490 && x >= 620) { performClick(); actions.onWeatherClick(); return true; }
        }
        return handled;
    }
}
