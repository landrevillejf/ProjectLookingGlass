/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.calculator;

import org.jdesktop.lg3d.apps.TitledSwingWindow;

/**
 * The Calculator application: the {@link CalculatorPanel} (scientific key pad,
 * memory register, live preview and history) presented as an integrated 3D
 * desktop window (title bar plus minimize / maximize / close) via
 * {@link TitledSwingWindow}, which hosts the Swing panel on a
 * {@code SwingNode} quad below a draggable glassy title bar.
 */
public class Calculator {

    public static void main(String[] args) {
        new Calculator();
    }

    public Calculator() {
        // Metal, not the platform Synth LAF: Synth widgets NPE when SwingNode
        // paints them offscreen. Must run before the panel is constructed.
        TitledSwingWindow.installHostedLookAndFeel();
        TitledSwingWindow.show(
                "Calculator",
                new CalculatorPanel(),
                CalculatorPanel.WIDTH_PX,
                CalculatorPanel.HEIGHT_PX);
    }
}
