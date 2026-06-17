/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator;

import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.api.model.Route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProxySecurityModelTest {

    @Test
    void shouldReturnTrueWhenClientSupportsRouteApi() {
        // Given
        KubernetesClient client = mock(KubernetesClient.class);
        when(client.supports(Route.class)).thenReturn(true);

        // When / Then
        assertThat(ProxySecurityModel.isOpenShift(client)).isTrue();
    }

    @Test
    void shouldReturnFalseWhenClientDoesNotSupportRouteApi() {
        // Given
        KubernetesClient client = mock(KubernetesClient.class);
        when(client.supports(Route.class)).thenReturn(false);

        // When / Then
        assertThat(ProxySecurityModel.isOpenShift(client)).isFalse();
    }
}