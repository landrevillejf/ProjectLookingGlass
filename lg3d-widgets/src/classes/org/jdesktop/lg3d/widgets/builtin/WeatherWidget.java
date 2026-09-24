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

import org.jdesktop.lg3d.utils.action.ActionInt;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MouseWheelEventAdapter;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.widgets.api.AbstractWidget;
import org.jdesktop.lg3d.widgets.api.WidgetContext;

/**
 * The 3D desktop's weather widget: a thin {@link AbstractWidget} that hosts the
 * pure-Swing {@link WeatherCard} in a {@code SwingNode} and forwards the click
 * (toggle &deg;C/&deg;F) and mouse-wheel (cycle city) gestures. All the fetching,
 * parsing and painting live in the card, which the 2D desktop hosts directly.
 */
public class WeatherWidget extends AbstractWidget {
    public static final String ID = WeatherCard.ID;

    private WeatherCard card;

    public WeatherWidget() {
        super(ID, "Weather");
    }

    @Override
    public void init(WidgetContext context) {
        super.init(context);
        card = new WeatherCard();
        card.attach(context.config(), getInstanceId(), context.scheduler());
        setSwingPanel(card);

        // Click toggles the temperature unit (instant; no re-fetch needed).
        addListener(new MouseClickedEventAdapter(new ActionNoArg() {
            @Override
            public void performAction(LgEventSource source) {
                card.onClick();
            }
        }));

        // Wheel cycles the preset city and fetches it right away.
        addListener(new MouseWheelEventAdapter(new ActionInt() {
            @Override
            public void performAction(LgEventSource source, int value) {
                card.onWheel(value);
            }
        }));
    }

    @Override
    public void start() {
        // scheduleTick runs the task immediately, then every refresh period.
        scheduleTick(card.tickPeriodMillis(), card::tick);
    }
}
