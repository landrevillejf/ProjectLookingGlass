/**
 * Project Looking Glass
 *
 * $RCSfile: X11WindowAssociator.java,v $
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
 * $Revision: 1.12 $
 * $Date: 2007-04-10 23:25:10 $
 * $State: Exp $
 */

package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.logging.Logger;

import org.jdesktop.lg3d.displayserver.nativewindow.NativeWindow3D;
import org.jdesktop.lg3d.wg.event.LgEventConnector;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.utils.eventadapter.MouseEnteredEventAdapter;
import org.jdesktop.lg3d.utils.action.ActionBoolean;
import org.jdesktop.lg3d.utils.prefs.LgPreferencesHelper;


/**
 *
 */
final class X11WindowAssociator {
    private static final Logger logger = Logger.getLogger("lg.x11");
    
    private ArrayList<WindowAssociationRuleEntry> 
        windowAssociationRule = new ArrayList<WindowAssociationRuleEntry>();
    
    private WindowAssociationTarget focusedWindow = null;
    
    /**
     * Production constructor: installs the focus-tracking listener and loads the
     * preference-configured association rules.
     */
    X11WindowAssociator() {
        this(true);
    }

    /**
     * Test seam. {@code new X11WindowAssociator(false)} builds an associator with
     * an empty rule list and no {@link LgEventConnector} listener / preference
     * load, so the orchestration ({@link #getAssociatedWindow},
     * {@link #removeAllRules} and rule matching) can be exercised headless against
     * {@link WindowAssociationTarget} fakes: a real {@link X11Client} extends
     * {@code gnu.x11.Window}, needs a live {@code Display}, and cannot even be
     * class-loaded in a test JVM without running its own static
     * {@code new X11WindowAssociator()}. Production always uses the no-arg
     * constructor, so runtime behaviour is unchanged.
     *
     * @param installWiring when {@code true}, register focus tracking and load the
     *                      default rules (production); when {@code false}, skip
     *                      both (headless tests).
     */
    X11WindowAssociator(boolean installWiring) {
        if (installWiring) {
            installFocusTracking();
            loadDefaultRules();
        }
    }

    /**
     * Keeps track of the X window touched last via the lg3d mouse-entered event
     * stream. Extracted verbatim from the constructor so the wiring can be
     * skipped in headless tests; behaviour is unchanged.
     */
    private void installFocusTracking() {
        LgEventConnector.getLgEventConnector().addListener(NativeWindow3D.class,
            new MouseEnteredEventAdapter(
                new ActionBoolean() {
                    public void performAction(LgEventSource source, boolean enter) {
                        if (enter) {
                            NativeWindow3D nw = (NativeWindow3D)source;
                            setFocusedWindow(
                                (WindowAssociationTarget)nw.getNativeWindowControl());
                        } else
                            setFocusedWindow(null);
                    }
                }
            ));
    }

    /**
     * Sets the currently focused window. Package-private so headless tests can
     * drive focus directly; production sets it from the mouse-entered listener in
     * {@link #installFocusTracking()}.
     *
     * @param focusedWindow the newly focused window, or {@code null} on exit
     */
    void setFocusedWindow(WindowAssociationTarget focusedWindow) {
        this.focusedWindow = focusedWindow;
    }
    
    private void loadDefaultRules() {
        logger.info("Loadinging window association rules...");
        Preferences prefs = LgPreferencesHelper.userNodeForPackage(getClass()).node("win-assoc");
        
        String[] rules = null;
        try {
            rules = prefs.childrenNames();
        } catch (BackingStoreException bse) {
            logger.warning("Failed to obtain window association configuration: " + bse);
            return;
        }
        if (rules.length == 0) {
            logger.info("No window association rule provided");
            return;
        }
        
        for (String rule : rules) {
            logger.info("Loading window association rule: " + rule);
            Preferences ruleRrefs = prefs.node(rule);

            String parentResCls  = ruleRrefs.get("parent-res-cls", null);
            String parentResName = ruleRrefs.get("parent-res-name", null);
            String parentResTitlePattern = ruleRrefs.get("parent-title-pattern", null);
            
            String childResCls   = ruleRrefs.get("child-res-cls", null);
            String childResName  = ruleRrefs.get("child-res-name", null);
            String childResTitlePattern = ruleRrefs.get("child-title-pattern", null);
            
            if (parentResCls == null || parentResName == null || parentResTitlePattern == null
                    || childResCls == null || childResName == null || childResTitlePattern == null) 
            {
                logger.warning("Rule for window association rule is broken: " + rule);
            } else {
                addRule(parentResCls, parentResName, parentResTitlePattern,
                        childResCls, childResName, childResTitlePattern);
            }
        }
    }
    
    void addRule(WindowAssociationTarget targetWindow,
            String subWinResCls, String subWinResName, String subWinTitlePattern) 
    {
        windowAssociationRule.add(
            new WindowAssociationRuleEntry(
                targetWindow, subWinResCls, subWinResName, subWinTitlePattern));
    }
    
    void addRule(String targetWinResCls, String targetWinResName, String targetWinTitlePattern, 
            String subWinResCls, String subWinResName, String subWinTitlePattern) 
    {
        windowAssociationRule.add(
            new WindowAssociationRuleEntry(
                targetWinResCls, targetWinResName, targetWinTitlePattern, 
                subWinResCls, subWinResName, subWinTitlePattern));
    }
    
