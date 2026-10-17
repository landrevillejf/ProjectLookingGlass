/*
 * Pins ManagementAuthGate over Spring's mock servlet objects: a blank token is a
 * pass-through (loopback development stays open), a configured token gates every
 * /api/** call -- a missing, wrong-scheme or wrong bearer is a readable 401 and
 * the chain is never invoked, while the correct bearer passes. Non-API (proxied
 * app) paths are intentionally never gated here.
 */
package org.jdesktop.lg3d.webserver.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ManagementAuthGateTest {

    private static final String TOKEN = "s3cr3t-token";

    private final WebServerProperties properties = new WebServerProperties();

    private void run(String method, String uri, String authorization,
                     MockHttpServletResponse response, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        new ManagementAuthGate(properties).doFilter(request, response, chain);
    }

    @Test
    void blankTokenPassesThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        run("GET", "/api/apps", null, response, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void nonApiPathIsNeverGated() throws Exception {
        properties.setManagementToken(TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        run("GET", "/shop/items", null, response, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void missingBearerIsRejected() throws Exception {
        properties.setManagementToken(TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        run("GET", "/api/apps", null, response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("bearer token");
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        properties.setManagementToken(TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        run("GET", "/api/apps", "Bearer not-the-token", response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void nonBearerSchemeIsRejected() throws Exception {
        properties.setManagementToken(TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        run("GET", "/api/apps", "Basic Zm9vOmJhcg==", response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void correctTokenPasses() throws Exception {
        properties.setManagementToken(TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        run("GET", "/api/apps", "Bearer " + TOKEN, response, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void apiRootIsGated() throws Exception {
        properties.setManagementToken(TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        run("GET", "/api", null, response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
