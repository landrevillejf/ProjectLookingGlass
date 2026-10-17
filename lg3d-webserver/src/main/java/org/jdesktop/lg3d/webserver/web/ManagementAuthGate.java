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
package org.jdesktop.lg3d.webserver.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * An optional bearer-token gate over the management API -- the same opt-in posture
 * as {@code wonderland-server}'s login gate.
 *
 * <p>When {@code webserver.management.token} is blank (the default) the filter is a
 * pass-through, leaving {@code /api/**} open on loopback for development. When a
 * token is configured, every {@code /api/**} request must present
 * {@code Authorization: Bearer <token>} or receive a {@code 401} with a readable
 * body. The reverse-proxied application paths are intentionally not gated here --
 * they belong to the deployed apps -- and the token is compared in constant time to
 * avoid leaking its length.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ManagementAuthGate extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final WebServerProperties properties;

    public ManagementAuthGate(WebServerProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = properties.getManagementToken();
        if (token == null || token.isBlank() || !isApiPath(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        if (constantTimeEquals(token, bearerOf(request.getHeader(HttpHeaders.AUTHORIZATION)))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"a valid bearer token is required\"}");
    }

    private static boolean isApiPath(String uri) {
        return uri != null && (uri.equals("/api") || uri.startsWith("/api/"));
    }

    /** @return the token carried by an {@code Authorization: Bearer} header, or null. */
    private static String bearerOf(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String candidate = header.substring(BEARER_PREFIX.length()).trim();
        return candidate.isEmpty() ? null : candidate;
    }

    /** Length-independent comparison so a probe cannot narrow the token down. */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
