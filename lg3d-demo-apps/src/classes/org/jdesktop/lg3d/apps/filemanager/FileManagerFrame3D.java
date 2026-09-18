/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may except in compliance with
 * the License. A copy of the License is available at
 * http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.filemanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.Timer;
import org.jdesktop.lg3d.apps.uikit.Button3D;
import org.jdesktop.lg3d.apps.uikit.ScrollList3D;
import org.jdesktop.lg3d.apps.uikit.Ui3D;
import org.jdesktop.lg3d.scenemanager.utils.taskbar.Taskbar;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.system.Opener;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.Component3D;
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Vector3f;

/**
 * The File Manager application window: a {@link Frame3D} with a 100%
 * lg3d-native 3D file browser (no SwingNode).
 *
 * <pre>
 *   +-------------------------------------------------------------+
 *   | File Manager                              [ _ ][ X ] (deco) |
 *   | [Back][Fwd][Up][Home][Refresh][Open][Delete]                |
 *   | /home/user/Documents                                        |
 *   +-------------------------------------------------------------+
 *   | [##] reports             folder                             |
 *   | [##] photos              folder                             |
 *   | [==] notes.txt           12.3 KB                            |
 *   |                   (wheel scrolls)                           |
 *   +-------------------------------------------------------------+
 *   | 42 items (6 folders)                                        |
 *   +-------------------------------------------------------------+
 * </pre>
 *
 * <p>Navigation: click a folder row to enter it, Back/Forward history, Up to
 * the parent, Home to {@code user.home}, Refresh to re-read. Click a file row
 * to select it; Open hands it to the desktop ({@link Opener}, xdg-open);
 * Delete moves it to the trash after a two-click confirm. The wheel scrolls
 * the listing.</p>
 *
 * <p>As a {@code Frame3D} without
 * {@code Frame3DWindowDecoration.OPT_OUT_PROPERTY}, the standard decoration
 * (minimize/maximize/close, flip, spin) is attached automatically, so the
 * top-right corner is left clear of controls.</p>
 */
public class FileManagerFrame3D extends Frame3D {

    private static final Color4f WINDOW_BG = new Color4f(0.05f, 0.07f, 0.11f, 0.55f);
    private static final int ROW_CAP = 400;
    private static final int DELETE_ARM_MS = 4000;

    private final Path home = java.nio.file.Paths.get(System.getProperty("user.home"));
    private final Deque<Path> back = new ArrayDeque<>();
    private final Deque<Path> forward = new ArrayDeque<>();

    private final GlassyText2D pathText;
    private final GlassyText2D statusText;
    private final ScrollList3D list;
    private final Button3D backBtn;
    private final Button3D fwdBtn;
    private final Button3D deleteBtn;
    private final Timer deleteArmTimer;

    private final float rowW;
    private final float rowH;
    private final float textH;

    private Path currentDir;
    private Path selected;
    private GlassyPanel selectedBg;
    private boolean deleteArmed;

