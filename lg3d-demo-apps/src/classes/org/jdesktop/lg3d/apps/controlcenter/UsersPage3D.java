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
package org.jdesktop.lg3d.apps.controlcenter;

import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.apps.uikit.Button3D;
import org.jdesktop.lg3d.apps.uikit.ScrollList3D;
import org.jdesktop.lg3d.apps.uikit.Ui3D;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.system.UserService;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Color4f;

/**
 * The Users page: a read-only browser of local accounts backed by
 * {@link UserService} - a user list on the left (with a Show-System toggle)
 * and the selected account's details and group membership on the right.
 *
 * <p>Account administration (add/edit/password/groups) needs privileged
 * commands and free-form text entry, which the native 3D widget set does not
 * provide; the page says so instead of pretending otherwise. Listing and
 * detail lookups run off the scene thread.</p>
 */
public class UsersPage3D implements ControlPanel {

    private static final int DETAIL_LINES = 10;

    private static final Color4f WARN = new Color4f(0.95f, 0.75f, 0.40f, 1.0f);

    private Component3D root;
    private ScrollList3D list;
    private Button3D systemBtn;
    private final GlassyText2D[] detail = new GlassyText2D[DETAIL_LINES];
    private GlassyText2D statusText;

    private float rowW;
    private float rowH;
    private float textH;

    private boolean showSystem;
    private boolean loading;
    private final List<UserService.User> users = new ArrayList<>();
    private UserService.User selected;
    private GlassyPanel selectedBg;

    @Override
    public String displayName() {
        return "Users";
    }

    @Override
    public Component3D component(float w, float h) {
        if (root != null) {
            return root;
        }
        root = new Component3D();

        float pad = h * 0.02f;
        float btnH = h * 0.07f;
        float topY = h * 0.5f - btnH * 0.5f;

        // ---- toolbar (top-right): Show System toggle + Refresh ----
        float btnW = w * 0.16f;
        systemBtn = new Button3D("Show System", btnW, btnH, btnH * 0.4f,
                Ui3D.BUTTON_OFF, Ui3D.BUTTON_ON, Ui3D.TEXT_BRIGHT,
                new ActionNoArg() {
                    public void performAction(LgEventSource s) {
                        showSystem = !showSystem;
                        systemBtn.setLit(showSystem);
                        reload();
                    }
                });
        systemBtn.setTranslation(w * 0.5f - pad - btnW * 1.55f, topY, 0.001f);
        root.addChild(systemBtn);

        Button3D refreshBtn = new Button3D("Refresh", btnW, btnH, btnH * 0.4f,
                Ui3D.BUTTON_OFF, Ui3D.BUTTON_ON, Ui3D.TEXT_BRIGHT,
                new ActionNoArg() {
                    public void performAction(LgEventSource s) {
                        reload();
                    }
                });
        refreshBtn.setTranslation(w * 0.5f - pad - btnW * 0.5f, topY, 0.001f);
        root.addChild(refreshBtn);

        // ---- user list (left) ----
        float listTop = h * 0.5f - btnH - pad * 1.5f;
        float listBottom = -h * 0.5f + pad + h * 0.05f;
        float listH = listTop - listBottom;
        rowW = w * 0.34f;
        rowH = listH / 12.5f;
        textH = rowH * 0.46f;
        list = new ScrollList3D(rowW, listH, rowH, Ui3D.PANEL_BG);
        list.setTranslation(-w * 0.5f + pad + rowW * 0.5f,
                (listTop + listBottom) * 0.5f, 0.001f);
        root.addChild(list);

        // ---- detail lines (right) ----
        float dx = -w * 0.5f + pad + rowW + pad * 1.5f;
        float detailW = w * 0.5f - dx;
        float lineH = (listTop - listBottom) / DETAIL_LINES;
        float lineTextH = lineH * 0.7f;
        for (int i = 0; i < DETAIL_LINES; i++) {
            detail[i] = Ui3D.makeText("", detailW, lineTextH, Ui3D.TEXT_DIM,
                    GlassyText2D.Alignment.LEFT);
            root.addChild(Ui3D.component(Ui3D.at(detail[i], dx,
                    listTop - i * lineH - lineH * 0.5f - lineTextH * 0.5f, 0.001f)));
        }

        // ---- status line ----
        statusText = Ui3D.makeText(
                "Read-only: account changes need a terminal (useradd / usermod / gpasswd).",
                w * 0.96f, h * 0.035f, WARN, GlassyText2D.Alignment.LEFT);
        root.addChild(Ui3D.component(Ui3D.at(statusText,
                -w * 0.5f + pad, -h * 0.5f + pad, 0.001f)));

        reload();
        return root;
    }

