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

import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.widgets.api.AbstractWidget;
import org.jdesktop.lg3d.widgets.api.WidgetContext;

/**
 * The 3D desktop's temperature widget: a thin {@link AbstractWidget} that hosts
 * the pure-Swing {@link TemperatureCard} in a {@code SwingNode} and forwards the
 * click that cycles the sensor zones. All the sampling and painting live in the
 * card, which the 2D desktop hosts directly.
 */
public class TemperatureWidget extends AbstractWidget {
    public static final String ID = TemperatureCard.ID;

    private TemperatureCard card;

    public TemperatureWidget() {
        super(ID, "Temperature");
    }

    @Override
    public void init(WidgetContext context) {
        super.init(context);
        card = new TemperatureCard();
        card.attach(context.config(), getInstanceId(), context.scheduler());
        setSwingPanel(card);
        // Cycle to the next zone on click.
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
