/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator.reconciler.kafkaproxy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.javaoperatorsdk.operator.processing.event.ResourceID;

import static org.assertj.core.api.Assertions.assertThat;

class PodSecondaryToKafkaProxyPrimaryMapperTest {

    private final PodSecondaryToKafkaProxyPrimaryMapper mapper = new PodSecondaryToKafkaProxyPrimaryMapper();

    @Test
    void podWithInstanceLabelMapsToKafkaProxy() {
        // Given
        // @formatter:off
        var pod = new PodBuilder()
                .withNewMetadata()
                    .withName("simple-8b5fff688-jsp2l")
                    .withNamespace("kroxylicious")
                    .withLabels(Map.of(
                            "app.kubernetes.io/managed-by", "kroxylicious-operator",
                            "app.kubernetes.io/instance", "simple"))
                .endMetadata()
                .build();
        // @formatter:on

        // When
        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(pod);

        // Then
        assertThat(primaryResourceIDs).containsExactly(new ResourceID("simple", "kroxylicious"));
    }

    @Test
    void podWithoutInstanceLabelReturnsEmpty() {
        // Given
        // @formatter:off
        var pod = new PodBuilder()
                .withNewMetadata()
                    .withName("unrelated-pod")
                    .withNamespace("kroxylicious")
                    .withLabels(Map.of("app.kubernetes.io/managed-by", "kroxylicious-operator"))
                .endMetadata()
                .build();
        // @formatter:on

        // When
        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(pod);

        // Then
        assertThat(primaryResourceIDs).isEmpty();
    }

    @Test
    void podWithNullLabelsReturnsEmpty() {
        // Given
        var pod = new PodBuilder()
                .withNewMetadata()
                    .withName("no-labels-pod")
                    .withNamespace("kroxylicious")
                .endMetadata()
                .build();

        // When
        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(pod);

        // Then
        assertThat(primaryResourceIDs).isEmpty();
    }
}