    public FileManagerFrame3D(Path initial) {
        setName("File Manager");

        // Match the window aspect to the usable screen area (above the
        // taskbar) so the decoration's aspect-preserving maximize fills the
        // viewport on both axes.
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        float usableH = tk.getScreenHeight() - Taskbar.getReservedBottomHeight();
        float H = usableH * 0.62f;
        float W = H * tk.getScreenWidth() / usableH;
        setPreferredSize(new Vector3f(W, H, 0.01f));

        // ---- layout metrics (origin at window centre, +x right, +y up) ----
        float pad = Math.min(W, H) * 0.022f;
        float topMargin = H * 0.075f;   // clear of the decoration buttons
        float toolH = H * 0.085f;
        float pathH = H * 0.05f;
        float statusH = H * 0.05f;

        float contentTop = H * 0.5f - topMargin;
        float contentBottom = -H * 0.5f + pad;
        float xLeft = -W * 0.5f + pad;

        float statusCY = contentBottom + statusH * 0.5f;
        float listBottom = contentBottom + statusH + pad * 0.6f;
        float pathCY = contentTop - toolH - pathH * 0.5f;
        float listTop = pathCY - pathH * 0.5f - pad * 0.6f;
        float listCY = (listTop + listBottom) * 0.5f;
        float listH = listTop - listBottom;
        float toolCY = contentTop - toolH * 0.5f;

        rowW = W - 2 * pad;
        rowH = listH / 12.5f;
        textH = rowH * 0.46f;

        // ---- window backdrop ----
        addChild(Ui3D.component(
                Ui3D.at(Ui3D.panel(W, H, 0.006f, WINDOW_BG), 0f, 0f, -0.008f)));

        // ---- title ----
        float titleH = topMargin * 0.46f;
        addChild(Ui3D.component(Ui3D.label("File Manager", W * 0.5f, titleH,
                Ui3D.TEXT_BRIGHT, GlassyText2D.Alignment.LEFT,
                xLeft, H * 0.5f - topMargin * 0.62f, 0.002f)));

        // ---- toolbar ----
        String[] names = {"Back", "Fwd", "Up", "Home", "Refresh", "Open", "Delete"};
        ActionNoArg[] acts = {
            new ActionNoArg() { public void performAction(org.jdesktop.lg3d.wg.event.LgEventSource s) { goBack(); } },
            new ActionNoArg() { public void performAction(org.jdesktop.lg3d.wg.event.LgEventSource s) { goForward(); } },
            new ActionNoArg() { public void performAction(org.jdesktop.lg3d.wg.event.LgEventSource s) { goUp(); } },
            new ActionNoArg() { public void performAction(org.jdesktop.lg3d.wg.event.LgEventSource s) { show(home, true); } },
            new ActionNoArg() { public void performAction(org.jdesktop.lg3d.wg.event.LgEventSource s) { show(currentDir, false); } },
            new ActionNoArg() { public void performAction(org.jdesktop.lg3d.wg.event.LgEventSource s) { doOpen(); } },
            new ActionNoArg() { public void performAction(org.jdesktop.lg3d.wg.event.LgEventSource s) { doDelete(); } },
        };
        float slot = rowW / names.length;
        Button3D[] buttons = new Button3D[names.length];
        for (int i = 0; i < names.length; i++) {
            buttons[i] = new Button3D(names[i], slot * 0.92f, toolH * 0.72f,
                    toolH * 0.34f, Ui3D.BUTTON_OFF, Ui3D.BUTTON_ON, Ui3D.TEXT_BRIGHT, acts[i]);
            buttons[i].setTranslation(xLeft + (i + 0.5f) * slot, toolCY, 0.002f);
            addChild(buttons[i]);
        }
        backBtn = buttons[0];
        fwdBtn = buttons[1];
        deleteBtn = buttons[6];
        deleteArmTimer = new Timer(DELETE_ARM_MS, e -> disarmDelete());
        deleteArmTimer.setRepeats(false);

        // ---- path line ----
        pathText = Ui3D.makeText("", W * 0.96f, pathH, Ui3D.TEXT_ACCENT,
                GlassyText2D.Alignment.LEFT);
        addChild(Ui3D.component(
                Ui3D.at(pathText, xLeft, pathCY - pathH * 0.5f, 0.002f)));

        // ---- file list ----
        list = new ScrollList3D(rowW, listH, rowH, Ui3D.PANEL_BG);
        list.setTranslation(0f, listCY, 0.002f);
        addChild(list);

        // ---- status line ----
        statusText = Ui3D.makeText("", W * 0.95f, statusH * 0.52f, Ui3D.TEXT_DIM,
                GlassyText2D.Alignment.LEFT);
        addChild(Ui3D.component(
                Ui3D.at(statusText, xLeft, statusCY - statusH * 0.26f, 0.002f)));

        show(initial, false);
    }

    // ------------------------------------------------------------------
    // Navigation

    /** Reads and displays {@code dir}, optionally recording history. */
    private void show(Path dir, boolean recordHistory) {
        if (dir == null) {
            return;
        }
        if (recordHistory && currentDir != null && !currentDir.equals(dir)) {
            back.push(currentDir);
            forward.clear();
        }
        currentDir = dir;
        clearSelection();
        disarmDelete();

        List<Path> entries = new ArrayList<>();
        String error = null;
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(entries::add);
        } catch (IOException | RuntimeException e) {
            error = e.getMessage();
        }
        int dirs = 0;
        for (Path p : entries) {
            if (Files.isDirectory(p)) {
                dirs++;
            }
        }
        final int dirCount = dirs;
        entries.sort(Comparator
                .comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        if (entries.size() > ROW_CAP) {
            entries = new ArrayList<>(entries.subList(0, ROW_CAP));
        }

        List<Component3D> rows = new ArrayList<>();
        for (Path p : entries) {
            rows.add(makeRow(p, Files.isDirectory(p)));
        }
        list.setRows(rows);

        pathText.setText(dir.toString());
        backBtn.setLit(!back.isEmpty());
        fwdBtn.setLit(!forward.isEmpty());
        statusText.setText(error != null
                ? "Cannot read " + dir + ": " + error
                : entries.size() + " items (" + dirCount + " folders)"
                    + (entries.size() >= ROW_CAP ? " - listing capped" : ""));
    }

