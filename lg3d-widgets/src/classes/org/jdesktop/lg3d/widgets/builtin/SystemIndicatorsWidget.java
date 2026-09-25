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

import org.jdesktop.lg3d.widgets.api.AbstractWidget;
import org.jdesktop.lg3d.widgets.api.WidgetContext;

/**
 * The 3D desktop's system-indicators widget (network / volume / brightness /
 * battery): a thin {@link AbstractWidget} that hosts the pure-Swing
 * {@link SystemIndicatorsCard} in a {@code SwingNode}. All the platform polling
 * and painting live in the card, which the 2D desktop hosts directly.
 */
public class SystemIndicatorsWidget extends AbstractWidget {
    public static final String ID = SystemIndicatorsCard.ID;

    private SystemIndicatorsCard card;

    public SystemIndicatorsWidget() {
        super(ID, "System Indicators");
    }

    @Override
    public void init(WidgetContext context) {
        super.init(context);
        card = new SystemIndicatorsCard();
        card.attach(context.config(), getInstanceId(), context.scheduler());
        setSwingPanel(card);
    }

    @Override
    public void start() {
        card.start();
        scheduleTick(card.tickPeriodMillis(), card::tick);
    }
}
