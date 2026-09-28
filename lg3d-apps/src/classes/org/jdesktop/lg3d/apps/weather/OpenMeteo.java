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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The AWT-free weather backend of the Weather app: it talks to the free
 * <a href="https://open-meteo.com/">Open-Meteo</a> forecast API (no API key, no
 * third-party library) exactly like the desktop's Weather widget does, using
 * only the JDK's {@link HttpClient} and a small dependency-free JSON reader.
 *
 * <p>This is the seam the Swing {@link WeatherPanel} renders: all fetching,
 * parsing, WMO weather-code description and unit conversion live here, so the
 * logic is exercised headlessly by {@code OpenMeteoTest} with no network and no
 * X display. Data is always requested in Celsius / km-h and converted for
 * display, so toggling &deg;C/&deg;F never re-fetches.</p>
 */
public final class OpenMeteo {

    /** Open-Meteo forecast endpoint (free, key-less). */
    static final String API = "https://api.open-meteo.com/v1/forecast";

    /** Open-Meteo geocoding endpoint (free, key-less): name -> coordinates. */
    static final String GEOCODING_API = "https://geocoding-api.open-meteo.com/v1/search";

    /** How many days of daily forecast to request. */
    static final int FORECAST_DAYS = 6;

    /** How many geocoding candidates to request when adding a location. */
    static final int SEARCH_COUNT = 5;

    /** Shared client; no connection is made at construction. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private OpenMeteo() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Preset cities
    // ------------------------------------------------------------------

    /** A location: a display name, an optional country, and WGS-84 coordinates. */
    public static final class City {
        public final String name;
        public final String country;
        public final double lat;
        public final double lon;

        City(String name, double lat, double lon) {
            this(name, null, lat, lon);
        }

        /**
         * Creates a location, e.g. one the user added by geocoding search or by
         * entering coordinates directly.
         *
         * @param country an optional country/region for display, may be null
         */
        public City(String name, String country, double lat, double lon) {
            this.name = name;
            this.country = country;
            this.lat = lat;
            this.lon = lon;
        }

