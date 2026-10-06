/**
 * Project Looking Glass
 *
 * $RCSfile: X11WindowManagerHints.java,v $
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
 *
 * $Revision: 1.6 $
 * $Date: 2007-04-10 23:25:10 $
 * $State: Exp $
 */
/** Copyright (c) 2005 Amir Bukhari
 *
 * Permission to use, copy, modify, distribute, and sell this software and its
 * documentation for any purpose is hereby granted without fee, provided that
 * the above copyright notice appear in all copies and that both that
 * copyright notice and this permission notice appear in supporting
 * documentation, and that the name of Amir Bukhari not be used in
 * advertising or publicity pertaining to distribution of the software without
 * specific, written prior permission.  Amir Bukhari makes no
 * representations about the suitability of this software for any purpose.  It
 * is provided "as is" without express or implied warranty.
 *
 * AMIR BUKHARI DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE,
 * INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS, IN NO
 * EVENT SHALL AMIR BUKHARI BE LIABLE FOR ANY SPECIAL, INDIRECT OR
 * CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE,
 * DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
 * TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
 * PERFORMANCE OF THIS SOFTWARE.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.logging.Logger;
import gnu.x11.Atom;
import gnu.x11.Display;
import gnu.x11.Window;
import gnu.x11.Enum;

/**
 * @author bukhari
 */
class X11WindowManagerHints {

    protected static final Logger logger = Logger.getLogger("lg.x11");

    final static int BASENR = 5;
    static Atom netSupported = null;
    static Atom netallowedAction = null;
    static Atom netWmState = null;
    static Atom netWmName = null;
    static Atom netSupportingWmCheck = null;
    static Atom windowTypes[] = new Atom[9];
    static Atom allowedActions[] = new Atom[4];
    static Atom wmStates[] = new Atom[4];    
        
    static void initX11WindowManagerHint(Display display) {
	netSupported = (Atom) Atom.intern(display, "_NET_SUPPORTED");
	netWmName = (Atom) Atom.intern(display, "_NET_WM_NAME");

	netSupportingWmCheck = (Atom) Atom.intern(display,
		"_NET_SUPPORTING_WM_CHECK");
	windowTypes[0] = (Atom) Atom.intern(display, "_NET_WM_WINDOW_TYPE");
	windowTypes[1] = (Atom) Atom.intern(display,
		"_NET_WM_WINDOW_TYPE_NORMAL");
	windowTypes[2] = (Atom) Atom.intern(display,
		"_NET_WM_WINDOW_TYPE_DIALOG");
	windowTypes[3] = (Atom) Atom.intern(display,
		"_NET_WM_WINDOW_TYPE_SPLASH");
	windowTypes[4] = (Atom) Atom.intern(display,
		"_NET_WM_WINDOW_TYPE_DESKTOP");
	windowTypes[5] = (Atom) Atom.intern(display,
		"_NET_WM_WINDOW_TYPE_DOCK");
	windowTypes[6] = (Atom) Atom.intern(display,
		"_NET_WM_WINDOW_TYPE_TOOLBAR");
	windowTypes[7] = (Atom) Atom.intern(display,
		"_NET_WM_WINDOW_TYPE_MENU");
	windowTypes[8] = (Atom) Atom.intern(display,
		"_NET_WM_WINDOW_TYPE_UTILITY");

	netWmState = (Atom) Atom.intern(display, "_NET_WM_STATE");
	wmStates[0] = (Atom) Atom.intern(display, "_NET_WM_STATE_ABOVE");
	wmStates[1] = (Atom) Atom.intern(display, "_NET_WM_STATE_BELOW");
	wmStates[2] = (Atom) Atom.intern(display, "_NET_WM_STATE_MODAL");
	wmStates[3] = (Atom) Atom.intern(display,
		"_NET_WM_STATE_SKIP_TASKBAR");

	netallowedAction = (Atom) Atom.intern(display,
		"_NET_WM_ALLOWED_ACTIONS");
	allowedActions[0] = (Atom) Atom.intern(display,
		"_NET_WM_ACTION_CLOSE");
	allowedActions[1] = (Atom) Atom.intern(display,
		"_NET_WM_ACTION_MINIMIZE");
	allowedActions[2] = (Atom) Atom
		.intern(display, "_NET_WM_ACTION_MOVE");
	allowedActions[3] = (Atom) Atom.intern(display,
		"_NET_WM_ACTION_RESIZE");
    }
    