    WindowAssociationTarget getAssociatedWindow(WindowAssociationTarget x11Client) {
        if (focusedWindow == null) {
            return null;
        }
        for (WindowAssociationRuleEntry entry : windowAssociationRule) {
            WindowAssociationTarget ret = entry.getTargetWindow(x11Client, focusedWindow);
            if (ret != null) {
                // A one-time rule is retired as soon as it fires. Removing here
                // is safe despite the for-each: the method returns immediately,
                // so the fail-fast iterator is never advanced again.
                if (entry.isOneTime()) {
                    windowAssociationRule.remove(entry);
                }
                return ret;
            }
        }
        return null;
    }

    /**
     * Remove all the rules associated with this x11Client
     */
    void removeAllRules(WindowAssociationTarget x11Client) {
        if (x11Client==null)
            return;
        // removeIf iterates safely; removing from the list directly inside a
        // for-each loop would throw ConcurrentModificationException.
        windowAssociationRule.removeIf(rule -> rule.getTargetWindow()==x11Client);
        
        if (focusedWindow==x11Client)
            focusedWindow = null;
    }

    /**
     * Pure window-association rule match, extracted verbatim from the inlined
     * checks in {@link WindowAssociationRuleEntry#getTargetWindow} so the
     * matcher can be unit-tested headless: an {@link X11Client} extends
     * {@code gnu.x11.Window} and needs a live {@code Display}, so it cannot be
     * constructed in a test JVM. A clause matches when every non-null criterion
     * equals the candidate's value and the (already compiled) title pattern, if
     * any, fully matches the candidate title. Both the sub-window half and the
     * focused-window half of a rule share this shape, so both delegate here.
     *
     * @param ruleCls   required WM_CLASS res_class, or null to ignore
     * @param ruleName  required WM_CLASS res_name, or null to ignore
     * @param ruleTitle compiled title regex, or null to ignore
     * @param cls       candidate res_class (may be null)
     * @param name      candidate res_name (may be null)
     * @param title     candidate window title
     */
    static boolean matches(String ruleCls, String ruleName, Pattern ruleTitle,
            String cls, String name, String title) {
        if (ruleCls != null && !ruleCls.equals(cls)) {
            return false;
        }
        if (ruleName != null && !ruleName.equals(name)) {
            return false;
        }
        if (ruleTitle != null && !ruleTitle.matcher(title).matches()) {
            return false;
        }
        return true;
    }

    private static class WindowAssociationRuleEntry {
        private WindowAssociationTarget targetWindow;
        private String subWinResCls;
        private String subWinResName;
        private Pattern targetWinTitlePattern;
        private String targetWinResCls;
        private String targetWinResName;
        private Pattern subWinTitlePattern;
        
        WindowAssociationRuleEntry(WindowAssociationTarget targetWindow,
                String subWinResCls, String subWinResName, String subWinTitlePattern) 
        {
            this.targetWindow = targetWindow;
            
            this.subWinResCls = subWinResCls;
            this.subWinResName = subWinResName;
            if (subWinTitlePattern != null) {
                try {
                    this.subWinTitlePattern = Pattern.compile(subWinTitlePattern);
                } catch (Exception ex) {
                    logger.warning("Error found in pattern: " + ex);
                }
            }
        }
        
        WindowAssociationRuleEntry(
                String targetWinResCls, String targetWinResName, String targetWinTitlePattern, 
                String subWinResCls, String subWinResName, String subWinTitlePattern) 
        {
            this.targetWinResCls = targetWinResCls;
            this.targetWinResName = targetWinResName;
            if (targetWinTitlePattern != null) {
                try {
                    this.targetWinTitlePattern = Pattern.compile(targetWinTitlePattern);
                } catch (Exception ex) {
                    logger.warning("Error found in pattern: " + ex);
                }
            }
            
            this.subWinResCls = subWinResCls;
            this.subWinResName = subWinResName;
            if (subWinTitlePattern != null) {
                try {
                    this.subWinTitlePattern = Pattern.compile(subWinTitlePattern);
                } catch (Exception ex) {
                    logger.warning("Error found in pattern: " + ex);
                }
            }
        }
        
        WindowAssociationTarget getTargetWindow(WindowAssociationTarget subWinCandidate,
                WindowAssociationTarget focusedWindow) {
            String cls = subWinCandidate.getResClass();
            String name = subWinCandidate.getResName();
            String title = subWinCandidate.getName();

            if (!matches(subWinResCls, subWinResName, subWinTitlePattern, cls, name, title)) {
                return null;
            }
            
            if (targetWindow != null) {
                return targetWindow;
            }
            
            String fCls = focusedWindow.getResClass();
            String fName = focusedWindow.getResName();
            String fTitle = focusedWindow.getName();
            
            if (!matches(targetWinResCls, targetWinResName, targetWinTitlePattern, fCls, fName, fTitle)) {
                return null;
            }
            return focusedWindow;
        }
        
        boolean isOneTime() {
            return (targetWindow != null);
        }
        
        WindowAssociationTarget getTargetWindow() {
            return targetWindow;
        }
    }
}