    private void goBack() {
        if (!back.isEmpty()) {
            forward.push(currentDir);
            show(back.pop(), false);
        }
    }

    private void goForward() {
        if (!forward.isEmpty()) {
            back.push(currentDir);
            show(forward.pop(), false);
        }
    }

    private void goUp() {
        Path parent = currentDir == null ? null : currentDir.getParent();
        if (parent != null) {
            show(parent, true);
        }
    }

    // ------------------------------------------------------------------
    // Rows + selection

    /** One listing row: type chip, name, and size/kind, click to open/select. */
    private Component3D makeRow(Path p, boolean dir) {
        Component3D row = new Component3D();
        GlassyPanel bg = Ui3D.panel(rowW, rowH * 0.9f, 0.002f, Ui3D.ROW_OFF);
        bg.setCapability(Shape3D.ALLOW_APPEARANCE_READ);
        bg.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
        row.addChild(Ui3D.component(Ui3D.at(bg, 0f, 0f, -0.001f)));

        float chip = rowH * 0.44f;
        float rxLeft = -rowW * 0.5f;
        row.addChild(Ui3D.component(Ui3D.at(
                Ui3D.panel(chip, chip, 0.002f, dir ? Ui3D.CHIP_DIR : Ui3D.CHIP_FILE),
                rxLeft + rowH * 0.6f, 0f, 0.001f)));

        String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
        row.addChild(Ui3D.component(Ui3D.label(name, rowW * 0.55f, textH,
                Ui3D.TEXT_BRIGHT, GlassyText2D.Alignment.LEFT,
                rxLeft + rowH * 1.2f, 0f, 0.001f)));

        String info = dir ? "folder" : sizeOf(p);
        row.addChild(Ui3D.component(Ui3D.label(info, rowW * 0.3f, textH,
                Ui3D.TEXT_DIM, GlassyText2D.Alignment.RIGHT,
                rowW * 0.5f - rowH * 0.4f, 0f, 0.001f)));

        row.addListener(new MouseClickedEventAdapter(new ActionNoArg() {
            public void performAction(org.jdesktop.lg3d.wg.event.LgEventSource s) {
                if (dir) {
                    show(p, true);
                } else {
                    select(bg, p);
                }
            }
        }));
        return row;
    }

    private void select(GlassyPanel bg, Path p) {
        clearSelection();
        selected = p;
        selectedBg = bg;
        bg.setAppearance(Ui3D.appearance(Ui3D.ROW_ON));
        statusText.setText("Selected: " + p.getFileName() + " (" + sizeOf(p) + ")");
    }

    private void clearSelection() {
        if (selectedBg != null) {
            selectedBg.setAppearance(Ui3D.appearance(Ui3D.ROW_OFF));
            selectedBg = null;
        }
        selected = null;
    }

    private static String sizeOf(Path p) {
        try {
            return Ui3D.humanBytes(Files.size(p));
        } catch (IOException e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------
    // Actions

    private void doOpen() {
        if (selected == null) {
            statusText.setText("Select a file to open (click its row).");
            return;
        }
        boolean ok = Opener.open(selected);
        statusText.setText(ok
                ? "Opening " + selected.getFileName() + "..."
                : "No handler available for " + selected.getFileName());
    }

    /** Two-click confirm, then trash; disarms itself after a timeout. */
    private void doDelete() {
        if (selected == null) {
            statusText.setText("Select a file to delete (click its row).");
            return;
        }
        if (!deleteArmed) {
            deleteArmed = true;
            deleteBtn.setText("Confirm?");
            deleteBtn.setLit(true);
            deleteArmTimer.restart();
            statusText.setText("Click Delete again to trash "
                    + selected.getFileName());
            return;
        }
        Path victim = selected;
        disarmDelete();
        boolean ok = Opener.trash(victim);
        statusText.setText(ok
                ? "Trashed " + victim.getFileName()
                : "Could not trash " + victim.getFileName());
        show(currentDir, false);
    }

    private void disarmDelete() {
        deleteArmTimer.stop();
        if (deleteArmed) {
            deleteArmed = false;
            deleteBtn.setText("Delete");
            deleteBtn.setLit(false);
        }
    }
}
