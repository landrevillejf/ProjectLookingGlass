/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules: the plugin layer that replaces the NetBeans Platform
 * module system and the dead scannotation class scan (milestone M4).
 *
 * The {@link org.jdesktop.wonderland.modules.WonderlandPluginLoader} discovers
 * the feature modules through {@link org.jdesktop.wonderland.modules.api.WonderlandPlugin}
 * service providers, orders them by their declared requirements and brings them
 * up headless. The shipped low-risk modules -- chat, imageviewer, user-list,
 * stickynote, whiteboard -- sit in sibling packages; their Swing / Java 3D
 * presentation arrives with the desktop integration at M6/M7.
 *
 * See docs/wonderland-migration.md for the milestone plan.
 */
package org.jdesktop.wonderland.modules;
