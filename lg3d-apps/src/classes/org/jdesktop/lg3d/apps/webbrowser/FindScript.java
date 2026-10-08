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
package org.jdesktop.lg3d.apps.webbrowser;

/**
 * Builds the JavaScript that powers the inline find bar (highlight-all with a
 * live "n of m" match count, next/prev cycling and clear) and parses the count
 * the script returns. Replaces the previous naive {@code window.find} call, which
 * neither highlighted every match nor reported how many there were.
 *
 * <p>The query is embedded through {@link #escapeJs} so it can never break out of
 * its JS string literal (script injection via the find field). The scripts
 * themselves are plain strings, and {@link #parseCounts} turns the script's
 * {@code "active:total"} result into ints, so all of it is unit-tested headlessly
 * even though the DOM walk only runs inside WebKit.</p>
 */
public final class FindScript {

    /** Marker class applied to every highlighted match. */
    static final String MARK_CLASS = "lg3d-find";
    /** Extra marker class applied to the currently-focused match. */
    static final String ACTIVE_CLASS = "lg3d-find-active";

    private FindScript() {
        // no instances
    }

    /**
     * Escapes {@code text} for safe inclusion inside a single-quoted JavaScript
     * string literal. {@code null} becomes the empty string.
     *
     * @param text the raw query (may be null)
     * @return the JS-escaped text, never null
     */
    public static String escapeJs(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\'':
                    sb.append("\\'");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '<':
                    sb.append("\\u003c");
                    break;
                case '>':
                    sb.append("\\u003e");
                    break;
                case '&':
                    sb.append("\\u0026");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * The highlight-all script for {@code query}. It clears any previous
     * highlights, wraps every case-insensitive match in a {@code <mark>}, scrolls
     * to the first one and returns {@code "active:total"} (active is 1-based, or
     * 0 when there are none). A blank query clears and returns {@code "0:0"}.
     *
     * @param query the search text (may be null/blank)
     * @return the JavaScript to run on the engine, never null
     */
    public static String highlightScript(String query) {
        String q = escapeJs(query);
        return "(function(){"
                + "lg3dFindClear();"
                + "var st=window.__lg3dFind={nodes:[],index:-1,total:0,query:'" + q + "'};"
                + "lg3dFindStyle();"
                + "if(!st.query){return '0:0';}"
                + "var ql=st.query.toLowerCase();"
                + "var root=document.body||document.documentElement;if(!root){return '0:0';}"
                + "var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null);"
                + "var targets=[],n;while((n=w.nextNode())){"
                + "if(n.nodeValue&&n.nodeValue.toLowerCase().indexOf(ql)>=0"
                + "&&!(n.parentNode&&n.parentNode.nodeName==='MARK')){targets.push(n);}}"
                + "for(var t=0;t<targets.length;t++){var node=targets[t],text=node.nodeValue,"
                + "lower=text.toLowerCase(),from=0,found,frag=document.createDocumentFragment();"
                + "while((found=lower.indexOf(ql,from))>=0){"
                + "if(found>from){frag.appendChild(document.createTextNode(text.substring(from,found)));}"
                + "var m=document.createElement('mark');m.className='" + MARK_CLASS + "';"
                + "m.textContent=text.substring(found,found+st.query.length);frag.appendChild(m);"
                + "st.nodes.push(m);from=found+st.query.length;if(from>lower.length){break;}}"
                + "if(from<text.length){frag.appendChild(document.createTextNode(text.substring(from)));}"
                + "if(node.parentNode){node.parentNode.replaceChild(frag,node);}}"
                + "st.total=st.nodes.length;"
                + "if(st.total>0){st.index=0;lg3dFindActivate(st);return '1:'+st.total;}"
                + "return '0:0';})()";
    }

    /** The next-match script: advances the active highlight (wrapping). */
    public static String nextScript() {
        return "(function(){var st=window.__lg3dFind;"
                + "if(!st||!st.nodes||st.nodes.length===0){return '0:0';}"
                + "st.index=(st.index+1)%st.nodes.length;lg3dFindActivate(st);"
                + "return (st.index+1)+':'+st.nodes.length;})()";
    }

    /** The previous-match script: moves the active highlight back (wrapping). */
    public static String prevScript() {
        return "(function(){var st=window.__lg3dFind;"
                + "if(!st||!st.nodes||st.nodes.length===0){return '0:0';}"
                + "st.index=(st.index-1+st.nodes.length)%st.nodes.length;lg3dFindActivate(st);"
                + "return (st.index+1)+':'+st.nodes.length;})()";
    }

    /** The clear script: removes every highlight and forgets the state. */
    public static String clearScript() {
        return "(function(){lg3dFindClear();return '0:0';})()";
    }

    /**
     * Defines the two helpers ({@code lg3dFindStyle}, {@code lg3dFindClear},
     * {@code lg3dFindActivate}) once, so the per-keystroke scripts stay small.
     * Injected before the first find of a page.
     *
     * @return the JavaScript that installs the helpers, never null
     */
    public static String installHelpersScript() {
        return "(function(){if(window.__lg3dFindHelpers){return;}"
                + "window.__lg3dFindHelpers=true;"
                + "window.lg3dFindStyle=function(){if(document.getElementById('lg3d-find-css')){return;}"
                + "var s=document.createElement('style');s.id='lg3d-find-css';"
                + "s.textContent='." + MARK_CLASS + "{background:#ffe08a;color:inherit;}"
                + "." + ACTIVE_CLASS + "{background:#ff9632;outline:1px solid #e8590c;}';"
                + "(document.head||document.documentElement).appendChild(s);};"
                + "window.lg3dFindActivate=function(st){for(var i=0;i<st.nodes.length;i++){"
                + "var m=st.nodes[i];if(!m){continue;}"
                + "m.className=(i===st.index)?('" + MARK_CLASS + " " + ACTIVE_CLASS + "'):('" + MARK_CLASS + "');}"
                + "var a=st.nodes[st.index];if(a&&a.scrollIntoView){a.scrollIntoView({block:'center'});}};"
                + "window.lg3dFindClear=function(){var st=window.__lg3dFind;"
                + "if(st&&st.nodes){for(var i=0;i<st.nodes.length;i++){var m=st.nodes[i];"
                + "if(m&&m.parentNode){m.parentNode.replaceChild(document.createTextNode(m.textContent),m);}}"
                + "if(document.body){document.body.normalize();}}"
                + "window.__lg3dFind=null;};})()";
    }

    /**
     * Parses a script result of the form {@code "active:total"} into a two-element
     * int array {@code {active, total}}. Tolerates {@code null}, an
     * already-numeric result, or a malformed string (yielding {@code {0,0}}).
     *
     * @param result the value returned by {@code executeScript} (may be null)
     * @return {@code {active, total}}, never null, both non-negative
     */
    public static int[] parseCounts(Object result) {
        if (result == null) {
            return new int[] {0, 0};
        }
        if (result instanceof Number) {
            int total = Math.max(0, ((Number) result).intValue());
            return new int[] {total > 0 ? 1 : 0, total};
        }
        String s = result.toString().trim();
        int colon = s.indexOf(':');
        if (colon < 0) {
            return new int[] {0, nonNeg(s)};
        }
        return new int[] {nonNeg(s.substring(0, colon)), nonNeg(s.substring(colon + 1))};
    }

    private static int nonNeg(String s) {
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
