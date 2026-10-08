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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reader mode: a small readability-style extractor plus the clean, ad-free page
 * it renders. {@link #EXTRACT_JS} runs inside the live page (WebKit) and returns a
 * JSON blob {@code {title, byline, paragraphs[]}}; {@link #parse} turns that JSON
 * into a {@link ReaderArticle} and {@link #renderHtml} lays it out as a styled,
 * self-contained document.
 *
 * <p>Only the DOM heuristic itself needs WebKit; the JSON parsing and the HTML
 * rendering (including escaping of every interpolated value) are plain Java and
 * unit-tested headlessly. Uses the Jackson that {@link BrowserStore} already pulls
 * in, so no new dependency.</p>
 */
public final class ReaderExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(ReaderExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The content type the reader page is loaded with. */
    public static final String CONTENT_TYPE = "text/html";

    /**
     * The extraction heuristic: prefer the {@code <article>}/{@code <main>}/densest
     * {@code <div>} container, take its substantive {@code <p>} text, plus the
     * page title and an author byline. Returns a JSON string.
     */
    public static final String EXTRACT_JS =
            "(function(){"
            + "function txt(e){return e?(e.textContent||'').replace(/\\s+/g,' ').trim():'';}"
            + "var title=txt(document.querySelector('h1'))||document.title||'';"
            + "var byline='';var a=document.querySelector('meta[name=\"author\"]');"
            + "if(a&&a.content){byline=a.content;}"
            + "if(!byline){byline=txt(document.querySelector('[rel=author],.byline,.author'));}"
            + "var cands=document.querySelectorAll('article,main,[role=main],div,section');"
            + "var best=null,bestScore=0;"
            + "for(var i=0;i<cands.length;i++){var c=cands[i],ps=c.querySelectorAll('p');"
            + "if(ps.length<2){continue;}var score=0;"
            + "for(var j=0;j<ps.length;j++){score+=txt(ps[j]).length;}"
            + "if(score>bestScore){bestScore=score;best=c;}}"
            + "var scope=best||document.body;"
            + "var nodes=scope?scope.querySelectorAll('p'):[];var paras=[];"
            + "for(var k=0;k<nodes.length;k++){var t=txt(nodes[k]);if(t.length>40){paras.push(t);}}"
            + "return JSON.stringify({title:title,byline:byline,paragraphs:paras});})()";

    private ReaderExtractor() {
        // no instances
    }

    /**
     * Parses the extractor's JSON result into a {@link ReaderArticle}. Any null,
     * blank or malformed input yields an empty article rather than throwing.
     *
     * @param json the raw JSON string returned by {@link #EXTRACT_JS} (may be null)
     * @return the article, never null
     */
    public static ReaderArticle parse(String json) {
        if (json == null || json.isBlank()) {
            return new ReaderArticle("", "", null);
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return new ReaderArticle("", "", null);
            }
            String title = text(root.get("title"));
            String byline = text(root.get("byline"));
            List<String> paragraphs = new ArrayList<>();
            JsonNode arr = root.get("paragraphs");
            if (arr != null && arr.isArray()) {
                for (JsonNode node : arr) {
                    String p = text(node);
                    if (!p.isEmpty()) {
                        paragraphs.add(p);
                    }
                }
            }
            return new ReaderArticle(title, byline, paragraphs);
        } catch (RuntimeException | java.io.IOException e) {
            LOG.warn("Could not parse reader JSON; showing an empty article", e);
            return new ReaderArticle("", "", null);
        }
    }

    /**
     * Renders an article as a clean, styled, self-contained HTML page. When the
     * article is empty, a friendly "couldn't extract" page links back to the
     * source. Every interpolated value is escaped via {@link Html#escape}.
     *
     * @param article   the extracted article (null is treated as empty)
     * @param sourceUrl the original page URL, for the footer link (may be null)
     * @return the complete HTML page, never null
     */
    public static String renderHtml(ReaderArticle article, String sourceUrl) {
        ReaderArticle a = (article == null) ? new ReaderArticle("", "", null) : article;
        String src = (sourceUrl == null) ? "" : sourceUrl.trim();
        String escSrc = Html.escape(src);

        StringBuilder sb = new StringBuilder(1024 + a.getParagraphs().size() * 128);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
          .append("<title>").append(Html.escape(fallbackTitle(a, src))).append("</title>")
          .append("<style>")
          .append("body{margin:0;background:#fbfbfd;color:#1c1c1e;")
          .append("font-family:Georgia,'Times New Roman',serif;line-height:1.7;}")
          .append(".wrap{max-width:720px;margin:0 auto;padding:40px 24px 80px;}")
          .append("h1{font-size:32px;line-height:1.25;margin:0 0 8px;}")
          .append(".byline{color:#86868b;font-size:14px;font-family:-apple-system,Segoe UI,")
          .append("Roboto,Helvetica,Arial,sans-serif;margin:0 0 28px;}")
          .append("p{font-size:19px;margin:0 0 20px;}")
          .append(".empty{color:#555;font-size:17px;}")
          .append("footer{margin-top:40px;padding-top:16px;border-top:1px solid #e5e5ea;")
          .append("font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;")
          .append("font-size:13px;color:#86868b;}")
          .append("footer a{color:#2563eb;text-decoration:none;word-break:break-all;}")
          .append("</style></head><body><div class=\"wrap\">");

        if (a.isEmpty()) {
            sb.append("<p class=\"empty\">Reader mode could not find the main article on "
                    + "this page. Some pages are mostly interactive content with little "
                    + "standalone text.</p>");
        } else {
            sb.append("<h1>").append(Html.escape(fallbackTitle(a, src))).append("</h1>");
            if (!a.getByline().isEmpty()) {
                sb.append("<div class=\"byline\">").append(Html.escape(a.getByline())).append("</div>");
            }
            for (String p : a.getParagraphs()) {
                sb.append("<p>").append(Html.escape(p)).append("</p>");
            }
        }

        if (!src.isEmpty()) {
            sb.append("<footer>Source: <a href=\"").append(escSrc).append("\">")
              .append(escSrc).append("</a></footer>");
        }
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static String fallbackTitle(ReaderArticle a, String src) {
        if (!a.getTitle().isEmpty()) {
            return a.getTitle();
        }
        String host = UrlNormalizer.hostOf(src);
        return host.isEmpty() ? "Reader" : host;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isTextual() ? node.asText().trim() : node.toString().trim();
    }
}
