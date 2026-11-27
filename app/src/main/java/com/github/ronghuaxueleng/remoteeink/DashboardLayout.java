package com.github.ronghuaxueleng.remoteeink;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Dashboard layout, formatting and data freshness helpers. */
public final class DashboardLayout {
    public static final int WIDTH = 1072, HEIGHT = 1448;
    public static final long STALE_AFTER_SECONDS = 1800;
    public static final String WEATHER_STALE = "天气数据已过期";
    public static final String WEATHER_UNAVAILABLE = "天气数据不可用";
    public static final String FORECAST_TITLE = "未来七天天气预报";
    public static final String WATERMARK_TEXT = "Gary";
    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");
    private DashboardLayout() {}
    public interface TextWidth { float width(String text, float size); }
    public static final class FullRefreshPolicy {
        public static final int FULL_REFRESH_MINUTE_TICKS = 10;
        private FullRefreshPolicy() {}
        public static boolean shouldFullRefreshOnStart() { return true; }
        public static boolean shouldFullRefreshOnDataRefresh() { return true; }
        public static boolean shouldFullRefreshOnSettingsReturn() { return true; }
        public static boolean shouldFullRefreshOnMinuteTick(int count) {
            return count > 0 && count % FULL_REFRESH_MINUTE_TICKS == 0;
        }
    }
    public static final class YijiLayout {
        public final float size, firstBaseline, lineStep;
        public final List<String> yiLines, jiLines;
        public final int originalLineCount;
        YijiLayout(float size, List<String> yi, List<String> ji, float baseline, float step, int count) {
            this.size = size; yiLines = yi; jiLines = ji;
            firstBaseline = baseline; lineStep = step; originalLineCount = count;
        }
    }
    public static String clockText(long now) { return format(now, "HH:mm"); }
    private static String format(long time, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.CHINA);
        format.setTimeZone(SHANGHAI);
        return format.format(new Date(time));
    }
    public static String dateText(long now, String[] weekdays) {
        Calendar c = Calendar.getInstance(SHANGHAI); c.setTimeInMillis(now);
        String[] fallback = {"日", "一", "二", "三", "四", "五", "六"};
        return (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 星期"
                + (weekdays == null ? fallback : weekdays)[c.get(Calendar.DAY_OF_WEEK) - 1].replace("星期", "").replace("周", "");
    }
    public static Long isoMillis(String input) {
        if (input == null) return null;
        String text = input.trim().replace(' ', 'T');
        if (text.endsWith("Z") || text.endsWith("z")) text = text.substring(0, text.length() - 1) + "+0000";
        text = text.replaceFirst("([+-][0-9]{2}):([0-9]{2})$", "$1$2");
        String[] formats = {"yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mmZ", "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm"};
        for (String pattern : formats) {
            SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
            parser.setTimeZone(SHANGHAI); parser.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date result = parser.parse(text, position);
            if (result != null && position.getIndex() == text.length()) return result.getTime();
        }
        return null;
    }
    private static boolean isDataStale(Long updated, Long observed, long now, boolean stale) {
        Long reference = updated;
        if (observed != null && (reference == null || observed < reference)) reference = observed;
        return stale || reference != null && now - reference > STALE_AFTER_SECONDS * 1000L;
    }
    public static String weatherNotice(Long updated, Long observed, long now, boolean stale) {
        if (updated == null && observed == null) return WEATHER_UNAVAILABLE;
        return isDataStale(updated, observed, now, stale) ? WEATHER_STALE : null;
    }
    public static String weatherNoticeText(DashboardJson.DashboardData d, long now) {
        if (d == null) return WEATHER_UNAVAILABLE;
        return weatherNotice(isoMillis(d.weatherUpdatedAt), d.current == null ? null : isoMillis(d.current.observationTime), now, d.isWeatherStale());
    }
    public static boolean isAirStale(DashboardJson.DashboardData d, long now) {
        return d != null && isDataStale(isoMillis(d.airUpdatedAt),
                d.air == null ? null : isoMillis(d.air.observationTime), now, d.isAirStale());
    }
    public static String cityLineText(DashboardJson.DashboardData d) {
        return "● 您喜欢的城市：" + (d == null ? "--" : d.city) + " ▼";
    }
    public static String phpNumber(Double n) {
        return n == null || n.isNaN() || n.isInfinite() ? "--" : String.valueOf(phpRound(n));
    }
    public static long phpRound(double n) {
        return (long) (n < 0 ? Math.ceil(n - 0.5) : Math.floor(n + 0.5));
    }
    public static String kindleNumber(Double n, String unit) { return n == null ? "--" : phpNumber(n) + unit; }
    public static String temperatureText(DashboardJson.Current c) { return kindleNumber(c == null ? null : c.temperature, "°C"); }
    public static String feelsLikeText(DashboardJson.Current c) { return kindleNumber(c == null ? null : c.feelsLike, "°C"); }
    public static String humidityText(DashboardJson.Current c) { return kindleNumber(c == null ? null : c.humidity, "%"); }
    public static String highLowText(DashboardJson.Current c) {
        return "最高 " + kindleNumber(c == null ? null : c.high, "°") + "   最低 " + kindleNumber(c == null ? null : c.low, "°");
    }
    public static String weatherLabelText(DashboardJson.Current c) { return c == null ? "--" : c.label; }
    public static String windLineText(DashboardJson.Current c) {
        return c == null ? "--" : c.windDirectionLabel + " " + (c.windLevel == null ? "--" : c.windLevel + "级");
    }
    public static String windSpeedText(Double speed) { return speed == null ? "-- m/s" : String.format(Locale.US, "%.1f m/s", speed); }
    public static String windSpeedLineText(DashboardJson.Current c) { return windSpeedText(c == null ? null : c.windMs); }
    public static String pmText(DashboardJson.Air air) { return phpNumber(air == null ? null : air.pm25) + " μg/m³"; }
    public static String pmGradeText(DashboardJson.Air air) { return air == null || "--".equals(air.pm25Grade) ? "--" : air.pm25Grade + "（参考）"; }
    public static String termFestivalText(DashboardJson.Almanac a) { return a == null ? "节气：--  节日：--" : "节气：" + a.term + "  节日：" + a.festival; }
    public static String monthDayText(String day) {
        if (!DashboardJson.isIsoDay(day)) return "--";
        return Integer.parseInt(day.substring(5, 7)) + "/" + Integer.parseInt(day.substring(8, 10));
    }
    public static String forecastWeekdayText(DashboardJson.ForecastDay day, String[] names) {
        if (day == null || !DashboardJson.isIsoDay(day.date)) return "--";
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US); f.setTimeZone(SHANGHAI);
        Calendar c = Calendar.getInstance(SHANGHAI);
        c.setTime(f.parse(day.date, new ParsePosition(0)));
        return "周" + names[c.get(Calendar.DAY_OF_WEEK) - 1].replace("星期", "").replace("周", "");
    }
    public static DashboardJson.ForecastDay forecastDayAt(DashboardJson.DashboardData d, int index) {
        return d == null || index < 0 || index >= d.forecast.size() ? null : d.forecast.get(index);
    }
    public static String forecastDateText(DashboardJson.ForecastDay day) { return day == null ? "--" : monthDayText(day.date); }
    public static String forecastLabelText(DashboardJson.ForecastDay day) { return day == null ? "--" : day.label; }
    public static String forecastHighLowText(DashboardJson.ForecastDay day) {
        return phpNumber(day == null ? null : day.high) + " / " + phpNumber(day == null ? null : day.low) + "°";
    }
    public static List<String> wrapItems(List<String> items, float size, float maxWidth, TextWidth width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String item : items) {
            if (item == null) continue;
            String candidate = line.length() == 0 ? item : line + "  " + item;
            if (line.length() > 0 && width.width(candidate, size) > maxWidth) {
                lines.add(line.toString()); line.setLength(0);
            }
            if (line.length() > 0) line.append("  ");
            line.append(item);
        }
        if (line.length() > 0) lines.add(line.toString());
        if (lines.isEmpty()) lines.add("--");
        return lines;
    }
    public static YijiLayout yiji(List<String> yi, List<String> ji, TextWidth width) {
        float size = 28;
        List<String> left, right;
        do {
            left = wrapItems(yi, size, 370, width); right = wrapItems(ji, size, 370, width);
            if (Math.max(left.size(), right.size()) <= 4 || size <= 18) break;
            size -= 1;
        } while (true);
        int count = Math.max(left.size(), right.size());
        return new YijiLayout(size, truncate(left), truncate(right), 1290, 34, count);
    }
    private static List<String> truncate(List<String> lines) {
        if (lines.size() <= 4) return Collections.unmodifiableList(lines);
        List<String> result = new ArrayList<>(lines.subList(0, 4));
        result.set(3, "内容较多，请查看完整黄历");
        return Collections.unmodifiableList(result);
    }
}
