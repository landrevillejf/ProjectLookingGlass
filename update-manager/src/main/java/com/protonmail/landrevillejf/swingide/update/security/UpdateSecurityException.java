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
