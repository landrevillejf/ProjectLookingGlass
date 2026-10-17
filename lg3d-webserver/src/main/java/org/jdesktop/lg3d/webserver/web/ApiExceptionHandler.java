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

import java.util.NoSuchElementException;
import org.jdesktop.lg3d.webserver.deploy.DeploymentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the registry's typed failures into clean, readable HTTP responses so the
 * API never leaks a stack trace or a bare {@code 500}.
 *
 * <p>The mapping mirrors the failure kinds the registry raises: bad input is a
 * {@code 400}, an unknown deployment id is a {@code 404}, an operational failure
 * (no Docker daemon, a container that will not start) is a {@code 503}, and a
 * bean-validation rejection (a missing deployment type) is a {@code 400} carrying
 * the first field error. Every body is {@code {"error": "<sentence>"}}.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** The uniform error body. */
    public record ApiError(String error) {
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst()
                .orElse("invalid request");
        return body(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(DeploymentException.class)
    public ResponseEntity<ApiError> unavailable(DeploymentException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    private static ResponseEntity<ApiError> body(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(message == null ? status.getReasonPhrase() : message));
    }
}
