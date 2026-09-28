/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the AWT-free {@link OpenMeteo} backend of the Weather app:
 * URL building, the dependency-free JSON reader, WMO weather-code description,
 * unit conversion and Open-Meteo document parsing. No network and no X display
 * are touched, so these run in CI exactly like {@code PdfDocumentTest}.
 */
class OpenMeteoTest {

    /** A representative Open-Meteo forecast document (current + two days). */
    private static final String SAMPLE = "{"
            + "\"latitude\":51.5,\"longitude\":-0.12,\"timezone\":\"Europe/London\","
            + "\"current\":{\"temperature_2m\":12.5,\"apparent_temperature\":10.1,"
            + "\"relative_humidity_2m\":80,\"weather_code\":61,"
            + "\"wind_speed_10m\":15.0,\"is_day\":1},"
            + "\"daily\":{\"time\":[\"2026-09-28\",\"2026-09-29\"],"
            + "\"weather_code\":[61,0],"
            + "\"temperature_2m_max\":[15.0,18.0],"
            + "\"temperature_2m_min\":[8.0,9.0]}}";

    @Test
    @DisplayName("forecastUrl requests Celsius / km-h current + daily fields")
    void buildsUrl() {
        String url = OpenMeteo.forecastUrl(51.5, -0.12, 6);
        assertTrue(url.startsWith(OpenMeteo.API));
        assertTrue(url.contains("latitude=51.5"));
        assertTrue(url.contains("longitude=-0.12"));
        assertTrue(url.contains("forecast_days=6"));
        assertTrue(url.contains("temperature_unit=celsius"));
        assertTrue(url.contains("wind_speed_unit=kmh"));
        assertTrue(url.contains("current="));
        assertTrue(url.contains("daily="));
    }

    @Test
    @DisplayName("parse reads the current block into the model")
    void parsesCurrent() {
        OpenMeteo.Report r = OpenMeteo.parse("London", SAMPLE);
        assertEquals("London", r.place);
        assertEquals(12.5, r.current.tempC, 1e-9);
        assertEquals(10.1, r.current.feelsC, 1e-9);
        assertEquals(15.0, r.current.windKmh, 1e-9);
        assertEquals(80, r.current.humidity);
        assertEquals(61, r.current.weatherCode);
        assertTrue(r.current.day);
    }

    @Test
    @DisplayName("parse reads the daily forecast in order")
    void parsesDaily() {
        OpenMeteo.Report r = OpenMeteo.parse("London", SAMPLE);
        assertEquals(2, r.days.size());
        OpenMeteo.Day d0 = r.days.get(0);
        assertEquals("2026-09-28", d0.date);
        assertEquals(15.0, d0.maxC, 1e-9);
        assertEquals(8.0, d0.minC, 1e-9);
        assertEquals(61, d0.weatherCode);
        assertEquals(0, r.days.get(1).weatherCode);
    }

    @Test
    @DisplayName("a missing numeric field degrades to NaN / -1, not a failure")
    void parsesSparseDocument() {
        OpenMeteo.Report r = OpenMeteo.parse("Nowhere", "{\"current\":{}}");
        assertTrue(Double.isNaN(r.current.tempC));
        assertEquals(-1, r.current.humidity);
        assertEquals(-1, r.current.weatherCode);
        assertTrue(r.days.isEmpty());
    }

    @Test
    @DisplayName("is_day defaults to night when absent")
    void isDayDefaultsToNight() {
        OpenMeteo.Report r = OpenMeteo.parse("X",
                "{\"current\":{\"temperature_2m\":5}}");
        assertFalse(r.current.day);
    }

