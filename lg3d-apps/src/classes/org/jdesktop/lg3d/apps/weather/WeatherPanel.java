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

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The Weather application's Swing face: a full window showing the current
 * conditions and a six-day forecast for a chosen city, fed by the AWT-free
 * {@link OpenMeteo} backend (the same free Open-Meteo API the desktop Weather
 * widget uses, over the JDK {@code java.net.http} client with a
 * dependency-free JSON reader &mdash; no third-party library).
 *
 * <p>The panel is plain Swing and touches no Java&nbsp;3D, so the one class
 * serves both desktops: in 3D the {@link Weather} wrapper hosts it on a
 * {@code SwingNode} inside a {@code Frame3D} via {@code TitledSwingWindow}; in
 * the 2D/Swing desktop {@code Desktop2DAppRegistry.PANEL_APPS} opens the very
 * same panel as an MDI internal frame. It uses only layout managers and a
 * hand-painted sky glyph (no Synth combo boxes), so it also paints correctly
 * into the SwingNode offscreen buffer.</p>
 *
 * <p>Fetching runs on a background daemon scheduler (never the EDT); results
 * are handed back to the EDT to update the labels and repaint. Selecting a city
 * fetches immediately, the &deg;C/&deg;F button converts in place without
 * re-fetching, and the selection re-polls every {@value #REFRESH_MINUTES}
 * minutes.</p>
 */
public class WeatherPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 680;
    public static final int HEIGHT_PX = 460;

    /** How often the selected city is re-polled, in minutes. */
    private static final int REFRESH_MINUTES = 15;

    /** Path to the user's custom cities file (in home directory). */
    private static final java.nio.file.Path CITIES_FILE =
            Paths.get(System.getProperty("user.home"), ".lg3d-weather-cities.json");

    private static final Color BACKDROP = new Color(0x2B, 0x33, 0x3D);
    private static final Color CARD = new Color(0x36, 0x40, 0x4C);
    private static final Color TEXT = new Color(0xEC, 0xF1, 0xF7);
    private static final Color TEXT_DIM = new Color(0xA8, 0xB4, 0xC2);
    private static final Color ACCENT = new Color(0x6F, 0xB5, 0xE8);

    private final JList<OpenMeteo.City> cityList;
    private final DefaultListModel<OpenMeteo.City> cityModel = new DefaultListModel<>();
    private final JButton unitButton = new JButton();
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton addButton = new JButton("+");
    private final JButton removeButton = new JButton("-");

    private final Glyph currentGlyph = new Glyph(96);
    private final JLabel placeLabel = new JLabel(" ");
    private final JLabel tempLabel = new JLabel(" ");
    private final JLabel conditionLabel = new JLabel(" ");
    private final JLabel detailLabel = new JLabel(" ");
    private final JLabel highLowLabel = new JLabel(" ");
    private final JPanel forecastStrip = new JPanel(new GridLayout(1, 0, 8, 0));

    private final JLabel statusLabel = new JLabel(" ");

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "WeatherApp-fetch");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean fahrenheit;
    private volatile OpenMeteo.Report report;
    private Runnable onClose;

    public WeatherPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBackground(BACKDROP);
        setOpaque(true);

        fahrenheit = OpenMeteo.defaultFahrenheit();

        loadCities();

        cityList = new JList<>(cityModel);
        cityList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cityList.setSelectedIndex(0);
        cityList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refresh();
            }
        });
        JScrollPane cityScroll = new JScrollPane(cityList);
        cityScroll.setPreferredSize(new Dimension(150, 0));
        cityScroll.setBorder(BorderFactory.createEmptyBorder());

        unitButton.addActionListener(e -> toggleUnit());
        refreshButton.addActionListener(e -> refresh());
        addButton.addActionListener(e -> showAddCityDialog());
        removeButton.addActionListener(e -> removeSelectedCity());
        updateUnitButton();

        JPanel controls = new JPanel(new GridLayout(4, 1, 0, 6));
        controls.setOpaque(false);
        controls.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 0));
        controls.add(unitButton);
        controls.add(refreshButton);
        controls.add(addButton);
        controls.add(removeButton);

        JPanel west = new JPanel(new BorderLayout());
        west.setOpaque(false);
        west.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 4));
        west.add(cityScroll, BorderLayout.CENTER);
        west.add(controls, BorderLayout.SOUTH);
        add(west, BorderLayout.WEST);

        add(buildCenter(), BorderLayout.CENTER);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
        statusLabel.setForeground(TEXT_DIM);
        add(statusLabel, BorderLayout.SOUTH);

        showLoading();
        refresh();
        scheduler.scheduleWithFixedDelay(this::refresh,
                REFRESH_MINUTES, REFRESH_MINUTES, TimeUnit.MINUTES);
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private JComponent buildCenter() {
        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.setBorder(BorderFactory.createEmptyBorder(12, 8, 8, 12));

        // Current conditions card.
        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(CARD);
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 2, 0);

        c.gridx = 0;
        c.gridy = 0;
        c.gridheight = 4;
        c.anchor = GridBagConstraints.NORTH;
        card.add(currentGlyph, c);

        c.gridheight = 1;
        c.gridx = 1;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        placeLabel.setFont(placeLabel.getFont().deriveFont(Font.BOLD, 20f));
        placeLabel.setForeground(TEXT);
        card.add(placeLabel, c);

        c.gridy = 1;
        tempLabel.setFont(tempLabel.getFont().deriveFont(Font.BOLD, 44f));
        tempLabel.setForeground(TEXT);
        card.add(tempLabel, c);

        c.gridy = 2;
        conditionLabel.setFont(conditionLabel.getFont().deriveFont(Font.PLAIN, 15f));
        conditionLabel.setForeground(ACCENT);
        card.add(conditionLabel, c);

        c.gridy = 3;
        highLowLabel.setFont(highLowLabel.getFont().deriveFont(Font.PLAIN, 13f));
        highLowLabel.setForeground(TEXT_DIM);
        card.add(highLowLabel, c);

        c.gridy = 4;
        c.gridx = 0;
        c.gridwidth = 2;
        c.insets = new Insets(8, 0, 0, 0);
        detailLabel.setFont(detailLabel.getFont().deriveFont(Font.PLAIN, 12f));
        detailLabel.setForeground(TEXT_DIM);
        card.add(detailLabel, c);

        center.add(card, BorderLayout.NORTH);

        // Forecast strip.
        JPanel forecastWrap = new JPanel(new BorderLayout());
        forecastWrap.setOpaque(false);
        JLabel title = new JLabel("  Forecast");
        title.setForeground(TEXT_DIM);
        forecastWrap.add(title, BorderLayout.NORTH);
        forecastStrip.setOpaque(false);
        forecastStrip.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        forecastWrap.add(forecastStrip, BorderLayout.CENTER);
        center.add(forecastWrap, BorderLayout.CENTER);

        return center;
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    /** Optional close hook the 2D MDI host wires its window close button to. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** Runs the registered close hook and stops the background scheduler. */
    public void close() {
        scheduler.shutdownNow();
        saveCities();
        if (onClose != null) {
            onClose.run();
        }
    }

    /** Loads cities from the persistence file, falling back to presets. */
    private void loadCities() {
        try {
            List<OpenMeteo.City> loaded = OpenMeteo.loadCities(CITIES_FILE);
            if (!loaded.isEmpty()) {
                for (OpenMeteo.City c : loaded) {
                    cityModel.addElement(c);
                }
                return;
            }
        } catch (Exception e) {
            // Fall through to presets
        }
        for (OpenMeteo.City c : OpenMeteo.CITIES) {
            cityModel.addElement(c);
        }
    }

    /** Saves the current city list to the persistence file. */
    private void saveCities() {
        try {
            List<OpenMeteo.City> cities = new ArrayList<>();
            for (int i = 0; i < cityModel.getSize(); i++) {
                cities.add(cityModel.getElementAt(i));
            }
            OpenMeteo.saveCities(cities, CITIES_FILE);
        } catch (Exception e) {
            // Silent failure - not critical
        }
    }

    /** Shows a dialog to search and add a new city. */
    private void showAddCityDialog() {
        JDialog dialog = new JDialog();
        dialog.setTitle("Add City");
        dialog.setModal(true);
        dialog.setLayout(new BorderLayout(0, 8));
        dialog.setSize(400, 300);
        dialog.setLocationRelativeTo(this);

        JTextField searchField = new JTextField();
        searchField.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        dialog.add(searchField, BorderLayout.NORTH);

        DefaultListModel<OpenMeteo.City> resultsModel = new DefaultListModel<>();
        JList<OpenMeteo.City> resultsList = new JList<>(resultsModel);
        resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane resultsScroll = new JScrollPane(resultsList);
        dialog.add(resultsScroll, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JButton addButton = new JButton("Add");
        JButton cancelButton = new JButton("Cancel");
        buttonPanel.add(addButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { search(); }
            @Override
            public void removeUpdate(DocumentEvent e) { search(); }
            @Override
            public void changedUpdate(DocumentEvent e) { search(); }

            private void search() {
                String query = searchField.getText().trim();
                if (query.length() < 2) {
                    resultsModel.clear();
                    return;
                }
                scheduler.execute(() -> {
                    try {
                        List<OpenMeteo.City> results = OpenMeteo.search(query);
                        SwingUtilities.invokeLater(() -> {
                            resultsModel.clear();
                            for (OpenMeteo.City c : results) {
                                resultsModel.addElement(c);
                            }
                        });
                    } catch (Exception ex) {
                        // Ignore errors
                    }
                });
            }
        });

        addButton.addActionListener(e -> {
            OpenMeteo.City selected = resultsList.getSelectedValue();
            if (selected != null) {
                cityModel.addElement(selected);
                cityList.setSelectedValue(selected, true);
                saveCities();
                dialog.dispose();
            }
        });

        cancelButton.addActionListener(e -> dialog.dispose());
        dialog.setVisible(true);
    }

    /** Removes the currently selected city from the list. */
    private void removeSelectedCity() {
        OpenMeteo.City selected = cityList.getSelectedValue();
        if (selected == null) {
            return;
        }
        if (cityModel.getSize() <= 1) {
            JOptionPane.showMessageDialog(this,
                    "Cannot remove the last city.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Remove " + selected + " from the list?",
                "Remove City",
                JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            int index = cityList.getSelectedIndex();
            cityModel.removeElement(selected);
            if (index >= cityModel.getSize()) {
                index = cityModel.getSize() - 1;
            }
            cityList.setSelectedIndex(index);
            saveCities();
        }
    }

    private void toggleUnit() {
        fahrenheit = !fahrenheit;
        updateUnitButton();
        render(report);
    }

    private void updateUnitButton() {
        unitButton.setText(fahrenheit ? "Switch to \u00B0C" : "Switch to \u00B0F");
    }

    /** Re-fetches the selected city on the background scheduler. */
    public void refresh() {
        OpenMeteo.City city = cityList.getSelectedValue();
        if (city == null) {
            return;
        }
        showLoading();
        scheduler.execute(() -> {
            try {
                OpenMeteo.Report r = OpenMeteo.fetch(city);
                SwingUtilities.invokeLater(() -> {
                    report = r;
                    statusLabel.setText("Updated " + city.name + " \u00B7 "
                            + java.time.LocalTime.now().withNano(0));
                    render(r);
                });
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError(city.name, ex));
            }
        });
    }

    // ------------------------------------------------------------------
    // Rendering (EDT)
    // ------------------------------------------------------------------

    private void showLoading() {
        placeLabel.setText(cityName());
        tempLabel.setText("\u2026");
        conditionLabel.setText("Loading");
        highLowLabel.setText(" ");
        detailLabel.setText(" ");
        currentGlyph.setCode(-1);
        forecastStrip.removeAll();
        forecastStrip.revalidate();
        forecastStrip.repaint();
    }

    private void showError(String place, Exception ex) {
        placeLabel.setText(place);
        tempLabel.setText("\u2014");
        conditionLabel.setText("Unavailable");
        highLowLabel.setText(" ");
        detailLabel.setText(" ");
        currentGlyph.setCode(-1);
        String msg = ex.getMessage();
        statusLabel.setText("Could not load weather for " + place
                + (msg == null ? "" : " (" + msg + ")"));
    }

    private String cityName() {
        OpenMeteo.City c = cityList.getSelectedValue();
        return (c == null) ? "" : c.name;
    }

    private void render(OpenMeteo.Report r) {
        if (r == null) {
            return;
        }
        OpenMeteo.Current cur = r.current;
        placeLabel.setText(r.place);
        currentGlyph.setCode(cur.weatherCode);

        if (Double.isNaN(cur.tempC)) {
            tempLabel.setText("\u2014");
            conditionLabel.setText("No data");
        } else {
            tempLabel.setText(String.format(Locale.ROOT, "%.0f\u00B0%s",
                    OpenMeteo.convert(cur.tempC, fahrenheit), fahrenheit ? "F" : "C"));
            conditionLabel.setText(OpenMeteo.condition(cur.weatherCode));
        }

        StringBuilder hl = new StringBuilder();
        OpenMeteo.Day today = r.days.isEmpty() ? null : r.days.get(0);
        if (today != null) {
            if (!Double.isNaN(today.maxC)) {
                hl.append("H ").append(String.format(Locale.ROOT, "%.0f\u00B0",
                        OpenMeteo.convert(today.maxC, fahrenheit)));
            }
            if (!Double.isNaN(today.minC)) {
                if (hl.length() > 0) {
                    hl.append("   ");
                }
                hl.append("L ").append(String.format(Locale.ROOT, "%.0f\u00B0",
                        OpenMeteo.convert(today.minC, fahrenheit)));
            }
        }
        highLowLabel.setText(hl.toString());

        StringBuilder d = new StringBuilder();
        if (!Double.isNaN(cur.feelsC)) {
            d.append("Feels ").append(String.format(Locale.ROOT, "%.0f\u00B0",
                    OpenMeteo.convert(cur.feelsC, fahrenheit)));
        }
        if (cur.humidity >= 0) {
            if (d.length() > 0) {
                d.append("   \u00B7   ");
            }
            d.append("Humidity ").append(cur.humidity).append('%');
        }
        if (!Double.isNaN(cur.windKmh)) {
            if (d.length() > 0) {
                d.append("   \u00B7   ");
            }
            d.append(String.format(Locale.ROOT, "Wind %.0f %s",
                    OpenMeteo.convertWind(cur.windKmh, fahrenheit),
                    fahrenheit ? "mph" : "km/h"));
        }
        detailLabel.setText(d.toString());

        renderForecast(r.days);
    }

    private void renderForecast(List<OpenMeteo.Day> days) {
        forecastStrip.removeAll();
        // Skip today (shown above); present the following days.
        int shown = 0;
        for (int i = 1; i < days.size() && shown < 5; i++, shown++) {
            forecastStrip.add(new ForecastCell(days.get(i)));
        }
        forecastStrip.revalidate();
        forecastStrip.repaint();
    }

    /** One day column in the forecast strip. */
    private final class ForecastCell extends JPanel {
        ForecastCell(OpenMeteo.Day day) {
            super(new GridBagLayout());
            setBackground(CARD);
            setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.anchor = GridBagConstraints.CENTER;
            c.insets = new Insets(1, 0, 1, 0);

            String label = dayName(day.date);
            JLabel name = new JLabel(label, SwingConstants.CENTER);
            name.setForeground(TEXT);
            name.setFont(name.getFont().deriveFont(Font.BOLD, 12f));
            c.gridy = 0;
            add(name, c);

            Glyph g = new Glyph(40);
            g.setCode(day.weatherCode);
            c.gridy = 1;
            add(g, c);

            JLabel hi = new JLabel(fmt(day.maxC), SwingConstants.CENTER);
            hi.setForeground(TEXT);
            c.gridy = 2;
            add(hi, c);

            JLabel lo = new JLabel(fmt(day.minC), SwingConstants.CENTER);
            lo.setForeground(TEXT_DIM);
            c.gridy = 3;
            add(lo, c);
        }

        private String fmt(double celsius) {
            if (Double.isNaN(celsius)) {
                return "\u2014";
            }
            return String.format(Locale.ROOT, "%.0f\u00B0",
                    OpenMeteo.convert(celsius, fahrenheit));
        }

        private String dayName(String isoDate) {
            try {
                return LocalDate.parse(isoDate)
                        .getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
            } catch (RuntimeException e) {
                return isoDate;
            }
        }
    }

    // ------------------------------------------------------------------
    // Sky glyph (hand-painted; no image assets)
    // ------------------------------------------------------------------

    /** Paints a WMO-code sky glyph (sun / cloud / rain / snow / thunder / fog). */
    private static final class Glyph extends JComponent {
        private final int size;
        private int code = -1;

        Glyph(int size) {
            this.size = size;
            setPreferredSize(new Dimension(size, size));
            setOpaque(false);
        }

        void setCode(int code) {
            if (this.code != code) {
                this.code = code;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (code < 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            draw(g2, 0, 0, size, code);
            g2.dispose();
        }
    }

    private static void draw(Graphics2D g2, int x, int y, int size, int code) {
        double s = size;
        switch (OpenMeteo.skyFor(code)) {
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
}
