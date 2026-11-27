package com.github.ronghuaxueleng.remoteeink;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DashboardLayoutTest {
    @Test public void staleFlagAndElapsedTimeBothProduceVisibleNotice() {
        long now = 2000000000L;
        assertNull(DashboardLayout.weatherNotice(now - 1000, null, now, false));
        assertEquals(DashboardLayout.WEATHER_STALE, DashboardLayout.weatherNotice(now - 1000, null, now, true));
        assertEquals(DashboardLayout.WEATHER_STALE, DashboardLayout.weatherNotice(now - 1800001, null, now, false));
        assertEquals(DashboardLayout.WEATHER_UNAVAILABLE, DashboardLayout.weatherNotice(null, null, now, false));
    }

    @Test public void airUsesOlderOfUpdateAndObservationTimes() throws Exception {
        long now = DashboardLayout.isoMillis("2026-11-27T12:00:00+08:00");
        String fresh = "2026-11-27T11:59:00+08:00";
        String old = "2026-11-27T11:00:00+08:00";
        assertTrue(DashboardLayout.isAirStale(airData(fresh, old, false), now));
        assertTrue(DashboardLayout.isAirStale(airData(old, fresh, false), now));
        assertFalse(DashboardLayout.isAirStale(airData(fresh, fresh, false), now));
        assertEquals(DashboardLayout.WEATHER_STALE,
                DashboardLayout.weatherNotice(now - 1000, now - 1800001, now, false));
    }

    @Test public void airFallsBackToEitherValidTimestampAndHonorsServerFlag() throws Exception {
        long now = DashboardLayout.isoMillis("2026-11-27T12:00:00+08:00");
        String fresh = "2026-11-27T11:59:00+08:00";
        String old = "2026-11-27T11:00:00+08:00";
        assertTrue(DashboardLayout.isAirStale(airData("invalid", old, false), now));
        assertTrue(DashboardLayout.isAirStale(airData(old, null, false), now));
        assertFalse(DashboardLayout.isAirStale(airData(null, fresh, false), now));
        assertFalse(DashboardLayout.isAirStale(airData(fresh, "invalid", false), now));
        assertFalse(DashboardLayout.isAirStale(airData(null, null, false), now));
        assertTrue(DashboardLayout.isAirStale(airData(fresh, fresh, true), now));
        assertTrue(DashboardLayout.isAirStale(airData(null, null, true), now));
        assertFalse(DashboardLayout.isAirStale(null, now));
    }

    @Test public void airBecomesStaleOnlyAfterThirtyMinutes() throws Exception {
        long now = DashboardLayout.isoMillis("2026-11-27T12:00:00+08:00");
        DashboardJson.DashboardData data = airData("2026-11-27T11:30:00+08:00", null, false);
        assertFalse(DashboardLayout.isAirStale(data, now));
        assertTrue(DashboardLayout.isAirStale(data, now + 1));
    }

    private static DashboardJson.DashboardData airData(String updated, String observed, boolean stale) throws Exception {
        JSONObject root = new JSONObject().put("schema", DashboardJson.SCHEMA_ID)
                .put("air_updated_at", updated).put("air_stale", stale)
                .put("air", new JSONObject().put("observation_time", observed));
        return DashboardJson.parse(root.toString());
    }

    @Test public void isoTimeSupportsOffsetAndRejectsImpossibleDates() {
        assertEquals(DashboardLayout.isoMillis("2026-11-27T12:00:00+08:00"),
                DashboardLayout.isoMillis("2026-11-27T04:00:00Z"));
        assertNotNull(DashboardLayout.isoMillis("2026-11-27T12:00:00.123+08:00"));
        assertNull(DashboardLayout.isoMillis("2026-02-31T12:00:00+08:00"));
        assertNull(DashboardLayout.isoMillis("invalid"));
    }

    @Test public void roundingUsesHalfAwayFromZero() {
        assertEquals(2, DashboardLayout.phpRound(1.5));
        assertEquals(-2, DashboardLayout.phpRound(-1.5));
        assertEquals("--", DashboardLayout.phpNumber(null));
    }

    @Test public void fullRefreshRunsEveryTenMinuteTicks() {
        assertFalse(DashboardLayout.FullRefreshPolicy.shouldFullRefreshOnMinuteTick(0));
        assertFalse(DashboardLayout.FullRefreshPolicy.shouldFullRefreshOnMinuteTick(9));
        assertTrue(DashboardLayout.FullRefreshPolicy.shouldFullRefreshOnMinuteTick(10));
        assertTrue(DashboardLayout.FullRefreshPolicy.shouldFullRefreshOnMinuteTick(20));
    }
}