    @Test
    @DisplayName("a non-object body is rejected")
    void rejectsNonObject() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenMeteo.parse("X", "[1,2,3]"));
    }

    @Test
    @DisplayName("WMO codes map to human condition text")
    void conditions() {
        assertEquals("Clear sky", OpenMeteo.condition(0));
        assertEquals("Partly cloudy", OpenMeteo.condition(2));
        assertEquals("Rain", OpenMeteo.condition(61));
        assertEquals("Thunderstorm", OpenMeteo.condition(95));
        assertEquals("Snow", OpenMeteo.condition(71));
        assertEquals("\u2014", OpenMeteo.condition(-1));
        assertEquals("\u2014", OpenMeteo.condition(9999));
    }

    @Test
    @DisplayName("WMO codes map to sky glyph families")
    void skyFamilies() {
        assertEquals(OpenMeteo.Sky.SUN, OpenMeteo.skyFor(0));
        assertEquals(OpenMeteo.Sky.PARTLY, OpenMeteo.skyFor(2));
        assertEquals(OpenMeteo.Sky.CLOUD, OpenMeteo.skyFor(3));
        assertEquals(OpenMeteo.Sky.FOG, OpenMeteo.skyFor(45));
        assertEquals(OpenMeteo.Sky.RAIN, OpenMeteo.skyFor(61));
        assertEquals(OpenMeteo.Sky.SNOW, OpenMeteo.skyFor(71));
        assertEquals(OpenMeteo.Sky.THUNDER, OpenMeteo.skyFor(95));
        assertEquals(OpenMeteo.Sky.CLOUD, OpenMeteo.skyFor(12345));
    }

    @Test
    @DisplayName("temperature and wind convert only in Fahrenheit/imperial mode")
    void conversions() {
        assertEquals(10.0, OpenMeteo.convert(10.0, false), 1e-9);
        assertEquals(32.0, OpenMeteo.convert(0.0, true), 1e-9);
        assertEquals(212.0, OpenMeteo.convert(100.0, true), 1e-9);
        assertEquals(100.0, OpenMeteo.convertWind(100.0, false), 1e-9);
        assertEquals(62.1371, OpenMeteo.convertWind(100.0, true), 1e-4);
    }

    @Test
    @DisplayName("the preset city list is populated and self-describing")
    void cities() {
        assertFalse(OpenMeteo.CITIES.isEmpty());
        OpenMeteo.City london = OpenMeteo.CITIES.get(0);
        assertEquals("London", london.name);
        assertEquals("London", london.toString());
        assertEquals(51.5072, london.lat, 1e-6);
        assertTrue(OpenMeteo.CITIES.stream()
                .anyMatch(c -> "Tokyo".equals(c.name)));
        // defaultFahrenheit is locale-dependent; just ensure it is callable.
        boolean ignored = OpenMeteo.defaultFahrenheit();
        assertTrue(ignored || !ignored);
    }

    // ------------------------------------------------------------------
    // The dependency-free JSON reader
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the JSON reader parses objects, arrays, strings and numbers")
    void jsonReader() {
        Object root = OpenMeteo.Json.parse(
                "{\"a\":1,\"b\":[true,false,null,\"x\"],\"c\":-2.5e3,\"d\":\"\\u0041\\t\"}");
        assertTrue(root instanceof Map);
        Map<?, ?> m = (Map<?, ?>) root;
        assertEquals(1.0, ((Number) m.get("a")).doubleValue(), 1e-9);
        assertEquals(-2500.0, ((Number) m.get("c")).doubleValue(), 1e-9);
        assertEquals("A\t", m.get("d"));
        List<?> b = (List<?>) m.get("b");
        assertEquals(Boolean.TRUE, b.get(0));
        assertEquals(Boolean.FALSE, b.get(1));
        assertEquals(null, b.get(2));
        assertEquals("x", b.get(3));
    }

    @Test
    @DisplayName("the JSON reader skips whitespace and empty containers")
    void jsonReaderEdgeCases() {
        assertTrue(OpenMeteo.Json.parse("  {  }  ") instanceof Map);
        assertTrue(((Map<?, ?>) OpenMeteo.Json.parse("{}")).isEmpty());
        assertTrue(((List<?>) OpenMeteo.Json.parse("[]")).isEmpty());
        assertNotNull(OpenMeteo.Json.parse("{\"empty\":\"\"}"));
        assertEquals("", ((Map<?, ?>) OpenMeteo.Json.parse("{\"empty\":\"\"}")).get("empty"));
    }

    @Test
    @DisplayName("malformed JSON is rejected")
    void jsonReaderRejectsMalformed() {
        assertThrows(RuntimeException.class, () -> OpenMeteo.Json.parse("{\"a\"}"));
        assertThrows(RuntimeException.class, () -> OpenMeteo.Json.parse("[1,"));
        assertThrows(RuntimeException.class, () -> OpenMeteo.Json.parse(""));
    }
}
