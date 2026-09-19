/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.widgets.builtin;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.utils.action.ActionInt;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MouseWheelEventAdapter;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.widgets.api.AbstractWidget;
import org.jdesktop.lg3d.widgets.api.WidgetContext;

/**
 * Current weather for a city, fetched from the free
 * <a href="https://open-meteo.com/">Open-Meteo</a> forecast API (no API key).
 *
 * <p>The widget ships a preset list of major world cities; the
 * <strong>mouse wheel</strong> cycles through them and the selection is
 * persisted, while a <strong>click</strong> toggles between &deg;C and &deg;F
 * (also persisted, defaulting from the system locale). A location not in the
 * preset list can be pinned by writing {@code lat}, {@code lon} and
 * {@code label} options for this instance into
 * {@code ~/.config/lg3d/widgets.properties}; using the wheel switches back to
 * the presets and clears that override.</p>
 *
 * <p>Fetches run on the shared widget scheduler thread (never the EDT or the
 * 3D event processor), so the blocking HTTP call is safe; results are published
 * to {@code volatile} fields and painted by the Swing panel, exactly like the
 * clock/temperature widgets. Weather is refreshed every {@value #REFRESH_MINUTES}
 * minutes and immediately after a city change. Data is always requested in
 * Celsius/km-h and converted for display, so toggling units never re-fetches.
 * If a refresh fails the last good reading is kept and flagged "stale".</p>
 */
public class WeatherWidget extends AbstractWidget {
    public static final String ID = "weather";

    private static final Logger logger = Logger.getLogger("lg.widgets");

    /** How often to re-fetch, in minutes. */
    private static final int REFRESH_MINUTES = 15;
    private static final long REFRESH_MILLIS = REFRESH_MINUTES * 60L * 1000L;

    private static final String API = "https://api.open-meteo.com/v1/forecast";

    /** Shared client; created lazily-free (no connection at construction). */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Preset cities as {label, latitude, longitude}. Wheel cycles this list. */
    private static final String[][] PRESETS = {
        {"London", "51.5072", "-0.1276"},
        {"Paris", "48.8566", "2.3522"},
        {"New York", "40.7128", "-74.0060"},
        {"San Francisco", "37.7749", "-122.4194"},
        {"Toronto", "43.6532", "-79.3832"},
        {"Berlin", "52.5200", "13.4050"},
        {"Madrid", "40.4168", "-3.7038"},
        {"Rome", "41.9028", "12.4964"},
        {"Tokyo", "35.6762", "139.6503"},
        {"Sydney", "-33.8688", "151.2093"},
    };

    // -- persisted / interaction state ---------------------------------
    private volatile int cityIndex = 0;
    private volatile boolean fahrenheit = false;
    private volatile Double customLat = null;
    private volatile Double customLon = null;
    private volatile String customLabel = null;

    // -- published model (read by the panel on the EDT) ------------------
    private volatile boolean loading = true;
    private volatile String error = null;
    private volatile String place = "";
    private volatile double tempC = Double.NaN;
    private volatile double feelsC = Double.NaN;
    private volatile double minC = Double.NaN;
    private volatile double maxC = Double.NaN;
    private volatile double windKmh = Double.NaN;
    private volatile int humidity = -1;
    private volatile int weatherCode = -1;

    public WeatherWidget() {
        super(ID, "Weather");
    }

    @Override
    public void init(WidgetContext context) {
        super.init(context);
        fahrenheit = "F".equalsIgnoreCase(getOption("unit", defaultUnit()));
        cityIndex = clampIndex(intOr(getOption("cityIndex", "0"), 0));

        String lat = getOption("lat", null);
        String lon = getOption("lon", null);
        if (lat != null && lon != null) {
            double la = dblOr(lat, Double.NaN);
            double lo = dblOr(lon, Double.NaN);
            if (!Double.isNaN(la) && !Double.isNaN(lo)) {
                customLat = la;
                customLon = lo;
                customLabel = getOption("label", "Custom");
            }
        }
        place = currentLabel();

        setSwingPanel(new WeatherPanel());

        // Click toggles the temperature unit (instant; no re-fetch needed).
        addListener(new MouseClickedEventAdapter(new ActionNoArg() {
            @Override
            public void performAction(LgEventSource source) {
                fahrenheit = !fahrenheit;
                setOption("unit", fahrenheit ? "F" : "C");
                setDirty();
            }
        }));

        // Wheel cycles the preset city and fetches it right away.
        addListener(new MouseWheelEventAdapter(new ActionInt() {
            @Override
            public void performAction(LgEventSource source, int value) {
                cycleCity(value);
            }
        }));
    }

