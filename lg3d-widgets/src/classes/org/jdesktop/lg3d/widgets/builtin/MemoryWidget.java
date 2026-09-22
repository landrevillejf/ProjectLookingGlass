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

import org.jdesktop.lg3d.widgets.api.AbstractWidget;
import org.jdesktop.lg3d.widgets.api.WidgetContext;

/**
 * The 3D desktop's memory widget: a thin {@link AbstractWidget} that hosts the
 * pure-Swing {@link MemoryCard} in a {@code SwingNode}. All the sampling and
 * painting live in the card, which the 2D desktop hosts directly.
 */
public class MemoryWidget extends AbstractWidget {
    public static final String ID = MemoryCard.ID;

    private MemoryCard card;

    public MemoryWidget() {
        super(ID, "Memory");
    }

    @Override
    public void init(WidgetContext context) {
        super.init(context);
        card = new MemoryCard();
        card.attach(context.config(), getInstanceId(), context.scheduler());
        setSwingPanel(card);
    }

    @Override
    public void start() {
        card.tick();
        scheduleTick(card.tickPeriodMillis(), card::tick);
    }
}
