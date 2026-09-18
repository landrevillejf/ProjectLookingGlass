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
package org.jdesktop.lg3d.scenemanager.utils.taskbar.stack;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import org.jdesktop.lg3d.utils.action.AppLaunchAction;
import org.jdesktop.lg3d.utils.system.Opener;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.SwingNode;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jogamp.vecmath.Vector3f;

/**
 * The expanded view of a {@link FolderStack}: a lightweight {@link Frame3D}
 * window hosting a Swing panel that fans the folder's most-recent entries out
 * along an arc of clickable icon cards (an OSX/Leopard-style stack "fan").
 *
 * <p>A header carries the folder name and count, a prominent
 * <em>Show in File Manager</em> action and a close button. The most-recent
 * {@value #MAX_FAN} entries are laid out along the arc, newest at the leading
 * (left) end.</p>
 *
 * <p>Clicking a card opens it: files go to {@code xdg-open} via {@link Opener},
 * sub-folders open in the lg3d file manager when it is on the classpath (falling
 * back to {@code xdg-open}). Escape or the close button dismisses the fan.</p>
 */
public class FolderStackPopup {

    /** Fully-qualified main class of the Stage 3 file manager. */
    static final String FILE_MANAGER_CLASS =
            "org.jdesktop.lg3d.apps.filemanager.FileManager";

    private static final int PANEL_W = 600;
    private static final int PANEL_H = 460;

    /** Most entries drawn on the fan arc (the newest ones). */
    private static final int MAX_FAN = 8;
    /** Card cell size: a filename pill beside an icon. */
    private static final int CARD_W = 210;
    private static final int CARD_H = 52;
    /** Scaled icon size painted on the right of a card. */
    private static final int ICON = 46;
    /** Filename pill painted on the left of a card. */
    private static final int PILL_W = CARD_W - ICON - 8;
    private static final int PILL_H = 22;
    /** Horizontal bow of the vertical fan arc. */
    private static final int ARC_BOW = 40;
    /** Top of the fan area, just below the header. */
    private static final int FAN_TOP = 44;

    private final FolderStackModel model;
    private final Frame3D frame3d;
    private final FanPanel panel;

    public FolderStackPopup(FolderStackModel model) {
        this.model = model;
        this.panel = new FanPanel();

        SwingNode node = new SwingNode();
        node.setJPanel(panel);
        node.setTransparency(0.0f);

        frame3d = new Frame3D();
        frame3d.setName(model.getDisplayName() + " stack");
        frame3d.addChild(node);

        Toolkit3D tk = Toolkit3D.getToolkit3D();
        frame3d.setPreferredSize(new Vector3f(
                tk.widthNativeToPhysical(PANEL_W),
                tk.heightNativeToPhysical(PANEL_H), 0.01f));
    }

    /** Rescans the folder and shows (or re-shows) the fan. */
    public void show() {
        model.refresh();
        panel.reload();
        frame3d.changeEnabled(true);
        frame3d.changeVisible(true);
        panel.requestFocusInWindow();
    }

    /** Hides the fan. */
    public void hide() {
        frame3d.changeEnabled(false);
    }

    public boolean isVisible() {
        return frame3d.isEnabled();
    }

    /**
     * Opens a folder in the lg3d file manager at that directory when the file
     * manager is on the classpath; otherwise falls back to {@code xdg-open}
     * (the desktop's own file manager). Guarded so a missing file manager never
     * throws.
     */
    static boolean openInFileManager(Path dir) {
        try {
            Class.forName(FILE_MANAGER_CLASS);
            new AppLaunchAction("java " + FILE_MANAGER_CLASS + " "
                    + dir.toAbsolutePath(),
                    FolderStackPopup.class.getClassLoader()).performAction(null);
            return true;
        } catch (ClassNotFoundException notPresent) {
            return Opener.open(dir);
        } catch (RuntimeException e) {
            return Opener.open(dir);
        }
    }

    private void openItem(FolderStackModel.StackItem item) {
        if (item == null) {
            return;
        }
        if (item.isDirectory()) {
            openInFileManager(item.getPath());
        } else {
            Opener.open(item.getPath());
        }
    }

    // ------------------------------------------------------------------

    /**
     * Renders the stack as a fan: a rounded translucent glass backdrop with the
     * most-recent entries positioned along an upward arc, plus a header with the
     * title, a <em>Show in File Manager</em> action and a close button.
     */
    private final class FanPanel extends JPanel {
        private final JLabel titleLabel = new JLabel();
        private final JLabel emptyLabel =
                new JLabel("This folder is empty", SwingConstants.CENTER);
        private final JButton showButton = new JButton("Show in File Manager");
        private final JButton closeButton = new JButton("Close");

        FanPanel() {
            setLayout(null);
            setOpaque(false);
            setPreferredSize(new Dimension(PANEL_W, PANEL_H));
            setFocusable(true);

            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));
            titleLabel.setForeground(Color.WHITE);
            titleLabel.setBounds(16, 10, PANEL_W - 320, 26);
            add(titleLabel);

            showButton.setBounds(PANEL_W - 306, 8, 214, 30);
            showButton.addActionListener(
                    e -> openInFileManager(model.getDirectory()));
            add(showButton);

