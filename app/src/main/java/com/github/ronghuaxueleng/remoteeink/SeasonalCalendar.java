package com.github.ronghuaxueleng.remoteeink;

import com.nlf.calendar.JieQi;
import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Local calendar dates and exact solar-term boundaries, all in Beijing time. */
public final class SeasonalCalendar {
    private static final TimeZone ZONE = TimeZone.getTimeZone("Asia/Shanghai");
    private long checkedAt = Long.MIN_VALUE, validUntil = Long.MIN_VALUE;
    private Snapshot cached;

    public static final class DayInfo {
        public final String date, lunar, jieQi, festival;
        public final List<String> yi, ji;
        private DayInfo(Solar solar) {
            Lunar lunarDate = solar.getLunar();
            date = solar.toYmd();
            lunar = lunarDate.getMonthInChinese() + "月" + lunarDate.getDayInChinese();
            jieQi = lunarDate.getJieQi();
            List<String> names = new ArrayList<>(solar.getFestivals());
            names.addAll(lunarDate.getFestivals());
            if ("清明".equals(jieQi)) names.add("清明节");
            StringBuilder joined = new StringBuilder();
            for (String name : names) {
                if (joined.length() > 0) joined.append("、");
                joined.append(name);
            }
            festival = joined.toString();
            yi = lunarDate.getDayYi();
            ji = lunarDate.getDayJi();
        }
    }

    public static DayInfo dayInfo(String date) {
        if (!DashboardJson.isIsoDay(date)) throw new IllegalArgumentException("无效日期：" + date);
        return new DayInfo(Solar.fromYmd(Integer.parseInt(date.substring(0, 4)),
                Integer.parseInt(date.substring(5, 7)), Integer.parseInt(date.substring(8, 10))));
    }

    public static final class Snapshot {
        public final String termName, termLine, festivalCountdown;
        public final int rotationDay;

        private Snapshot(Solar today, JieQi previous, JieQi next) {
            termName = previous.getName();
            Solar start = previous.getSolar();
            rotationDay = today.subtract(start);
            Solar end = next.getSolar();
            int days = end.subtract(today);
            String countdown = days == 0
                    ? String.format(Locale.CHINA, "今日%02d:%02d交%s", end.getHour(), end.getMinute(), next.getName())
                    : "距" + next.getName() + days + "天";
            termLine = termName + " " + start.getMonth() + "月" + start.getDay() + "日 · " + countdown;
            festivalCountdown = nextFestivalText(today);
        }
    }

    public Snapshot at(long now) {
        // Recompute at midnight, the next term boundary, or after a clock rollback.
        if (cached == null || now < checkedAt || now >= validUntil) {
            Calendar clock = Calendar.getInstance(ZONE);
            clock.setTimeInMillis(now);
            Solar today = Solar.fromCalendar(clock);
            Lunar lunar = today.getLunar();
            JieQi previous = lunar.getPrevJieQi(false);
            JieQi next = lunar.getNextJieQi(false);
            cached = new Snapshot(today, previous, next);
            clock.add(Calendar.DAY_OF_MONTH, 1);
            clock.set(Calendar.HOUR_OF_DAY, 0);
            clock.set(Calendar.MINUTE, 0);
            clock.set(Calendar.SECOND, 0);
            clock.set(Calendar.MILLISECOND, 0);
            validUntil = Math.min(clock.getTimeInMillis(), millis(next.getSolar()));
            checkedAt = now;
        }
        return cached;
    }

    private static long millis(Solar solar) {
        Calendar clock = Calendar.getInstance(ZONE);
        clock.clear();
        clock.set(solar.getYear(), solar.getMonth() - 1, solar.getDay(),
                solar.getHour(), solar.getMinute(), solar.getSecond());
        return clock.getTimeInMillis();
    }

    static String nextFestivalText(Solar today) {
        // Standard solar/lunar festivals, movable weekday festivals, and Qingming.
        // Start tomorrow: today's festival is already shown on the line above.
        for (int days = 1; days <= 366; days++) {
            Solar date = today.next(days);
            List<String> festivals = new ArrayList<>(date.getFestivals());
            Lunar lunar = date.getLunar();
            festivals.addAll(lunar.getFestivals());
            if ("清明".equals(lunar.getJieQi())) festivals.add("清明节");
            if (!festivals.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (String name : festivals) {
                    if (names.length() > 0) names.append("、");
                    names.append(name);
                }
                return "距" + names + "还有" + days + "天";
            }
        }
        return "下次节日 --";
    }
}
