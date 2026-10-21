/*
 * Pins ApiExceptionHandler's typed-failure -> HTTP mapping directly, so the two
 * arms the MockMvc surface never reaches on its own are covered: the
 * IllegalArgumentException handler (the registry raises it for a duplicate
 * port/context path, but the standalone controller test only trips the
 * bean-validation 400) and body()'s null-message fallback to the status reason
 * phrase. Every body is the uniform {"error": "<sentence>"} record.
 */
package org.jdesktop.lg3d.webserver.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.NoSuchElementException;
import org.jdesktop.lg3d.webserver.deploy.DeploymentException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void illegalArgumentMapsTo400CarryingTheMessage() {
        ResponseEntity<ApiExceptionHandler.ApiError> response =
                handler.badRequest(new IllegalArgumentException("port already used"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("port already used");
    }

    @Test
    void nullMessageFallsBackToTheStatusReasonPhrase() {
        ResponseEntity<ApiExceptionHandler.ApiError> response =
                handler.notFound(new NoSuchElementException());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo(HttpStatus.NOT_FOUND.getReasonPhrase());
    }

    @Test
    void deploymentExceptionMapsTo503() {
        ResponseEntity<ApiExceptionHandler.ApiError> response =
                handler.unavailable(new DeploymentException("no daemon"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("no daemon");
    }
}
