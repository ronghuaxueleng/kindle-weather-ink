package com.github.ronghuaxueleng.remoteeink;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DashboardJsonTest {
    @Test public void rejectsWrongSchema() {
        try {
            DashboardJson.parse("{\"schema\":\"other\"}");
            fail("Wrong schema must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(DashboardJson.SCHEMA_ID));
        }
    }

    @Test public void sortsDeduplicatesAndFillsForecastAcrossYearBoundary() {
        DashboardJson.DashboardData data = DashboardJson.parse("{\"schema\":\"kindle-dashboard-json/1\","
                + "\"forecast\":[{\"date\":\"2027-01-02\",\"high\":9},"
                + "{\"date\":\"2026-12-30\",\"high\":12},"
                + "{\"date\":\"2026-12-30\",\"high\":99},"
                + "{\"date\":\"2026-02-31\",\"high\":88}]}");
        assertEquals(7, data.forecast.size());
        assertEquals("2026-12-30", data.firstForecastDate());
        assertEquals(Double.valueOf(12), data.forecast.get(0).high);
        assertNull(data.forecast.get(1).high);
        assertEquals(Double.valueOf(9), data.forecast.get(3).high);
        assertEquals("2027-01-05", data.forecast.get(6).date);
    }

    @Test public void numericStringsAndFractionalCodesAreNotNumbers() {
        DashboardJson.DashboardData data = DashboardJson.parse("{\"schema\":\"kindle-dashboard-json/1\","
                + "\"current\":{\"temperature\":\"18\",\"humidity\":46,\"code\":1.5,\"wind_level\":2147483648}}");
        assertNull(data.current.temperature);
        assertNull(data.current.code);
        assertNull(data.current.windLevel);
        assertEquals(Double.valueOf(46), data.current.humidity);
    }

    @Test public void dateValidationIsStrictAndLeapYearsWork() {
        assertFalse(DashboardJson.isIsoDay("2026-02-29"));
        assertFalse(DashboardJson.isIsoDay("2026-99-99"));
        assertTrue(DashboardJson.isIsoDay("2028-02-29"));
        assertEquals("2028-03-01", DashboardJson.addDays("2028-02-29", 1));
    }

    @Test public void demoMatchesProtocolAndContainsSevenDays() {
        DashboardJson.DashboardData data = DashboardJson.parse(DemoData.create("上海"));
        assertEquals("上海", data.city);
        assertEquals(7, data.forecast.size());
        assertTrue(data.weatherSource.contains("演示"));
        assertNotNull(data.current.temperature);
    }

    @Test public void langqingyuanIsSelectableAndExplicitlyUsesBeijingReferenceData() {
        String cityId = "beijing_tongzhou_langqingyuan";
        String label = DashboardFetcher.CITY_TO_SERVER_CITY.get(cityId);
        assertNotNull(label);
        assertTrue(label.contains("北京市通州区朗清园"));
        assertTrue(label.contains("北京参考"));
        assertEquals("北京", DashboardFetcher.serverCityName(cityId));
        assertEquals("上海", DashboardFetcher.serverCityName("shanghai"));
        assertEquals("北京", DashboardFetcher.serverCityName("unknown"));
    }

    @Test public void forecastCannotBeModified() {
        DashboardJson.DashboardData data = DashboardJson.parse(DemoData.create("北京"));
        try {
            data.forecast.clear();
            fail("Forecast should be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(7, data.forecast.size());
        }
    }
}