        @Override
        public String toString() {
            return (country == null || country.isEmpty())
                    ? name : name + ", " + country;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof City)) {
                return false;
            }
            City c = (City) o;
            // Coordinates identify a location; round to ~11 m to absorb the
            // float noise of a re-parse without treating distinct places as one.
            return Math.round(lat * 1e4) == Math.round(c.lat * 1e4)
                    && Math.round(lon * 1e4) == Math.round(c.lon * 1e4)
                    && java.util.Objects.equals(name, c.name);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name,
                    Math.round(lat * 1e4), Math.round(lon * 1e4));
        }
    }

    /** Preset major world cities shown in the app's city list. */
    public static final List<City> CITIES = Collections.unmodifiableList(List.of(
            new City("London", 51.5072, -0.1276),
            new City("Paris", 48.8566, 2.3522),
            new City("New York", 40.7128, -74.0060),
            new City("San Francisco", 37.7749, -122.4194),
            new City("Toronto", 43.6532, -79.3832),
            new City("Berlin", 52.5200, 13.4050),
            new City("Madrid", 40.4168, -3.7038),
            new City("Rome", 41.9028, 12.4964),
            new City("Tokyo", 35.6762, 139.6503),
            new City("Sydney", -33.8688, 151.2093)));

    /**
     * The default temperature unit for the current locale: a handful of
     * countries still use Fahrenheit day-to-day, everyone else Celsius.
     *
     * @return {@code true} for Fahrenheit, {@code false} for Celsius
     */
    public static boolean defaultFahrenheit() {
        String c = Locale.getDefault().getCountry();
        return "US".equals(c) || "LR".equals(c) || "BS".equals(c)
                || "BZ".equals(c) || "KY".equals(c) || "PW".equals(c);
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    /** The current conditions block of a {@link Report}. */
    public static final class Current {
        public final double tempC;
        public final double feelsC;
        public final double windKmh;
        public final int humidity;
        public final int weatherCode;
        public final boolean day;

        Current(double tempC, double feelsC, double windKmh,
                int humidity, int weatherCode, boolean day) {
            this.tempC = tempC;
            this.feelsC = feelsC;
            this.windKmh = windKmh;
            this.humidity = humidity;
            this.weatherCode = weatherCode;
            this.day = day;
        }
    }

    /** One day of the daily forecast: an ISO date, its extremes and sky. */
    public static final class Day {
        public final String date;
        public final double minC;
        public final double maxC;
        public final int weatherCode;

        Day(String date, double minC, double maxC, int weatherCode) {
            this.date = date;
            this.minC = minC;
            this.maxC = maxC;
            this.weatherCode = weatherCode;
        }
    }

    /** A parsed weather report for one location. */
    public static final class Report {
        public final String place;
        public final Current current;
        public final List<Day> days;

        Report(String place, Current current, List<Day> days) {
            this.place = place;
            this.current = current;
            this.days = days;
        }
    }

    // ------------------------------------------------------------------
    // URL + fetch
    // ------------------------------------------------------------------

    /**
     * Builds the Open-Meteo forecast URL for a location. Always requests
     * Celsius / km-h (display converts), the current block plus {@code days}
     * days of daily extremes and weather codes.
     */
    static String forecastUrl(double lat, double lon, int days) {
        return API
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,"
                + "relative_humidity_2m,weather_code,wind_speed_10m,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&forecast_days=" + days
                + "&timezone=auto"
                + "&temperature_unit=celsius&wind_speed_unit=kmh";
    }

    /**
     * Fetches and parses the forecast for {@code city} (blocking; call off the
     * EDT).
     *
     * @throws IOException          on a transport or non-2xx HTTP error
     * @throws InterruptedException if the calling thread is interrupted
     */
    public static Report fetch(City city) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(forecastUrl(city.lat, city.lon, FORECAST_DAYS)))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "lg3d-weather-app/1.0")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        return parse(city.name, resp.body());
    }

    // ------------------------------------------------------------------
    // Parse (AWT-free, unit-tested)
    // ------------------------------------------------------------------

    /**
     * Parses an Open-Meteo forecast document into a {@link Report}. Missing
     * numeric fields become {@link Double#NaN} / {@code -1} so the panel can
     * omit them rather than fail the whole report.
     *
     * @throws IllegalArgumentException if the body is not a JSON object
     */
    static Report parse(String place, String body) {
        Object root = Json.parse(body);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Not a JSON object");
        }
        Map<?, ?> m = (Map<?, ?>) root;
        Map<?, ?> cur = asMap(m.get("current"));
        Map<?, ?> daily = asMap(m.get("daily"));

        double tempC = num(cur, "temperature_2m");
        double feelsC = num(cur, "apparent_temperature");
        double windKmh = num(cur, "wind_speed_10m");
        double hum = num(cur, "relative_humidity_2m");
        double code = num(cur, "weather_code");
        double isDay = num(cur, "is_day");
        Current current = new Current(
                tempC, feelsC, windKmh,
                Double.isNaN(hum) ? -1 : (int) Math.round(hum),
                Double.isNaN(code) ? -1 : (int) Math.round(code),
                !Double.isNaN(isDay) && isDay >= 0.5);

        List<Day> days = new ArrayList<>();
        if (daily != null) {
            List<?> dates = asList(daily.get("time"));
            List<?> codes = asList(daily.get("weather_code"));
            List<?> maxs = asList(daily.get("temperature_2m_max"));
            List<?> mins = asList(daily.get("temperature_2m_min"));
            int n = (dates == null) ? 0 : dates.size();
            for (int i = 0; i < n; i++) {
                String date = String.valueOf(dates.get(i));
                days.add(new Day(date,
                        numAt(mins, i), numAt(maxs, i),
                        (int) Math.round(numAt(codes, i))));
            }
        }
        return new Report(place, current, days);
    }

    // ------------------------------------------------------------------
    // Geocoding search
    // ------------------------------------------------------------------

    /**
     * Searches the Open-Meteo geocoding API for locations matching {@code query}.
     * Returns up to {@link #SEARCH_COUNT} candidates.
     *
     * @param query a city name or partial name (e.g., "Paris", "San Fran")
     * @return a list of matching cities, never null (empty on error/no results)
     * @throws IOException          on transport or non-2xx HTTP error
     * @throws InterruptedException if the calling thread is interrupted
     */
    public static List<City> search(String query) throws IOException, InterruptedException {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String url = GEOCODING_API + "?name=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")
                + "&count=" + SEARCH_COUNT + "&language=en&format=json";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "lg3d-weather-app/1.0")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            return Collections.emptyList();
        }
        return parseGeocoding(resp.body());
    }

    /**
     * Parses the Open-Meteo geocoding JSON response into a list of {@link City}
     * objects. The format is {@code {"results": [{"name": "...", "country": "...",
     * "latitude": ..., "longitude": ...}, ...]}}.
     */
    static List<City> parseGeocoding(String body) {
        Object root = Json.parse(body);
        if (!(root instanceof Map)) {
            return Collections.emptyList();
        }
        Map<?, ?> m = (Map<?, ?>) root;
        List<?> results = asList(m.get("results"));
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        List<City> cities = new ArrayList<>();
        for (Object o : results) {
            Map<?, ?> item = asMap(o);
            if (item == null) {
                continue;
            }
            String name = string(item, "name");
            String country = string(item, "country");
            double lat = num(item, "latitude");
            double lon = num(item, "longitude");
            if (name != null && !Double.isNaN(lat) && !Double.isNaN(lon)) {
                cities.add(new City(name, country, lat, lon));
            }
        }
        return cities;
    }

    private static String string(Map<?, ?> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        return (v instanceof String) ? (String) v : null;
    }

    // ------------------------------------------------------------------
    // Persistence (custom cities)
    // ------------------------------------------------------------------

    /**
     * Saves a list of cities to a JSON file. Each city is serialized as
     * {@code {"name": "...", "country": "...", "lat": ..., "lon": ...}}.
     *
     * @param cities the cities to save
     * @param path   the file path to write to
     * @throws IOException on write error
     */
    public static void saveCities(List<City> cities, Path path) throws IOException {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < cities.size(); i++) {
            City c = cities.get(i);
            sb.append("  {\"name\":").append(Json.quote(c.name));
            if (c.country != null && !c.country.isEmpty()) {
                sb.append(",\"country\":").append(Json.quote(c.country));
            }
            sb.append(",\"lat\":").append(c.lat);
            sb.append(",\"lon\":").append(c.lon);
            sb.append('}');
            if (i < cities.size() - 1) {
                sb.append(",\n");
            }
        }
        sb.append("\n]");
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write(sb.toString());
        }
    }

    /**
     * Loads cities from a JSON file. The format matches {@link #saveCities}.
     *
     * @param path the file path to read from
     * @return a list of cities, never null (empty on error/missing file)
     * @throws IOException on read error
     */
    public static List<City> loadCities(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }
        try (Reader r = Files.newBufferedReader(path)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            Object root = Json.parse(sb.toString());
            if (!(root instanceof List)) {
                return Collections.emptyList();
            }
            List<?> items = (List<?>) root;
            List<City> cities = new ArrayList<>();
            for (Object o : items) {
                Map<?, ?> m = asMap(o);
                if (m == null) {
                    continue;
                }
                String name = string(m, "name");
                String country = string(m, "country");
                double lat = num(m, "lat");
                double lon = num(m, "lon");
                if (name != null && !Double.isNaN(lat) && !Double.isNaN(lon)) {
                    cities.add(new City(name, country, lat, lon));
                }
            }
            return cities;
        }
    }

    // ------------------------------------------------------------------
    // Descriptions + conversion
    // ------------------------------------------------------------------

    /** WMO weather interpretation codes &rarr; short human text. */
    public static String condition(int code) {
        switch (code) {
            case 0: return "Clear sky";
            case 1: return "Mainly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: case 63: case 65: return "Rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: return "Snow";
            case 77: return "Snow grains";
            case 80: case 81: case 82: return "Rain showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, hail";
            default: return "\u2014";
        }
    }

    /** The coarse sky family used to pick a glyph. */
    public enum Sky { SUN, PARTLY, CLOUD, FOG, RAIN, SNOW, THUNDER }

    /** Maps a WMO code to its {@link Sky} family (overcast by default). */
    public static Sky skyFor(int code) {
        switch (code) {
            case 0: return Sky.SUN;
            case 1: case 2: return Sky.PARTLY;
            case 3: return Sky.CLOUD;
            case 45: case 48: return Sky.FOG;
            case 51: case 53: case 55: case 56: case 57:
            case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82: return Sky.RAIN;
            case 71: case 73: case 75: case 77:
            case 85: case 86: return Sky.SNOW;
            case 95: case 96: case 99: return Sky.THUNDER;
            default: return Sky.CLOUD;
        }
    }

    /** Converts a Celsius reading to Fahrenheit (identity when {@code false}). */
    public static double convert(double celsius, boolean fahrenheit) {
        return fahrenheit ? celsius * 9.0 / 5.0 + 32.0 : celsius;
    }

    /** Converts km/h to mph (identity when {@code false}). */
    public static double convertWind(double kmh, boolean fahrenheit) {
        return fahrenheit ? kmh * 0.621371 : kmh;
    }

    // ------------------------------------------------------------------
    // Small JSON accessors
    // ------------------------------------------------------------------

    private static Map<?, ?> asMap(Object o) {
        return (o instanceof Map) ? (Map<?, ?>) o : null;
    }

    private static List<?> asList(Object o) {
        return (o instanceof List) ? (List<?>) o : null;
    }

    private static double num(Map<?, ?> m, String key) {
        if (m == null) {
            return Double.NaN;
        }
        Object v = m.get(key);
        return (v instanceof Number) ? ((Number) v).doubleValue() : Double.NaN;
    }

    private static double numAt(List<?> l, int i) {
        if (l != null && i < l.size() && l.get(i) instanceof Number) {
            return ((Number) l.get(i)).doubleValue();
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------
    // Minimal dependency-free JSON reader (objects, arrays, strings,
    // numbers, true/false/null). Open-Meteo returns a small, well-formed
    // document, so a compact recursive-descent parser is enough and avoids
    // adding a third-party JSON dependency to the desktop.
    // ------------------------------------------------------------------

    static final class Json {
        private final String s;
        private int p;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text);
            j.skipWs();
            Object v = j.readValue();
            j.skipWs();
            return v;
        }

        /** Quotes a string for JSON output (escapes special characters). */
        static String quote(String s) {
            if (s == null) {
                return "null";
            }
            StringBuilder sb = new StringBuilder(s.length() + 2);
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < ' ') {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
            return sb.toString();
        }

        private Object readValue() {
            skipWs();
            if (p >= s.length()) {
                throw new IllegalStateException("Unexpected end of JSON");
            }
            char c = s.charAt(p);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': case 'f': case 'n': return readLiteral();
                default: return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                p++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                map.put(key, readValue());
                skipWs();
                char c = next();
                if (c == '}') {
                    break;
                }
                if (c != ',') {
                    throw new IllegalStateException("Expected , or } at " + p);
                }
            }
            return map;
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                p++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    break;
                }
                if (c != ',') {
                    throw new IllegalStateException("Expected , or ] at " + p);
                }
            }
            return list;
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                            p += 4;
                            break;
                        default: throw new IllegalStateException("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object readLiteral() {
            if (s.startsWith("true", p)) {
                p += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", p)) {
                p += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", p)) {
                p += 4;
                return null;
            }
            throw new IllegalStateException("Bad literal at " + p);
        }

        private Double readNumber() {
            int start = p;
            if (peek() == '-' || peek() == '+') {
                p++;
            }
            while (p < s.length() && "+-.eE0123456789".indexOf(s.charAt(p)) >= 0) {
                p++;
            }
            return Double.parseDouble(s.substring(start, p));
        }

        private void skipWs() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) {
                p++;
            }
        }

        private char peek() {
            if (p >= s.length()) {
                throw new IllegalStateException("Unexpected end of JSON");
            }
            return s.charAt(p);
        }

        private char next() {
            if (p >= s.length()) {
                throw new IllegalStateException("Unexpected end of JSON");
            }
            return s.charAt(p++);
        }

        private void expect(char c) {
            char n = next();
            if (n != c) {
                throw new IllegalStateException(
                        "Expected " + c + " but got " + n + " at " + (p - 1));
            }
        }
    }
}
