/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator.reconciler.kafkaproxy;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.Pod;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.SecondaryToPrimaryMapper;

class PodSecondaryToKafkaProxyPrimaryMapper implements SecondaryToPrimaryMapper<Pod> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PodSecondaryToKafkaProxyPrimaryMapper.class);
    private static final String INSTANCE_LABEL = "app.kubernetes.io/instance";

    @Override
    public Set<ResourceID> toPrimaryResourceIDs(Pod pod) {
        String instanceName = pod.getMetadata().getLabels().get(INSTANCE_LABEL);
        if (instanceName == null) {
            LOGGER.atDebug()
                    .addKeyValue("pod", pod.getMetadata().getName())
                    .log("Ignoring pod without instance label");
            return Set.of();
        }
        Set<ResourceID> proxyIds = Set.of(new ResourceID(instanceName, pod.getMetadata().getNamespace()));
        LOGGER.atDebug()
                .addKeyValue("proxyIds", proxyIds)
                .log("Event source Pod SecondaryToPrimaryMapper");
        return proxyIds;
    }
}