    /**
     * initialize the Window Manager Hints. this initialize all supported features
     * of extended Window Manager Hints.
     * @param root
     * @param checkwin
     */
    static void initWmNETSupport(Display display, Window root[], Window checkwin[]) {
	int netsp[] = new int[BASENR + windowTypes.length + wmStates.length
		+ allowedActions.length];
	int i = 0;
	String wmname = "LG3D";
	netsp[i++] = netSupported.id;
	netsp[i++] = netSupportingWmCheck.id;

	for (int j = 0; j < windowTypes.length; j++) {
	    netsp[i++] = windowTypes[j].id;
	}
	netsp[i++] = netWmName.id;
	netsp[i++] = netWmState.id;
	for (int j = 0; j < wmStates.length; j++) {
	    netsp[i++] = wmStates[j].id;
	}

	netsp[i++] = netallowedAction.id;
	for (int j = 0; j < allowedActions.length; j++) {
	    netsp[i++] = allowedActions[j].id;
	}

	// now set the property
	for (int j = 0; j < root.length; j++) {
	    int chkWin[] = { checkwin[j].id };
	    root[j].change_property(Window.REPLACE, netsp.length, netSupported,
		    Atom.ATOM, 32, netsp, 0, 32);
	    root[j].change_property(Window.REPLACE, 1, netSupportingWmCheck,
		    Atom.WINDOW, 32, chkWin, 0, 32);
	    checkwin[j].change_property(Window.REPLACE, 1,
		    netSupportingWmCheck, Atom.WINDOW, 32, chkWin, 0, 32);
	    checkwin[j].change_property(Window.REPLACE, wmname.length(),
		    netWmName, Atom.STRING, 8, wmname.getBytes(), 0, 8);
	    checkwin[j].set_wm_class_hint("lg3d", "LG3D");
	    checkwin[j].set_wm_name("LG3D");
	}

	logger.info("LG3D WM Hints initilized");
    }

    //////////////////////////////////////////////////////////////
    // EWMH subset (Phase D). The pure decision logic lives in
    // NetWmState; the methods below only translate its enums to
    // atom ids and filter to the atoms advertised in _NET_SUPPORTED.
    //////////////////////////////////////////////////////////////
    /**
     * Sets the default {@code _NET_WM_STATE} for a client according to its
     * window type, using the pure {@link NetWmState} decision layer. Only the
     * states this WM advertises in {@code _NET_SUPPORTED} (see {@link #wmStates})
     * are emitted, so the property stays consistent with the capability list.
     * @param display the X display
     * @param client the client window
     */
    static void setNetWmState(Display display, X11Client client) {
	NetWmState.WindowType type = windowTypeFor(client.getNetWindowType());
	EnumSet<NetWmState.State> state = NetWmState.defaultStateFor(type);

	int temp[] = new int[state.size()];
	int i = 0;
	for (NetWmState.State s : state) {
	    int atomId = ((Atom) Atom.intern(display,
		    NetWmState.stateAtomName(s))).id;
	    if (isAdvertised(wmStates, atomId)) {
		temp[i++] = atomId;
	    }
	}
	int data[] = new int[i];
	System.arraycopy(temp, 0, data, 0, i);
	client.change_property(Window.REPLACE, i, netWmState, Atom.ATOM, 32,
		data, 0, 32);
    }

