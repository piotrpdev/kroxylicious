/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.kroxylicious.proxy.security.FilePermissionConfig;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Security configuration for the proxy.
 * Controls various security-related behaviors.
 *
 * @param filePermissions configuration for file permission validation
 */
public record SecurityConfig(
                             @JsonProperty("filePermissions") @Nullable FilePermissionConfig filePermissions) {

    /**
     * Default security configuration.
     */
    public static final SecurityConfig DEFAULT = new SecurityConfig(FilePermissionConfig.DEFAULT);

    /**
     * Gets the effective file permissions configuration.
     *
     * @return the file permissions config, never null
     */
    public FilePermissionConfig getEffectiveFilePermissions() {
        return filePermissions != null ? filePermissions : FilePermissionConfig.DEFAULT;
    }
}
