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
package org.jdesktop.lg3d.widgets.api;

import java.util.List;
import org.jdesktop.lg3d.wg.Component3D;
import org.jogamp.vecmath.Vector3f;

/**
 * A test-only {@link WidgetProvider}, registered through
 * {@code src/test/resources/META-INF/services/...WidgetProvider} so the
 * {@link WidgetRegistry} singleton discovers it alongside the built-ins.
 *
 * <p>Its single descriptor's factory hands back a {@link StubWidget} whose
 * scene-graph accessors return null, which lets {@link WidgetRegistry#create}
 * be exercised down its <em>success</em> path headlessly: creating a real
 * built-in widget would instantiate a Java 3D {@code Component3D}, which needs
 * a live 3D desktop and is probe-verified instead.</p>
 */
public class StubWidgetProvider implements WidgetProvider {

    /** Id contributed by this provider; kept distinct from every built-in id. */
    public static final String STUB_ID = "test-stub";

    @Override
    public List<WidgetDescriptor> descriptors() {
        return List.of(new WidgetDescriptor(STUB_ID, "Test Stub", "Testing",
                null, 10, 10, StubWidget::new));
    }

    /** Minimal {@link Widget} that never builds a peer or a scene-graph node. */
    public static final class StubWidget implements Widget {
        @Override public String id() { return STUB_ID; }
        @Override public String displayName() { return "Test Stub"; }
        @Override public void init(WidgetContext context) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void dispose() { }
        @Override public Component3D node() { return null; }
        @Override public Vector3f getPreferredSize() { return null; }
    }
}