    @Override
    public void onShow() {
        reload();
    }

    // ------------------------------------------------------------------

    /** Lists accounts off the scene thread, then rebuilds rows on the EDT. */
    private void reload() {
        if (loading || root == null) {
            return;
        }
        loading = true;
        final boolean sys = showSystem;
        Thread loader = new Thread(() -> {
            List<UserService.User> result;
            try {
                result = UserService.listUsers(sys);
            } catch (RuntimeException ex) {
                loading = false;
                return;
            }
            SwingUtilities.invokeLater(() -> {
                loading = false;
                users.clear();
                users.addAll(result);
                selected = null;
                selectedBg = null;
                List<Component3D> rows = new ArrayList<>();
                for (UserService.User u : users) {
                    rows.add(makeRow(u));
                }
                list.setRows(rows);
                for (GlassyText2D t : detail) {
                    t.setText("");
                }
            });
        }, "UsersPage3D:loader");
        loader.setDaemon(true);
        loader.start();
    }

    /** One account row: login name + uid; click shows the details. */
    private Component3D makeRow(UserService.User u) {
        Component3D row = new Component3D();
        GlassyPanel bg = Ui3D.panel(rowW, rowH * 0.9f, 0.002f, Ui3D.ROW_OFF);
        bg.setCapability(Shape3D.ALLOW_APPEARANCE_READ);
        bg.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
        row.addChild(Ui3D.component(Ui3D.at(bg, 0f, 0f, -0.001f)));

        row.addChild(Ui3D.component(Ui3D.label(u.getName(), rowW * 0.6f, textH,
                Ui3D.TEXT_BRIGHT, GlassyText2D.Alignment.LEFT,
                -rowW * 0.5f + rowH * 0.4f, 0f, 0.001f)));
        row.addChild(Ui3D.component(Ui3D.label(String.valueOf(u.getUid()),
                rowW * 0.3f, textH, Ui3D.TEXT_DIM, GlassyText2D.Alignment.RIGHT,
                rowW * 0.5f - rowH * 0.4f, 0f, 0.001f)));

        row.addListener(new MouseClickedEventAdapter(new ActionNoArg() {
            public void performAction(LgEventSource s) {
                select(bg, u);
            }
        }));
        return row;
    }

    private void select(GlassyPanel bg, UserService.User u) {
        if (selectedBg != null && selectedBg != bg) {
            selectedBg.setAppearance(Ui3D.appearance(Ui3D.ROW_OFF));
        }
        selected = u;
        selectedBg = bg;
        bg.setAppearance(Ui3D.appearance(Ui3D.ROW_ON));
        statusText.setText("Loading details for " + u.getName() + "...");

        // Group membership parses /etc/group; do it off the scene thread.
        Thread loader = new Thread(() -> {
            List<String> groups;
            try {
                groups = UserService.groupsFor(u.getName());
            } catch (RuntimeException ex) {
                groups = List.of("(unavailable)");
            }
            final List<String> gs = groups;
            SwingUtilities.invokeLater(() -> {
                if (selected != u) {
                    return;
                }
                showDetails(u, gs);
            });
        }, "UsersPage3D:details");
        loader.setDaemon(true);
        loader.start();
    }

    private void showDetails(UserService.User u, List<String> groups) {
        List<String> ls = new ArrayList<>();
        ls.add("Login:      " + u.getName());
        ls.add("Full name:  " + u.getFullName());
        ls.add("Type:       " + (u.isSystem() ? "System / service" : "Standard"));
        ls.add("UID:        " + u.getUid());
        ls.add("GID:        " + u.getGid());
        ls.add("Home:       " + u.getHome());
        ls.add("Shell:      " + u.getShell());
        ls.add("Groups:     " + String.join(", ", groups));
        if (u.getAvatarPath() != null) {
            ls.add("Avatar:     " + u.getAvatarPath());
        }
        for (int i = 0; i < detail.length; i++) {
            detail[i].setText(i < ls.size() ? ls.get(i) : "");
        }
        statusText.setText(
                "Read-only: account changes need a terminal (useradd / usermod / gpasswd).");
    }
}