    @Override
    public void start() {
        // scheduleTick runs the task immediately, then every REFRESH_MILLIS.
        scheduleTick(REFRESH_MILLIS, this::tick);
    }

    // ------------------------------------------------------------------
    // Location handling
    // ------------------------------------------------------------------

    private void cycleCity(int rotation) {
        // Switching to a preset clears any file-configured custom location.
        if (customLat != null || customLon != null) {
            customLat = null;
            customLon = null;
            customLabel = null;
            setOption("lat", null);
            setOption("lon", null);
            setOption("label", null);
        }
        cityIndex = clampIndex(cityIndex + ((rotation >= 0) ? 1 : -1));
        setOption("cityIndex", Integer.toString(cityIndex));

        // Drop the previous city's readings so a failed fetch cannot show them
        // against the new label.
        place = currentLabel();
        tempC = Double.NaN;
        feelsC = Double.NaN;
        minC = Double.NaN;
        maxC = Double.NaN;
        windKmh = Double.NaN;
        humidity = -1;
        weatherCode = -1;
        error = null;
        loading = true;
        setDirty();
        refreshAsync();
    }

    private String currentLabel() {
        if (customLat != null && customLabel != null) {
            return customLabel;
        }
        return PRESETS[clampIndex(cityIndex)][0];
    }

    private int clampIndex(int i) {
        int n = PRESETS.length;
        return ((i % n) + n) % n;
    }

    private void refreshAsync() {
        WidgetContext c = context();
        if (c != null && c.scheduler() != null) {
            c.scheduler().execute(this::tick);
        }
    }

    // ------------------------------------------------------------------
    // Fetch + parse (runs on the scheduler thread)
    // ------------------------------------------------------------------

    private void tick() {
        double lat;
        double lon;
        String label;
        if (customLat != null && customLon != null) {
            lat = customLat;
            lon = customLon;
            label = (customLabel != null) ? customLabel : "Custom";
        } else {
            String[] p = PRESETS[clampIndex(cityIndex)];
            label = p[0];
            lat = dblOr(p[1], 0.0);
            lon = dblOr(p[2], 0.0);
        }

        try {
            String url = API
                    + "?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,apparent_temperature,"
                    + "relative_humidity_2m,weather_code,wind_speed_10m"
                    + "&daily=temperature_2m_max,temperature_2m_min"
                    + "&forecast_days=1&timezone=auto"
                    + "&temperature_unit=celsius&wind_speed_unit=kmh";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "lg3d-weather-widget/1.0")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                place = label;
                error = "HTTP " + resp.statusCode();
                return;
            }
            Object root = Json.parse(resp.body());
            if (!(root instanceof Map)) {
                place = label;
                error = "Bad response";
                return;
            }
            Map<?, ?> m = (Map<?, ?>) root;
            Map<?, ?> cur = asMap(m.get("current"));
            Map<?, ?> daily = asMap(m.get("daily"));

            double t = num(cur, "temperature_2m");
            double feels = num(cur, "apparent_temperature");
            double hum = num(cur, "relative_humidity_2m");
            double code = num(cur, "weather_code");
            double wind = num(cur, "wind_speed_10m");