            closeButton.setBounds(PANEL_W - 84, 8, 68, 30);
            closeButton.addActionListener(e -> hide());
            add(closeButton);

            emptyLabel.setForeground(new Color(210, 216, 228));
            emptyLabel.setBounds(0, PANEL_H / 2 - 20, PANEL_W, 30);
            emptyLabel.setVisible(false);
            add(emptyLabel);

            getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(KeyStroke.getKeyStroke("ESCAPE"), "closeStack");
            getActionMap().put("closeStack", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    hide();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(28, 32, 42, 210));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 26, 26);
            g2.setColor(new Color(255, 255, 255, 40));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 26, 26);
            g2.dispose();
        }

        /** Rebuilds the fan cards from the model's current (recent-first) items. */
        void reload() {
            for (Component c : getComponents()) {
                if (c instanceof FanCard) {
                    remove(c);
                }
            }
            List<FolderStackModel.StackItem> items = model.getItems();
            int total = items.size();
            int n = Math.min(total, MAX_FAN);
            StringBuilder title = new StringBuilder(model.getDisplayName());
            title.append("  \u2014  ").append(total)
                    .append(total == 1 ? " item" : " items");
            if (total > n) {
                title.append("  (showing ").append(n).append(" most recent)");
            }
            titleLabel.setText(title.toString());
            emptyLabel.setVisible(n == 0);

            if (n > 0) {
                // Vertical fan, newest at top, bowed like an opening arc.
                final int usable = PANEL_H - FAN_TOP - 8 - CARD_H;
                for (int i = 0; i < n; i++) {
                    double t = (n == 1) ? 0.5 : (double) i / (n - 1);
                    int x = (int) Math.round(PANEL_W / 2.0
                            + ARC_BOW * Math.sin(Math.PI * t));
                    int y = (int) Math.round(FAN_TOP + t * usable);
                    final FolderStackModel.StackItem item = items.get(i);
                    FanCard card = new FanCard();
                    card.setItem(scaleIcon(item.getIcon(), ICON),
                            truncate(item.getName(), 14));
                    card.setToolTipText(item.getName());
                    card.setOnClick(() -> openItem(item));
                    card.setBounds(x - CARD_W / 2, y, CARD_W, CARD_H);
                    add(card);
                }
            }
            revalidate();
            repaint();
        }
    }

    /** A single entry on the fan: an icon beside a filename pill. */
    private static final class FanCard extends JPanel {
        private final JLabel iconLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();
        private boolean hover = false;
        private Runnable onClick;

        FanCard() {
            // Null layout with explicit bounds: the offscreen SwingNode render
            // never runs layout managers, so children must carry real bounds.
            super(null);
            setOpaque(false);
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            iconLabel.setBounds(CARD_W - ICON, (CARD_H - ICON) / 2, ICON, ICON);
            nameLabel.setHorizontalAlignment(SwingConstants.CENTER);
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, 11f));
            nameLabel.setForeground(Color.WHITE);
            nameLabel.setBounds(0, (CARD_H - PILL_H) / 2, PILL_W, PILL_H);
            add(iconLabel);
            add(nameLabel);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }
                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (onClick != null) {
                        onClick.run();
                    }
                }
            });
        }

        void setItem(ImageIcon image, String text) {
            iconLabel.setIcon(image);
            nameLabel.setText(text);
        }

        void setOnClick(Runnable action) {
            this.onClick = action;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            // Filename pill behind the label (reference look).
            g2.setColor(new Color(18, 22, 30, hover ? 235 : 200));
            g2.fillRoundRect(0, (CARD_H - PILL_H) / 2, PILL_W, PILL_H, 12, 12);
            g2.setColor(new Color(255, 255, 255, hover ? 150 : 70));
            g2.drawRoundRect(0, (CARD_H - PILL_H) / 2, PILL_W, PILL_H, 12, 12);
            // Hover highlight behind the icon.
            if (hover) {
                g2.setColor(new Color(255, 255, 255, 60));
                g2.fillRoundRect(CARD_W - ICON - 3, (CARD_H - ICON) / 2 - 3,
                        ICON + 6, ICON + 6, 14, 14);
            }
            g2.dispose();
        }
    }

    /**
     * Renders a Swing {@link Icon} into a square image fitted to {@code size},
     * preserving aspect ratio. Returns null if the icon is missing or cannot be
     * painted, so a card degrades to a text-only entry.
     */
    private static ImageIcon scaleIcon(Icon src, int size) {
        if (src == null) {
            return null;
        }
        try {
            int w = src.getIconWidth();
            int h = src.getIconHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            BufferedImage base =
                    new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gb = base.createGraphics();
            src.paintIcon(null, gb, 0, 0);
            gb.dispose();

            BufferedImage out =
                    new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            double s = Math.min((double) size / w, (double) size / h);
            int dw = Math.max(1, (int) Math.round(w * s));
            int dh = Math.max(1, (int) Math.round(h * s));
            g.drawImage(base, (size - dw) / 2, (size - dh) / 2, dw, dh, null);
            g.dispose();
            return new ImageIcon(out);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return (s.length() > max) ? s.substring(0, max - 1) + "\u2026" : s;
    }
}
