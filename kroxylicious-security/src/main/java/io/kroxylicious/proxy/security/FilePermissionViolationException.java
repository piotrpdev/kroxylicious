/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.security;

/**
 * Thrown when a confidential file has permissions that are too open for the configured policy.
 */
public class FilePermissionViolationException extends IllegalStateException {

    public FilePermissionViolationException(String message) {
        super(message);
    }
}