package com.protonmail.landrevillejf.swingide.update.security;

/**
 * Exception thrown when update security validation fails.
 */
public class UpdateSecurityException extends Exception {
    
    public UpdateSecurityException(String message) {
        super(message);
    }
    
    public UpdateSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