    /**
     * Sets the {@code _NET_WM_ALLOWED_ACTIONS} property from the client's window
     * type and capabilities, using the pure {@link NetWmState} decision layer.
     * Only the actions this WM advertises in {@code _NET_SUPPORTED} (see
     * {@link #allowedActions}) are emitted.
     * @param display the X display
     * @param client the client window
     */
    static void setNetAllowedActions(Display display, X11Client client) {
	NetWmState.WindowType type = windowTypeFor(client.getNetWindowType());
	EnumSet<NetWmState.State> state = NetWmState.defaultStateFor(type);
	EnumSet<NetWmState.Action> actions = NetWmState.allowedActions(state,
		client.isResizable(), client.isMaximizable(),
		client.isCloseable(), false);

	int temp[] = new int[actions.size()];
	int i = 0;
	for (NetWmState.Action a : actions) {
	    int atomId = ((Atom) Atom.intern(display,
		    NetWmState.actionAtomName(a))).id;
	    if (isAdvertised(allowedActions, atomId)) {
		temp[i++] = atomId;
	    }
	}
	int data[] = new int[i];
	System.arraycopy(temp, 0, data, 0, i);
	client.change_property(Window.REPLACE, i, netallowedAction, Atom.ATOM,
		32, data, 0, 32);
    }

    /**
     * Maps a {@code _NET_WM_WINDOW_TYPE_*} atom id back to the pure
     * {@link NetWmState.WindowType} enum used by the EWMH decision layer.
     * @param typeAtomId the client's window-type atom id
     * @return the matching type, or {@link NetWmState.WindowType#NORMAL} if unknown
     */
    static NetWmState.WindowType windowTypeFor(int typeAtomId) {
	if (windowTypes[2].id == typeAtomId) {
	    return NetWmState.WindowType.DIALOG;
	}
	if (windowTypes[3].id == typeAtomId) {
	    return NetWmState.WindowType.SPLASH;
	}
	if (windowTypes[4].id == typeAtomId) {
	    return NetWmState.WindowType.DESKTOP;
	}
	if (windowTypes[5].id == typeAtomId) {
	    return NetWmState.WindowType.DOCK;
	}
	if (windowTypes[6].id == typeAtomId) {
	    return NetWmState.WindowType.TOOLBAR;
	}
	if (windowTypes[7].id == typeAtomId) {
	    return NetWmState.WindowType.MENU;
	}
	if (windowTypes[8].id == typeAtomId) {
	    return NetWmState.WindowType.UTILITY;
	}
	return NetWmState.WindowType.NORMAL;
    }

    /**
     * True if {@code atomId} is one of the atoms this WM advertised in
     * {@code _NET_SUPPORTED} (the given static table).
     * @param table the advertised atom table
     * @param atomId the atom id to look for
     * @return whether the atom is advertised
     */
    private static boolean isAdvertised(Atom table[], int atomId) {
	for (int j = 0; j < table.length; j++) {
	    if (table[j] != null && table[j].id == atomId) {
		return true;
	    }
	}
	return false;
    }

    /**
     * save the window type.
     * @param client
     * @param type
     */
    static void setNetWindowType(Display display, X11Client client, Atom type) {
	client.setNetWindowType(type);
    }    

    /**
     * get current window state.
     * @param win
     */
    Atom[] getWmState(Display display, Window win) {
	ArrayList<Atom> res = new ArrayList<Atom>();
	Window.PropertyReply rep = win.property(false, netWmState, Atom.ATOM,
		0, 5);
	Enum enm = rep.items();
	while (enm.more()) {
	    Atom atom = (Atom) Atom.intern(display, enm.next_integer());
	    res.add(atom);
	    logger.fine("WM State: " + win + " " + atom);
	}
	if (res.size() > 0) {
	    return res.toArray(new Atom[res.size()]);
	}
	return null;
    }

