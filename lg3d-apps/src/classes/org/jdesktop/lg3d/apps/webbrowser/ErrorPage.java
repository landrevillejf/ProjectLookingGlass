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

import java.util.List;

/**
 * Renders a self-contained, styled HTML error page for a {@link LoadFailure},
 * so a failed navigation shows an honest, actionable card (what went wrong,
 * why, a "Try again" button when retrying makes sense, and recovery tips)
 * instead of a blank page or a bare status-bar string.
 *
 * <p>The page is loaded with {@code WebEngine.loadContent(html, "text/html")},
 * which gives it no base URL, so the "Try again" button is a plain anchor to the
 * original (escaped) address: clicking it navigates the engine back to the real
 * URL through the normal path. Every interpolated value is passed through
 * {@link Html#escape}, so a hostile URL or message can never inject markup or
 * script into the page we build.</p>
 *
 * <p>Free of AWT/JavaFX so the markup and escaping are unit-tested headlessly.</p>
 */
public final class ErrorPage {

    /** The content type the generated page is loaded with. */
    public static final String CONTENT_TYPE = "text/html";

    private ErrorPage() {
        // no instances
    }

    /**
     * Builds the full HTML document for {@code failure}.
     *
     * @param failure the classified failure (null renders a generic unknown error)
     * @return a complete, escaped, styled HTML page, never null
     */
    public static String html(LoadFailure failure) {
        LoadFailure f = (failure == null)
                ? LoadFailure.of(LoadFailure.Reason.UNKNOWN, "", null)
                : failure;
        String title = Html.escape(f.getTitle());
        String explanation = Html.escape(f.getExplanation());
        String url = Html.escape(f.getUrl());
        String detail = Html.escape(f.getDetail());

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
          .append("<title>").append(title).append("</title>")
          .append("<style>")
          .append("body{margin:0;background:#f4f5f7;color:#1f2328;")
          .append("font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;}")
          .append(".wrap{max-width:640px;margin:12vh auto;padding:0 24px;}")
          .append(".card{background:#fff;border:1px solid #e1e4e8;border-radius:12px;")
          .append("padding:28px 32px;box-shadow:0 1px 3px rgba(0,0,0,.08);}")
          .append(".icon{font-size:40px;line-height:1;margin-bottom:8px;}")
          .append("h1{font-size:22px;margin:0 0 6px;}")
          .append("p.lede{color:#444;margin:0 0 14px;font-size:15px;}")
          .append(".url{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;")
          .append("color:#57606a;word-break:break-all;background:#f6f8fa;")
          .append("border:1px solid #eaeef2;border-radius:6px;padding:8px 10px;margin:0 0 14px;}")
          .append(".detail{font-size:12px;color:#8b949e;margin:0 0 16px;}")
          .append("ul{margin:0 0 18px;padding-left:20px;color:#444;font-size:13px;}")
          .append("li{margin:3px 0;}")
          .append("a.btn{display:inline-block;background:#2563eb;color:#fff;text-decoration:none;")
          .append("font-size:14px;font-weight:600;padding:9px 18px;border-radius:8px;}")
          .append("a.btn:hover{background:#1d4ed8;}")
          .append("</style></head><body><div class=\"wrap\"><div class=\"card\">");

        sb.append("<div class=\"icon\">").append(iconFor(f.getReason())).append("</div>");
        sb.append("<h1>").append(title).append("</h1>");
        sb.append("<p class=\"lede\">").append(explanation).append("</p>");
        if (!f.getUrl().isEmpty()) {
            sb.append("<div class=\"url\">").append(url).append("</div>");
        }
        if (!f.getDetail().isEmpty()) {
            sb.append("<p class=\"detail\">").append(detail).append("</p>");
        }
        List<String> tips = f.getTips();
        if (tips != null && !tips.isEmpty()) {
            sb.append("<ul>");
            for (String tip : tips) {
                sb.append("<li>").append(Html.escape(tip)).append("</li>");
            }
            sb.append("</ul>");
        }
        if (f.isRetryable() && !f.getUrl().isEmpty()) {
            sb.append("<a class=\"btn\" href=\"").append(url).append("\">Try again</a>");
        }

        sb.append("</div></div></body></html>");
        return sb.toString();
    }

    private static String iconFor(LoadFailure.Reason reason) {
        switch (reason) {
            case DNS_FAILURE:
            case CONNECTION_REFUSED:
                return "&#128268;";   // plug/disconnect
            case TIMEOUT:
                return "&#8987;";     // hourglass
            case TLS_ERROR:
                return "&#128274;";   // lock
            case HTTP_CLIENT_ERROR:
            case HTTP_SERVER_ERROR:
                return "&#9888;";     // warning
            case BLOCKED:
                return "&#128683;";   // no entry
            default:
                return "&#9888;";     // warning
        }
    }
}