            place = label;
            tempC = t;
            feelsC = feels;
            windKmh = wind;
            maxC = firstNum(daily == null ? null : daily.get("temperature_2m_max"));
            minC = firstNum(daily == null ? null : daily.get("temperature_2m_min"));
            humidity = Double.isNaN(hum) ? -1 : (int) Math.round(hum);
            weatherCode = Double.isNaN(code) ? -1 : (int) Math.round(code);
            error = null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Weather fetch failed for " + label, e);
            if (error == null) {
                error = "Unavailable";
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Weather fetch failed for " + label, e);
            error = "Unavailable";
        } finally {
            loading = false;
            setDirty();
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static String defaultUnit() {
        String c = Locale.getDefault().getCountry();
        // A handful of countries still use Fahrenheit day-to-day.
        if ("US".equals(c) || "LR".equals(c) || "BS".equals(c)
                || "BZ".equals(c) || "KY".equals(c) || "PW".equals(c)) {
            return "F";
        }
        return "C";
    }

    private static int intOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static double dblOr(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static Map<?, ?> asMap(Object o) {
        return (o instanceof Map) ? (Map<?, ?>) o : null;
    }

    private static double num(Map<?, ?> m, String key) {
        if (m == null) {
            return Double.NaN;
        }
        Object v = m.get(key);
        return (v instanceof Number) ? ((Number) v).doubleValue() : Double.NaN;
    }

    private static double firstNum(Object arr) {
        if (arr instanceof List) {
            List<?> l = (List<?>) arr;
            if (!l.isEmpty() && l.get(0) instanceof Number) {
                return ((Number) l.get(0)).doubleValue();
            }
        }
        return Double.NaN;
    }

    private double conv(double celsius) {
        return fahrenheit ? celsius * 9.0 / 5.0 + 32.0 : celsius;
    }

    // ------------------------------------------------------------------
    // Weather-code description + glyph
    // ------------------------------------------------------------------

    /** WMO weather interpretation codes -> short human text. */
    static String condition(int code) {
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

    private enum Sky { SUN, PARTLY, CLOUD, FOG, RAIN, SNOW, THUNDER }

    private static Sky skyFor(int code) {
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

    // ------------------------------------------------------------------
    // The Swing panel
    // ------------------------------------------------------------------

    private final class WeatherPanel extends WidgetPanel {
        WeatherPanel() {
            super("Weather", 200, 160);
        }

        @Override
        protected void paintContent(Graphics2D g2, int w, int h, int top) {
            int pad = 12;
            if (loading) {
                center(g2, "Loading\u2026", w, top, h, TEXT_DIM, 13f);
                return;
            }
            if (Double.isNaN(tempC)) {
                center(g2, (error != null) ? "Unavailable" : "No data", w, top, h, TEXT_DIM, 13f);
                return;
            }

            double t = conv(tempC);
            int gs = 46;

            // Glyph (top-right).
            drawGlyph(g2, w - pad - gs, top + 2, gs, weatherCode);

            // Big temperature (top-left).
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 30f));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(TEXT);
            g2.drawString(String.format("%.0f\u00B0%s", t, fahrenheit ? "F" : "C"),
                    pad, top + fm.getAscent());
            int y = top + fm.getAscent() + 2;

            // Condition.
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
            fm = g2.getFontMetrics();
            g2.setColor(TITLE_COLOR);
            drawClipped(g2, condition(weatherCode), pad, y + fm.getAscent(), w - pad - gs - 6);
            y += fm.getHeight() + 1;

            // Location.
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
            fm = g2.getFontMetrics();
            g2.setColor(TEXT_DIM);
            drawClipped(g2, place, pad, y + fm.getAscent(), w - 2 * pad);
            y += fm.getHeight() + 5;

            // Divider.
            g2.setColor(new Color(255, 255, 255, 26));
            g2.drawLine(pad, y, w - pad, y);
            y += 3;

            // High / low.
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
            fm = g2.getFontMetrics();
            StringBuilder hl = new StringBuilder();
            if (!Double.isNaN(maxC)) {
                hl.append("H ").append(String.format("%.0f\u00B0", conv(maxC)));
            }
            if (!Double.isNaN(minC)) {
                if (hl.length() > 0) {
                    hl.append("   ");
                }
                hl.append("L ").append(String.format("%.0f\u00B0", conv(minC)));
            }
            g2.setColor(TEXT);
            y += fm.getAscent();
            g2.drawString(hl.toString(), pad, y);
            y += fm.getHeight() + 1;

            // Feels-like / humidity / wind.
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
            fm = g2.getFontMetrics();
            g2.setColor(TEXT_DIM);
            StringBuilder d = new StringBuilder();
            if (!Double.isNaN(feelsC)) {
                d.append("Feels ").append(String.format("%.0f\u00B0", conv(feelsC)));
            }
            if (humidity >= 0) {
                if (d.length() > 0) {
                    d.append("  \u00B7  ");
                }
                d.append(humidity).append("%");
            }
            if (!Double.isNaN(windKmh)) {
                if (d.length() > 0) {
                    d.append("  \u00B7  ");
                }
                double wv = fahrenheit ? windKmh * 0.621371 : windKmh;
                d.append(String.format("%.0f %s", wv, fahrenheit ? "mph" : "km/h"));
            }
            drawClipped(g2, d.toString(), pad, y + fm.getAscent(), w - 2 * pad);

            // Stale marker when the last refresh failed but old data is shown.
            if (error != null) {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 9f));
                g2.setColor(new Color(240, 130, 110, 210));
                g2.drawString("stale", w - pad - 20, h - 7);
            }
        }
    }

    // ------------------------------------------------------------------
    // Painting helpers
    // ------------------------------------------------------------------

