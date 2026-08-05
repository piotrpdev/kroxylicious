/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.security;

import java.util.EnumMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.kroxylicious.proxy.security.FilePermissionValidator.Category;
import io.kroxylicious.proxy.security.FilePermissionValidator.Policy;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Configuration for file permission validation, with per-category policies.
 *
 * @param secrets policy for TLS private keys, keystores, and password files; defaults to {@link Policy#DISABLED}
 * @param truststores policy for TLS truststore files; defaults to {@link Policy#DISABLED}
 * @param platformCredentials policy for platform-managed credential files (e.g. AWS tokens); defaults to {@link Policy#DISABLED}
 */
public record FilePermissionConfig(
                                   @JsonProperty("secrets") @Nullable Policy secrets,
                                   @JsonProperty("truststores") @Nullable Policy truststores,
                                   @JsonProperty("platformCredentials") @Nullable Policy platformCredentials) {

    /**
     * Default configuration: DISABLED for all categories (backward compatibility).
     */
    public static final FilePermissionConfig DEFAULT = new FilePermissionConfig(null, null, null);

    /**
     * Returns the effective policy map, defaulting each null category to {@link Policy#DISABLED}.
     *
     * @return a non-null map with a policy for every category
     */
    public Map<Category, Policy> getEffectivePolicies() {
        var map = new EnumMap<Category, Policy>(Category.class);
        map.put(Category.SECRETS, secrets != null ? secrets : Policy.DISABLED);
        map.put(Category.TRUSTSTORES, truststores != null ? truststores : Policy.DISABLED);
        map.put(Category.PLATFORM_CREDENTIALS, platformCredentials != null ? platformCredentials : Policy.DISABLED);
        return map;
    }
}