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
package com.protonmail.landrevillejf.swingide.update;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shared HTTP plumbing for the update client.
 * <p>
 * The release metadata and the artefacts live on a private GitHub repository, so
 * a request must carry a bearer token to be answered. GitHub resolves a download
 * through a chain of redirects that ends on a different host
 * ({@code objects.githubusercontent.com}) serving a pre-signed URL, which must be
 * fetched <em>without</em> the token. This helper follows the redirects manually
 * and attaches the {@code Authorization} header only while the request stays on
 * the original host, so the credential is never forwarded to a third party.
 * </p>
 * <p>
 * The {@link HttpClient} handed to {@link #get} must be built with
 * {@link HttpClient.Redirect#NEVER}; redirect handling lives here.
 * </p>
 */
@Slf4j
final class UpdateHttpSupport {

    /** Safety net against a redirect loop. */
    private static final int MAX_REDIRECTS = 8;

    private UpdateHttpSupport() {
    }

    /**
     * Issues a GET and follows redirects, returning the final response with an
     * open body stream the caller must close.
     *
     * @param client  client built with {@link HttpClient.Redirect#NEVER}
     * @param uri     resource to fetch
     * @param token   bearer token, attached only on same-host hops; {@code null}
     *                or blank for an anonymous request
     * @param timeout per-request timeout
     * @return the final, non-redirect response
     * @throws IOException          when the request fails or loops too often
     * @throws InterruptedException when the calling thread is interrupted
     */
    static HttpResponse<InputStream> get(HttpClient client, URI uri, String token, Duration timeout)
            throws IOException, InterruptedException {

        String authHost = uri.getHost();
        URI current = uri;

        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                .GET()
                .timeout(timeout);

            if (hasText(token) && authHost.equalsIgnoreCase(current.getHost())) {
                builder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<InputStream> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (!isRedirect(response.statusCode())) {
                return response;
            }

            String location = response.headers().firstValue("Location").orElse(null);
            // A redirect body is empty; close it before following the next hop.
            response.body().close();

            if (location == null) {
                log.warn("Redirect {} from {} carried no Location header",
                    response.statusCode(), current);
                return response;
            }

            current = current.resolve(location);
        }

        throw new IOException("Too many redirects (>" + MAX_REDIRECTS + ") starting from " + uri);
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
            || statusCode == 307 || statusCode == 308;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