    private static void center(Graphics2D g2, String text, int w, int top, int h,
            Color color, float size) {
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, size));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(color);
        int tx = (w - fm.stringWidth(text)) / 2;
        int ty = top + ((h - top) - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(text, tx, ty);
    }

    private static void drawClipped(Graphics2D g2, String text, int x, int baselineY, int maxW) {
        if (text == null || text.isEmpty()) {
            return;
        }
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(text) <= maxW) {
            g2.drawString(text, x, baselineY);
            return;
        }
        String ell = "\u2026";
        int ellW = fm.stringWidth(ell);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            sb.append(text.charAt(i));
            if (fm.stringWidth(sb.toString()) + ellW > maxW) {
                sb.setLength(Math.max(0, sb.length() - 1));
                break;
            }
        }
        g2.drawString(sb + ell, x, baselineY);
    }

    private static void drawGlyph(Graphics2D g2, int x, int y, int size, int code) {
        Sky sky = skyFor(code);
        double s = size;
        switch (sky) {
            case SUN:
                sun(g2, x + s * 0.5, y + s * 0.5, s * 0.28);
                break;
            case PARTLY:
                sun(g2, x + s * 0.34, y + s * 0.32, s * 0.20);
                cloud(g2, x + s * 0.20, y + s * 0.40, s * 0.74, new Color(212, 222, 236));
                break;
            case FOG:
                cloud(g2, x + s * 0.10, y + s * 0.12, s * 0.80, new Color(190, 200, 216));
                g2.setColor(new Color(160, 175, 196));
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 3; i++) {
                    int ly = (int) (y + s * 0.72) + i * 5;
                    g2.drawLine((int) (x + s * 0.20), ly, (int) (x + s * 0.80), ly);
                }
                break;
            case RAIN:
                cloud(g2, x + s * 0.10, y + s * 0.08, s * 0.80, new Color(180, 195, 216));
                g2.setColor(new Color(110, 170, 240));
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 3; i++) {
                    int dx = (int) (x + s * (0.30 + i * 0.20));
                    g2.drawLine(dx, (int) (y + s * 0.66), dx - 3, (int) (y + s * 0.88));
                }
                break;
            case SNOW:
                cloud(g2, x + s * 0.10, y + s * 0.08, s * 0.80, new Color(190, 200, 216));
                g2.setColor(new Color(226, 236, 250));
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 3; i++) {
                    int dx = (int) (x + s * (0.30 + i * 0.20));
                    int dy = (int) (y + s * 0.82);
                    g2.drawLine(dx - 3, dy, dx + 3, dy);
                    g2.drawLine(dx, dy - 3, dx, dy + 3);
                }
                break;
            case THUNDER:
                cloud(g2, x + s * 0.10, y + s * 0.06, s * 0.80, new Color(158, 170, 194));
                g2.setColor(new Color(250, 205, 90));
                double bx = x + s * 0.54;
                double by = y + s * 0.56;
                Path2D bolt = new Path2D.Double();
                bolt.moveTo(bx, by);
                bolt.lineTo(bx - s * 0.16, by + s * 0.22);
                bolt.lineTo(bx - s * 0.02, by + s * 0.22);
                bolt.lineTo(bx - s * 0.10, by + s * 0.44);
                bolt.lineTo(bx + s * 0.16, by + s * 0.16);
                bolt.lineTo(bx + s * 0.02, by + s * 0.16);
                bolt.closePath();
                g2.fill(bolt);
                break;
            case CLOUD:
            default:
                cloud(g2, x + s * 0.10, y + s * 0.18, s * 0.80, new Color(200, 210, 226));
                break;
        }
    }

    private static void sun(Graphics2D g2, double cx, double cy, double r) {
        g2.setColor(new Color(250, 200, 80));
        g2.setStroke(new BasicStroke((float) Math.max(1.4, r * 0.20),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            g2.drawLine((int) (cx + Math.cos(a) * r * 1.25), (int) (cy + Math.sin(a) * r * 1.25),
                    (int) (cx + Math.cos(a) * r * 1.75), (int) (cy + Math.sin(a) * r * 1.75));
        }
        g2.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }

    private static void cloud(Graphics2D g2, double x, double y, double w, Color c) {
        g2.setColor(c);
        double hh = w * 0.62;
        double r = hh * 0.5;
        g2.fill(new Ellipse2D.Double(x + w * 0.08, y + hh * 0.16, r * 1.3, r * 1.3));
        g2.fill(new Ellipse2D.Double(x + w * 0.36, y, r * 1.7, r * 1.7));
        g2.fill(new Ellipse2D.Double(x + w * 0.62, y + hh * 0.20, r * 1.25, r * 1.25));
        g2.fill(new RoundRectangle2D.Double(x + w * 0.08, y + hh * 0.46,
                w * 0.84, hh * 0.52, hh * 0.5, hh * 0.5));
    }

    // ------------------------------------------------------------------
    // Minimal dependency-free JSON reader (objects, arrays, strings,
    // numbers, true/false/null). Open-Meteo returns a small, well-formed
    // document, so a compact recursive-descent parser is enough and avoids
    // adding a third-party JSON dependency to the desktop.
    // ------------------------------------------------------------------

    private static final class Json {
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
                throw new IllegalStateException("Expected " + c + " but got " + n + " at " + (p - 1));
            }
        }
    }
}
