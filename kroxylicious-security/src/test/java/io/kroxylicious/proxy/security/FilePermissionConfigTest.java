/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.security;

import org.junit.jupiter.api.Test;

import io.kroxylicious.proxy.security.FilePermissionValidator.Policy;

import static org.assertj.core.api.Assertions.assertThat;

class FilePermissionConfigTest {

    @Test
    void shouldDefaultToDisabledPolicy() {
        // Given / When
        FilePermissionConfig config = FilePermissionConfig.DEFAULT;
        // Then
        assertThat(config.getEffectivePolicy()).isEqualTo(Policy.DISABLED);
    }

    @Test
    void shouldTreatNullPolicyAsDisabled() {
        // Given / When
        FilePermissionConfig config = new FilePermissionConfig(null);
        // Then
        assertThat(config.getEffectivePolicy()).isEqualTo(Policy.DISABLED);
    }

    @Test
    void shouldReturnStrictWhenExplicitlyConfigured() {
        // Given
        FilePermissionConfig config = new FilePermissionConfig(Policy.STRICT);
        // When / Then
        assertThat(config.getEffectivePolicy()).isEqualTo(Policy.STRICT);
    }

    @Test
    void shouldReturnRelaxedWhenExplicitlyConfigured() {
        // Given
        FilePermissionConfig config = new FilePermissionConfig(Policy.RELAXED);
        // When / Then
        assertThat(config.getEffectivePolicy()).isEqualTo(Policy.RELAXED);
    }

    @Test
    void shouldReturnDisabledWhenExplicitlyConfigured() {
        // Given
        FilePermissionConfig config = new FilePermissionConfig(Policy.DISABLED);
        // When / Then
        assertThat(config.getEffectivePolicy()).isEqualTo(Policy.DISABLED);
    }
}