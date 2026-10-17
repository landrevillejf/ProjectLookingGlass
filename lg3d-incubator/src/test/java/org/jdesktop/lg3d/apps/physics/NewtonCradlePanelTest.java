package org.jdesktop.lg3d.apps.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.jdesktop.lg3d.apps.physics.model.NewtonCradleModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the 2D/Swing face of the resurrected Physics app. {@link
 * NewtonCradlePanel} builds headlessly on a fresh {@link NewtonCradleModel}, does
 * not start its animation timer until it is actually placed in a displayable peer
 * (so a headless host never spawns one), advances the model by exactly one fixed
 * frame per {@code tick()}, re-pulls the first ball once a release has played out,
 * and paints the cradle - support bar, strings and shaded balls - into its canvas.
 * Every case runs with {@code java.awt.headless=true}: a {@code JPanel}, a
 * {@code javax.swing.Timer} model and a {@code BufferedImage} need no display.
 */
class NewtonCradlePanelTest {

    @Test
    @DisplayName("the panel builds headless, paused, on a fresh cradle")
    void buildsHeadless() {
        NewtonCradlePanel panel = new NewtonCradlePanel();
        assertEquals(NewtonCradlePanel.WIDTH_PX, panel.getPreferredSize().width);
        assertEquals(NewtonCradlePanel.HEIGHT_PX, panel.getPreferredSize().height);
        assertFalse(panel.isRunning(), "no timer runs until the panel is shown");
        assertEquals(0f, panel.model().getTime(), "the cradle starts unreleased");
        assertNotNull(panel.model());
        assertEquals(NewtonCradleModel.BALL_COUNT, panel.model().getBallCount());
    }

    @Test
    @DisplayName("start and pause flip the running flag and the button label")
    void startPauseToggle() {
        NewtonCradlePanel panel = new NewtonCradlePanel();
        panel.start();
        assertTrue(panel.isRunning());
        panel.pause();
        assertFalse(panel.isRunning());
        panel.toggleRun();
        assertTrue(panel.isRunning(), "toggleRun resumes a paused cradle");
        panel.toggleRun();
        assertFalse(panel.isRunning(), "toggleRun freezes a running cradle");
        // Leave it stopped so no timer outlives the test.
        panel.pause();
    }

    @Test
    @DisplayName("one tick advances the model by exactly one fixed frame")
    void tickAdvancesOneFrame() {
        NewtonCradlePanel panel = new NewtonCradlePanel();
        float before = panel.model().getTime();
        panel.tick();
        float after = panel.model().getTime();
        assertEquals(1f / 60f, after - before, 1e-6,
                "a tick is one 60 fps frame of simulated time");
    }

    @Test
    @DisplayName("the toy re-pulls the first ball once a release has played out")
    void restartsOnCadence() {
        NewtonCradlePanel panel = new NewtonCradlePanel();
        // Drive well past the restart cadence; the clock must wrap back to a
        // fresh release rather than running away.
        boolean sawRestart = false;
        float previous = 0f;
        for (int i = 0; i < 60 * 12; i++) {
            panel.tick();
            float now = panel.model().getTime();
            if (now < previous) {
                sawRestart = true;
            }
            previous = now;
        }
        assertTrue(sawRestart, "the cradle must re-pull itself on its cadence");
        assertTrue(panel.model().getTime() < 12f, "the clock wraps on restart");
    }

    @Test
    @DisplayName("reset returns the cradle to its release pose")
    void resetRestoresPose() {
        NewtonCradlePanel panel = new NewtonCradlePanel();
        for (int i = 0; i < 60; i++) {
            panel.tick();
        }
        assertTrue(panel.model().getTime() > 0f);
        panel.reset();
        assertEquals(0f, panel.model().getTime());
        assertTrue(panel.model().getBallX(0) < panel.model().getAnchorX(0),
                "reset re-pulls the first ball");
    }

    @Test
    @DisplayName("the status line names the toy and its run state")
    void statusLine() {
        NewtonCradlePanel panel = new NewtonCradlePanel();
        String paused = panel.statusText();
        assertTrue(paused.contains("Newton's cradle"), paused);
        assertTrue(paused.contains("paused"), paused);
        panel.start();
        assertTrue(panel.statusText().contains("running"), panel.statusText());
        panel.pause();
    }

    @Test
    @DisplayName("the canvas paints the cradle rather than a blank field")
    void paintsTheCradle() {
        NewtonCradlePanel panel = new NewtonCradlePanel();
        // A few frames in, so the balls are mid-swing rather than at the release.
        for (int i = 0; i < 40; i++) {
            panel.tick();
        }
        panel.setSize(NewtonCradlePanel.WIDTH_PX, NewtonCradlePanel.HEIGHT_PX);
        panel.doLayout();
        panel.sizeCanvasForTest(400, 300);

        BufferedImage image = new BufferedImage(NewtonCradlePanel.WIDTH_PX,
                NewtonCradlePanel.HEIGHT_PX, BufferedImage.TYPE_INT_ARGB);
        panel.paint(image.createGraphics());

        // The canvas band must carry more than a flat fill: the slate backdrop,
        // the steel bar, the strings and the shaded (gradient) balls together
        // produce many distinct colours. A blank or unpainted canvas would be ~1.
        java.util.Set<Integer> colours = new java.util.HashSet<>();
        for (int y = 60; y < 400; y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                colours.add(image.getRGB(x, y));
            }
        }
        assertTrue(colours.size() > 8,
                "the cradle must paint (distinct colours=" + colours.size() + ")");
    }
}
