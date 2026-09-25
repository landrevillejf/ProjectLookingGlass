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
package org.jdesktop.lg3d.widgets.builtin;

import java.time.LocalDate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.utils.action.AppLaunchAction;
import org.jdesktop.lg3d.widgets.api.AbstractWidget;
import org.jdesktop.lg3d.widgets.api.WidgetContext;

/**
 * The 3D desktop's calendar widget: a thin {@link AbstractWidget} that hosts the
 * pure-Swing {@link CalendarCard} in a {@code SwingNode}. All the month layout
 * and painting live in the card, which the 2D desktop hosts directly.
 *
 * <p>This host adds the one piece of behaviour that needs the 3D desktop: it
 * wires the card's day-double-click to opening that day in the Agenda 3D app.
 * The launch is loose-coupled - it runs the same in-JVM {@code java <main>}
 * command the Agenda's start-menu descriptor uses, through the same
 * {@link AppLaunchAction}, passing the clicked ISO date as an argument - so
 * lg3d-widgets keeps no compile-time dependency on the incubator app.</p>
 */
public class CalendarWidget extends AbstractWidget {

    private static final Logger logger = Logger.getLogger("lg.widgets");

    public static final String ID = CalendarCard.ID;

    /**
     * The in-JVM launch command for the Agenda 3D app, matching its
     * {@code agenda3d.lgcfg} start-menu descriptor. {@link AppLaunchAction}
     * appends everything after the main class as the app's arguments, so the
     * clicked date is passed by suffixing it here.
     */
    static final String AGENDA_COMMAND =
            "java org.jdesktop.lg3d.apps.orgchart.ui.agenda.Agenda3D";

    private CalendarCard card;

    public CalendarWidget() {
        super(ID, "Calendar");
    }

    @Override
    public void init(WidgetContext context) {
        super.init(context);
        card = new CalendarCard();
        card.attach(context.config(), getInstanceId(), context.scheduler());
        card.setOnOpenDate(this::openAgenda);
        setSwingPanel(card);
    }

    @Override
    public void start() {
        card.start();
        scheduleTick(card.tickPeriodMillis(), card::tick);
    }

    /**
     * Opens the Agenda 3D app centred on {@code date}'s week, launching it
     * in-JVM exactly as the start menu does. Guarded so a failed launch (e.g. no
     * live scene/event connector yet) is logged, never thrown into the Swing
     * input dispatch that triggered it.
     */
    void openAgenda(LocalDate date) {
        if (date == null) {
            return;
        }
        String command = AGENDA_COMMAND + " " + date;
        try {
            new AppLaunchAction(command, getClass().getClassLoader()).performAction(null);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Failed to open Agenda 3D at " + date, t);
        }
    }
}
