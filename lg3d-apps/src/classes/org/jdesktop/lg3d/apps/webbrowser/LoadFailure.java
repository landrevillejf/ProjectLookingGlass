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

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * A classified page-load (or download) failure: a {@link Reason} plus the URL
 * that failed and an optional specific detail (an exception message or an HTTP
 * status line). Each reason carries a human title, a one-line explanation, a
 * "should we offer Retry?" flag and a few recovery tips, so the UI can present
 * an honest, actionable error instead of a blank page or a bare status string.
 *
 * <p>This class is deliberately free of AWT/JavaFX so the classification logic
 * can be unit-tested headlessly. {@link #classify(Throwable, String)} walks the
 * whole cause chain and, failing a typed match, falls back to matching WebKit's
 * message text, so a failure surfaced only as a string is still diagnosed.</p>
 */
public final class LoadFailure {

    /** The kind of failure, with its presentation text and retry policy. */
    public enum Reason {
        /** The host name could not be resolved. */
        DNS_FAILURE("Site not found",
                "The address could not be resolved to a server (DNS failure).",
                true,
                "Check the address for typos.",
                "Make sure you are connected to the network.",
                "If your network is fine, the site's DNS may be temporarily down."),
        /** The server actively refused (or could not be reached on) the port. */
        CONNECTION_REFUSED("Connection refused",
                "The server refused the connection or is not accepting requests.",
                true,
                "The site may be down; try again shortly.",
                "A firewall may be blocking the connection."),
        /** The navigation exceeded the configured timeout. */
        TIMEOUT("The page took too long",
                "The site did not respond within the allowed time.",
                true,
                "Try again - the site or your connection may have been slow.",
                "You can raise the page-load timeout in Settings."),
        /** A TLS/certificate problem prevented a secure connection. */
        TLS_ERROR("Secure connection failed",
                "The site's security certificate is invalid or the TLS handshake failed.",
                false,
                "This may be a misconfigured or spoofed site - proceed with caution.",
                "Check your system clock, since a wrong date breaks certificate validation."),
        /** The server returned a 4xx status. */
        HTTP_CLIENT_ERROR("Page not available",
                "The server reported a client error (4xx) for this address.",
                false,
                "The page may have moved or been removed.",
                "Check the address, or return to the site's home page."),
        /** The server returned a 5xx status. */
        HTTP_SERVER_ERROR("Server error",
                "The server reported an internal error (5xx).",
                true,
                "This is a problem on the site's side; try again shortly."),
        /** The load was cancelled (by Stop or a new navigation). */
        CANCELLED("Loading cancelled",
                "The page load was cancelled before it finished.",
                false,
                "Reload the page if you want to continue."),
        /** An installed extension blocked the navigation. */
        BLOCKED("Blocked by an extension",
                "An installed extension blocked this page.",
                false,
                "Review your extensions if you expected this page to load."),
        /** The address was not a usable URL. */
        MALFORMED_URL("Invalid address",
                "The address could not be understood as a URL.",
                false,
                "Check the address for typos or illegal characters."),
        /** No specific cause could be determined. */
        UNKNOWN("This page isn't working",
                "The page could not be loaded.",
                true,
                "Try again, or search for the page instead.");

        private final String title;
        private final String explanation;
        private final boolean retryable;
        private final List<String> tips;

        Reason(String title, String explanation, boolean retryable, String... tips) {
            this.title = title;
            this.explanation = explanation;
            this.retryable = retryable;
            this.tips = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(tips)));
        }

        /** @return the short human title shown as the error heading. */
        public String getTitle() {
            return title;
        }

        /** @return the one-line explanation of what went wrong. */
        public String getExplanation() {
            return explanation;
        }

        /** @return true when offering a Retry action makes sense. */
        public boolean isRetryable() {
            return retryable;
        }

        /** @return recovery tips, possibly empty, never null. */
        public List<String> getTips() {
            return tips;
        }
    }

    /** Matches a bare 3-digit HTTP status code embedded in a message. */
    private static final Pattern STATUS = Pattern.compile("\\b([1-9]\\d{2})\\b");

    /** Guards against a self-referential / cyclic cause chain. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private final Reason reason;
    private final String url;
    private final String detail;

    private LoadFailure(Reason reason, String url, String detail) {
        this.reason = (reason == null) ? Reason.UNKNOWN : reason;
        this.url = (url == null) ? "" : url;
        this.detail = (detail == null) ? "" : detail;
    }

    /**
     * Builds a failure for an explicit reason.
     *
     * @param reason the classification (null becomes {@link Reason#UNKNOWN})
     * @param url    the URL that failed (may be null)
     * @param detail a specific message (may be null)
     * @return the failure, never null
     */
    public static LoadFailure of(Reason reason, String url, String detail) {
        return new LoadFailure(reason, url, detail);
    }

    /**
     * Classifies an exception (and its cause chain) into a {@link LoadFailure}.
     * A typed match wins; otherwise WebKit's message text is inspected; failing
     * that the result is {@link Reason#UNKNOWN} carrying the message as detail.
     *
     * @param t   the throwable (may be null)
     * @param url the URL that failed (may be null)
     * @return the classified failure, never null
     */
    public static LoadFailure classify(Throwable t, String url) {
        if (t == null) {
            return new LoadFailure(Reason.UNKNOWN, url, null);
        }
        Throwable cursor = t;
        for (int depth = 0; cursor != null && depth < MAX_CAUSE_DEPTH; depth++) {
            Reason typed = typedReason(cursor);
            if (typed != null) {
                return new LoadFailure(typed, url, messageOf(cursor));
            }
            Throwable next = cursor.getCause();
            cursor = (next == cursor) ? null : next;
        }
        // No typed match anywhere in the chain: fall back to the message text of
        // the top-level throwable (WebKit often surfaces failures as a string).
        Reason fromMessage = messageReason(t.getMessage());
        if (fromMessage != null) {
            return new LoadFailure(fromMessage, url, messageOf(t));
        }
        return new LoadFailure(Reason.UNKNOWN, url, messageOf(t));
    }

    /**
     * Classifies an explicit HTTP status code (used by the download path and by
     * any WebKit message that carries a status).
     *
     * @param status the HTTP status code
     * @param url    the URL that failed (may be null)
     * @return the classified failure, never null
     */
    public static LoadFailure fromHttpStatus(int status, String url) {
        Reason reason;
        if (status >= 500 && status <= 599) {
            reason = Reason.HTTP_SERVER_ERROR;
        } else if (status >= 400 && status <= 499) {
            reason = Reason.HTTP_CLIENT_ERROR;
        } else {
            reason = Reason.UNKNOWN;
        }
        return new LoadFailure(reason, url, "HTTP " + status);
    }

    /** @return the classified reason, never null. */
    public Reason getReason() {
        return reason;
    }

    /** @return the URL that failed, never null (may be empty). */
    public String getUrl() {
        return url;
    }

    /** @return the specific detail (exception message / status), never null. */
    public String getDetail() {
        return detail;
    }

    /** @return the short human title. */
    public String getTitle() {
        return reason.getTitle();
    }

    /** @return the one-line explanation. */
    public String getExplanation() {
        return reason.getExplanation();
    }

    /** @return true when a Retry action should be offered. */
    public boolean isRetryable() {
        return reason.isRetryable();
    }

    /** @return recovery tips, possibly empty, never null. */
    public List<String> getTips() {
        return reason.getTips();
    }

    @Override
    public String toString() {
        return reason + (detail.isEmpty() ? "" : ": " + detail);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static Reason typedReason(Throwable t) {
        // Order matters: SocketTimeoutException extends InterruptedIOException.
        if (t instanceof UnknownHostException) {
            return Reason.DNS_FAILURE;
        }
        if (t instanceof SocketTimeoutException) {
            return Reason.TIMEOUT;
        }
        if (t instanceof ConnectException) {
            return Reason.CONNECTION_REFUSED;
        }
        if (t instanceof SSLException || t instanceof CertificateException) {
            return Reason.TLS_ERROR;
        }
        if (t instanceof URISyntaxException) {
            return Reason.MALFORMED_URL;
        }
        if (t instanceof InterruptedIOException) {
            return Reason.TIMEOUT;
        }
        return null;
    }

    private static Reason messageReason(String message) {
        if (message == null) {
            return null;
        }
        String m = message.toLowerCase();
        if (m.contains("cancel") || m.contains("abort")) {
            return Reason.CANCELLED;
        }
        if (m.contains("timed out") || m.contains("timeout")) {
            return Reason.TIMEOUT;
        }
        if (m.contains("ssl") || m.contains("certificate") || m.contains("handshake")
                || m.contains("tls")) {
            return Reason.TLS_ERROR;
        }
        if (m.contains("refused")) {
            return Reason.CONNECTION_REFUSED;
        }
        if (m.contains("unknownhost") || m.contains("unresolved")
                || m.contains("name or service not known")
                || m.contains("nodename nor servname")
                || m.contains("temporary failure in name resolution")) {
            return Reason.DNS_FAILURE;
        }
        if (m.contains("malformed") || m.contains("illegal character")
                || m.contains("invalid url") || m.contains("unknown protocol")) {
            return Reason.MALFORMED_URL;
        }
        Matcher match = STATUS.matcher(message);
        while (match.find()) {
            int code = Integer.parseInt(match.group(1));
            if (code >= 500 && code <= 599) {
                return Reason.HTTP_SERVER_ERROR;
            }
            if (code >= 400 && code <= 499) {
                return Reason.HTTP_CLIENT_ERROR;
            }
        }
        return null;
    }

    private static String messageOf(Throwable t) {
        String msg = t.getMessage();
        if (msg != null && !msg.isBlank()) {
            return msg.trim();
        }
        // Fall back to the exception type so the detail is never meaningless.
        String name = t.getClass().getName();
        int dot = name.lastIndexOf('.');
        return (dot >= 0) ? name.substring(dot + 1) : name;
    }

    /**
     * Convenience for an IOException-free classification used by the download
     * path: maps a caught transfer exception to a readable reason string.
     *
     * @param t the transfer exception (may be null)
     * @return a short human-readable reason, never null
     */
    public static String describe(Throwable t) {
        if (t == null) {
            return "";
        }
        LoadFailure f = classify(t, null);
        String detail = f.getDetail();
        return detail.isEmpty() ? f.getTitle() : f.getTitle() + " (" + detail + ")";
    }
}
