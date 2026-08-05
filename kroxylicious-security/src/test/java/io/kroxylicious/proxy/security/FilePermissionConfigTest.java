/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.security;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kroxylicious.proxy.security.FilePermissionValidator.Category;
import io.kroxylicious.proxy.security.FilePermissionValidator.Policy;

import static org.assertj.core.api.Assertions.assertThat;

class FilePermissionConfigTest {

    @Test
    void defaultConfigDisablesAllCategories() {
        // Given / When
        var policies = FilePermissionConfig.DEFAULT.getEffectivePolicies();

        // Then
        assertThat(policies).containsExactlyInAnyOrderEntriesOf(Map.of(
                Category.SECRETS, Policy.DISABLED,
                Category.TRUSTSTORES, Policy.DISABLED,
                Category.PLATFORM_CREDENTIALS, Policy.DISABLED));
    }

    @Test
    void nullFieldsDefaultToDisabled() {
        // Given / When
        var policies = new FilePermissionConfig(null, null, null).getEffectivePolicies();

        // Then
        assertThat(policies).containsExactlyInAnyOrderEntriesOf(Map.of(
                Category.SECRETS, Policy.DISABLED,
                Category.TRUSTSTORES, Policy.DISABLED,
                Category.PLATFORM_CREDENTIALS, Policy.DISABLED));
    }

    @Test
    void explicitPoliciesAreReturned() {
        // Given / When
        var policies = new FilePermissionConfig(Policy.STRICT, Policy.RELAXED, Policy.DISABLED).getEffectivePolicies();

        // Then
        assertThat(policies).containsExactlyInAnyOrderEntriesOf(Map.of(
                Category.SECRETS, Policy.STRICT,
                Category.TRUSTSTORES, Policy.RELAXED,
                Category.PLATFORM_CREDENTIALS, Policy.DISABLED));
    }

    @Test
    void partialConfigDefaultsRemainingToDisabled() {
        // Given / When
        var policies = new FilePermissionConfig(Policy.STRICT, null, null).getEffectivePolicies();

        // Then
        assertThat(policies).containsExactlyInAnyOrderEntriesOf(Map.of(
                Category.SECRETS, Policy.STRICT,
                Category.TRUSTSTORES, Policy.DISABLED,
                Category.PLATFORM_CREDENTIALS, Policy.DISABLED));
    }
}
