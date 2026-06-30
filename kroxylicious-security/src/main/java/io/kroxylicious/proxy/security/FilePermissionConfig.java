/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.security;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.kroxylicious.proxy.security.FilePermissionValidator.Policy;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Configuration for file permission validation.
 * Controls how strictly file permissions are checked for confidential files.
 * Defaults to {@link Policy#DISABLED} for backward compatibility.
 *
 * @param policy the validation policy; {@code null} is treated as {@link Policy#DISABLED}
 */
public record FilePermissionConfig(
                                   @JsonProperty("policy") @Nullable Policy policy) {

    /**
     * Default configuration: DISABLED for backward compatibility.
     */
    public static final FilePermissionConfig DEFAULT = new FilePermissionConfig(null);

    /**
     * Returns the effective policy, defaulting to {@link Policy#DISABLED} when no policy is configured.
     *
     * @return the resolved policy, never null
     */
    public Policy getEffectivePolicy() {
        return policy != null ? policy : Policy.DISABLED;
    }
}
