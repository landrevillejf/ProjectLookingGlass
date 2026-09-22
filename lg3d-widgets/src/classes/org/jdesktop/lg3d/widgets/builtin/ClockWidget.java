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

import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.widgets.api.AbstractWidget;
import org.jdesktop.lg3d.widgets.api.WidgetContext;

/**
 * The 3D desktop's clock widget: a thin {@link AbstractWidget} that hosts the
 * pure-Swing {@link ClockCard} in a {@code SwingNode} and forwards the click
 * that toggles the analog/digital face. All the clock's logic lives in the card,
 * which the conventional Swing 2D desktop hosts directly.
 */
public class ClockWidget extends AbstractWidget {
    public static final String ID = ClockCard.ID;

    private ClockCard card;

    public ClockWidget() {
        super(ID, "Clock");
    }

    @Override
    public void init(WidgetContext context) {
        super.init(context);
        card = new ClockCard();
        card.attach(context.config(), getInstanceId(), context.scheduler());
        setSwingPanel(card);
        // Toggle the face on click. The SwingNode is propagatable, so this node
        // receives the click even though the pointer is over the Swing content.
        addListener(new MouseClickedEventAdapter(new ActionNoArg() {
            @Override
            public void performAction(LgEventSource source) {
                card.onClick();
            }
        }));
    }

    @Override
    public void start() {
        card.tick();
        scheduleTick(card.tickPeriodMillis(), card::tick);
    }
}
