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
package org.jdesktop.lg3d.apps.uikit;

import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.sg.utils.transparency.TransparencyOrderedGroup;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.action.AppearanceChangeAction;
import org.jdesktop.lg3d.utils.action.ScaleActionBoolean;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MouseEnteredEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jogamp.vecmath.Color4f;

/**
 * A glassy push button centered on its own origin: a translucent panel with a
 * centered label, hover appearance + scale highlight, and a click action -
 * the idiom proven by {@code Lg3dHelp.Button} and Image Studio's
 * {@code Ui3D.button}, promoted to a class so callers can also retitle
 * ({@link #setText}) and light ({@link #setLit}) it (armed/confirm states,
 * active sort tabs, ...).
 *
 * <p>The caller positions the button with {@link #setTranslation}.</p>
 */
public class Button3D extends Component3D {

    private final GlassyPanel bg;
    private final GlassyText2D label;
    private final Color4f off;
    private final Color4f on;

    public Button3D(String text, float w, float h, float textH,
            Color4f off, Color4f on, Color4f textCol, ActionNoArg onClick) {
        this.off = off;
        this.on = on;

        TransparencyOrderedGroup tog = new TransparencyOrderedGroup();
        bg = Ui3D.panel(w, h, 0.002f, off);
        bg.setCapability(Shape3D.ALLOW_APPEARANCE_READ);
        bg.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
        tog.addChild(bg);
        label = Ui3D.makeText(text, w * 0.94f, textH, textCol,
                GlassyText2D.Alignment.CENTER);
        tog.addChild(Ui3D.at(label, 0.0f, -textH * 0.5f, 0.0025f));
        addChild(tog);

        addListener(new MouseEnteredEventAdapter(
                new AppearanceChangeAction(bg, Ui3D.appearance(on))));
        addListener(new MouseEnteredEventAdapter(
                new ScaleActionBoolean(this, 1.06f, 120)));
        if (onClick != null) {
            addListener(new MouseClickedEventAdapter(onClick));
        }
        setCursor(Cursor3D.SMALL_CURSOR);
    }

    /** Replaces the button label in place (e.g. "Delete" -> "Confirm?"). */
    public void setText(String text) {
        label.setText(text);
    }

    /**
     * Persistently lights ({@code true}) or unlights the button background,
     * for toggle/armed states. Independent of the transient hover highlight.
     */
    public void setLit(boolean lit) {
        bg.setAppearance(Ui3D.appearance(lit ? on : off));
    }
}
