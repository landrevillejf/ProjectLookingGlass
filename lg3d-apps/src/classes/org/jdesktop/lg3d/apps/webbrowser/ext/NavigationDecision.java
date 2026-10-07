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
package org.jdesktop.lg3d.apps.webbrowser.ext;

/**
 * An extension's verdict on a {@link NavigationRequest}: proceed, block, or
 * proceed against a different URL. Created through the static factories.
 */
public final class NavigationDecision {

    /** The three possible verdicts. */
    public enum Action { ALLOW, BLOCK, REDIRECT }

    private final Action action;
    private final String targetUrl;

    private NavigationDecision(Action action, String targetUrl) {
        this.action = action;
        this.targetUrl = targetUrl;
    }

    /** Proceed with the requested URL unchanged. */
    public static NavigationDecision allow() {
        return new NavigationDecision(Action.ALLOW, null);
    }

    /** Cancel the navigation entirely. */
    public static NavigationDecision block() {
        return new NavigationDecision(Action.BLOCK, null);
    }

    /** Load {@code url} instead of the requested one. */
    public static NavigationDecision redirect(String url) {
        return new NavigationDecision(Action.REDIRECT, (url == null) ? "" : url);
    }

    public Action getAction() { return action; }

    /** @return the redirect target, or "" when the action is not REDIRECT. */
    public String getTargetUrl() { return (targetUrl == null) ? "" : targetUrl; }

    public boolean isAllow() { return action == Action.ALLOW; }
    public boolean isBlock() { return action == Action.BLOCK; }
    public boolean isRedirect() { return action == Action.REDIRECT; }

    @Override
    public String toString() {
        return "NavigationDecision[" + action + (isRedirect() ? " -> " + targetUrl : "") + "]";
    }
}