    /**
     * get window type (_NET_WM_WINDOW_TYPE)
     * @param win
     * @return window type ATOM
     */
    static Atom getNetWindowType(Display display, Window win) {
	Atom res = null;
	Window.PropertyReply rep = win.property(false, windowTypes[0],
		Atom.ATOM, 0, 1);

	if (rep.length() == 1) {
	    Enum enm = rep.items();
	    res = (Atom) Atom.intern(display, enm.next_integer(), true);
	} else {
	    /** if not set by application assume NORMAL TYPE */
	    res = (Atom) Atom.intern(display, "_NET_WM_WINDOW_TYPE_NORMAL");
	}
	logger.fine("WM Type: " + win + " " + res);
	return res;
    }

    /**
     * check if Window is supported by our WM. supported windows are:
     * _NET_WM_WINDOW_TYPE_NORMAL, _NET_WM_WINDOW_TYPE_DIALOG, 
     * _NET_WM_WINDOW_TYPE_SPLASH, _NET_WM_WINDOW_TYPE_UTILITY
     * @param client
     * @return
     */
    static boolean isSupportedWinType(Display display, X11Client client) {
	boolean res = false;
	int type = client.getNetWindowType();
	if (windowTypes[1].id == type) { // _NET_WM_WINDOW_TYPE_NORMAL
	    return true;
	}
	if (windowTypes[2].id == type) { // _NET_WM_WINDOW_TYPE_DIALOG
	    return true;
	}
	if (windowTypes[3].id == type) { // _NET_WM_WINDOW_TYPE_SPLASH
	    return true;
	}
	if (windowTypes[6].id == type) { // _NET_WM_WINDOW_TYPE_TOOLBAR
	    return true;
	}
	if (windowTypes[7].id == type) { // _NET_WM_WINDOW_TYPE_MENU
	    return true;
	}
	if (windowTypes[8].id == type) { // _NET_WM_WINDOW_TYPE_UTILITY
	    return true;
	}

	return res;
    }

    /**
     * set the window features, such as closeable, maximzable. this give 
     * control of how 3D Frame is drawn by LookANDFeel class. 
     * @param client
     */
    static void setWindowFeatures(Display display, X11Client client) {
	int type = client.getNetWindowType();
	if (windowTypes[1].id == type) { // _NET_WM_WINDOW_TYPE_NORMAL
	    client.setNormalWindow(true);
	    Window.WMSizeHints sizeHints = client.wm_normal_hints();
	    
	    if (sizeHints != null &&
		(sizeHints.min_width() == sizeHints.max_width()) &&
		(sizeHints.min_height() == sizeHints.max_height())) {
		client.setResizable(false);
		client.setMaximizable(false);
	    }	    
	    return;
	}
	if (windowTypes[2].id == type) { // _NET_WM_WINDOW_TYPE_DIALOG
	    client.setNormalWindow(false);
	    client.setCloseable(true);
	    Window.WMSizeHints sizeHints = client.wm_normal_hints();
	    if ((sizeHints.min_width() == sizeHints.max_width())
		    && (sizeHints.min_height() == sizeHints.max_height())) {
		client.setResizable(false);
	    }
	    client.setMinimizable(false);
	    client.setMaximizable(false);
	    return;
	}
	if (windowTypes[3].id == type) { // _NET_WM_WINDOW_TYPE_SPLASH
	    client.setNormalWindow(false);
	    client.setDecorated(false);
	    return;
	}
	if (windowTypes[6].id == type) { // _NET_WM_WINDOW_TYPE_TOOLBAR
	    client.setNormalWindow(false);
	    client.setCloseable(true);
	    client.setMinimizable(false);
	    client.setMaximizable(false);
	    return;
	}
	if (windowTypes[7].id == type) { // _NET_WM_WINDOW_TYPE_MENU
	    client.setNormalWindow(false);
	    client.setCloseable(true);
	    client.setMinimizable(false);
	    client.setMaximizable(false);
	    return;
	}
	if (windowTypes[8].id == type) { // _NET_WM_WINDOW_TYPE_UTILITY
	    client.setNormalWindow(false);
	    client.setCloseable(true);
	    client.setMinimizable(false);
	    client.setMaximizable(false);
	    return;
	}
    }
}
