package com.github.ronghuaxueleng.remoteeink;

import com.nlf.calendar.Solar;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import org.junit.Test;
import static org.junit.Assert.*;

public class SeasonalCalendarTest {
    private static long time(String text) { return DashboardLayout.isoMillis(text); }

    @Test public void datesAndCountdownUseBeijingTimeAcrossDefaultTimeZones() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            SeasonalCalendar.Snapshot value = new SeasonalCalendar().at(time("2026-11-27T23:00:00+08:00"));
            assertEquals("小雪 11月22日 · 距大雪10天", value.termLine);
            assertEquals("距平安夜还有27天", value.festivalCountdown);
            assertEquals(5, value.rotationDay);
        } finally { TimeZone.setDefault(original); }
    }

    @Test public void midnightRotatesPoemsAndUpdatesCountdownWithoutFetching() {
        SeasonalCalendar calendar = new SeasonalCalendar();
        SeasonalCalendar.Snapshot before = calendar.at(time("2026-11-27T23:59:59+08:00"));
        SeasonalCalendar.Snapshot after = calendar.at(time("2026-11-28T00:00:00+08:00"));
        assertEquals(6, after.rotationDay);
        assertEquals("距平安夜还有26天", after.festivalCountdown);
        assertNotEquals(DashboardLayout.termPoemText(before.termName, before.rotationDay),
                DashboardLayout.termPoemText(after.termName, after.rotationDay));
        // A clock correction must also invalidate the cached day.
        assertEquals(5, calendar.at(time("2026-11-27T23:00:00+08:00")).rotationDay);
    }

    @Test public void termChangesAtItsInstantInsteadOfAtMidnight() {
        Solar boundary = Solar.fromYmd(2026, 12, 1).getLunar().getNextJieQi(false).getSolar();
        assertEquals("2026-12-07", boundary.toYmd()); // HKO 2026 calendar.
        long instant = time(boundary.toYmdHms() + "+08:00");
        SeasonalCalendar calendar = new SeasonalCalendar();
        SeasonalCalendar.Snapshot before = calendar.at(instant - 1000);
        assertEquals("小雪", before.termName);
        assertTrue(before.termLine.contains("今日"));
        SeasonalCalendar.Snapshot after = calendar.at(instant);
        assertEquals("大雪", after.termName);
        assertEquals(0, after.rotationDay);
        assertTrue(after.termLine.startsWith("大雪 12月7日"));
    }

    @Test public void futureFestivalsHandleYearEndLunarNewYearAndQingming() {
        assertEquals("距元旦节还有1天", SeasonalCalendar.nextFestivalText(Solar.fromYmd(2026, 12, 31)));
        // HKO 2027 calendar: lunar New Year is February 6; the preceding month has 29 days.
        assertEquals("距除夕还有1天", SeasonalCalendar.nextFestivalText(Solar.fromYmd(2027, 2, 4)));
        assertEquals("距春节还有1天", SeasonalCalendar.nextFestivalText(Solar.fromYmd(2027, 2, 5)));
        assertEquals("距清明节还有1天", SeasonalCalendar.nextFestivalText(Solar.fromYmd(2027, 4, 4)));
    }

    @Test public void everyTermRotatesAtLeastThreeDifferentPoems() {
        String[] terms = {"立春", "雨水", "惊蛰", "春分", "清明", "谷雨", "立夏", "小满", "芒种", "夏至", "小暑", "大暑",
                "立秋", "处暑", "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至", "小寒", "大寒"};
        for (String term : terms) {
            Set<String> poems = new HashSet<>();
            int count = "小雪".equals(term) ? 6 : 3;
            for (int day = 0; day < count; day++) poems.add(DashboardLayout.termPoemText(term, day));
            assertEquals(term, count, poems.size());
            assertFalse(poems.contains("--"));
            assertEquals(DashboardLayout.termPoemText(term, 0), DashboardLayout.termPoemText(term, count));
        }
        assertEquals("--", DashboardLayout.termPoemText("未知", 0));
    }
}
