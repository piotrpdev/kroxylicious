/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.strimzi.api.kafka.model.kafka.listener.ListenerStatus;

import io.kroxylicious.kubernetes.api.v1alpha1.kafkaservicespec.Tls;
import io.kroxylicious.kubernetes.api.v1alpha1.kafkaservicespec.TlsBuilder;
import io.kroxylicious.systemtests.clients.records.ConsumerRecord;
import io.kroxylicious.systemtests.installation.kroxylicious.Kroxylicious;
import io.kroxylicious.systemtests.installation.kroxylicious.KroxyliciousBuilder;
import io.kroxylicious.systemtests.installation.kroxylicious.KroxyliciousOperator;
import io.kroxylicious.systemtests.steps.KafkaSteps;
import io.kroxylicious.systemtests.steps.KroxyliciousSteps;
import io.kroxylicious.systemtests.templates.strimzi.KafkaNodePoolTemplates;
import io.kroxylicious.systemtests.templates.strimzi.KafkaTemplates;
import io.kroxylicious.systemtests.utils.KafkaUtils;

import static io.kroxylicious.systemtests.k8s.KubeClusterResource.kubeClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies that the proxy correctly handles file permission validation in a real Kubernetes
 * deployment. The operator injects {@code policy: RELAXED} and sets {@code defaultMode: 0440}
 * on secret volumes; this test confirms the proxy starts and serves traffic with those settings.
 */
class FilePermissionST extends AbstractSystemTests {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilePermissionST.class);
    private static final String CLUSTER_NAME = "file-permission-st-cluster";
    private static final String UPSTREAM_CA_SECRET_NAME = "upstream-tls-ca";
    private static final String CA_CERT_KEY = "ca.pem";
    private static final int SECRET_VOLUME_DEFAULT_MODE_OCTAL_0440 = 288;
    private static final String MESSAGE = "Hello-world";

    private static Kroxylicious kroxylicious;
    private static KroxyliciousOperator kroxyliciousOperator;

    @BeforeAll
    void setupBefore() {
        LOGGER.atInfo().log("Deploying Kafka cluster with TLS listener");
        resourceManager.createOrUpdateResourceFromBuilderWithWait(
                KafkaNodePoolTemplates.poolWithDualRoleAndPersistentStorage(Constants.KAFKA_DEFAULT_NAMESPACE, CLUSTER_NAME, 1),
                KafkaTemplates.defaultKafka(Constants.KAFKA_DEFAULT_NAMESPACE, CLUSTER_NAME, 1));

        kroxyliciousOperator = new KroxyliciousOperator(Constants.KROXYLICIOUS_OPERATOR_NAMESPACE);
        kroxyliciousOperator.deploy();
    }

    @AfterAll
    void cleanUp() {
        kroxyliciousOperator.delete();
    }

    @Test
    void relaxedPolicyAllowsProxyToStartWithGroupReadableSecretVolume(String namespace) {
        // Given - extract the Kafka TLS CA cert and store it in a Secret (not a ConfigMap).
        // Using a Secret reference causes the operator to mount it with defaultMode: 0440,
        // exercising the RELAXED policy path end-to-end.
        var caCert = KafkaUtils.getKafkaListenerStatus("tls")
                .stream()
                .map(ListenerStatus::getCertificates)
                .findFirst()
                .orElseThrow()
                .get(0);

        // @formatter:off
        resourceManager.createOrUpdateResourceFromBuilderWithWait(
                new SecretBuilder()
                        .withNewMetadata()
                            .withName(UPSTREAM_CA_SECRET_NAME)
                            .withNamespace(Constants.KROXYLICIOUS_NAMESPACE)
                        .endMetadata()
                        .withStringData(Map.of(CA_CERT_KEY, caCert)));
        // @formatter:on

        // @formatter:off
        Tls upstreamTls = new TlsBuilder()
                .withNewTrustAnchorRef()
                    .withNewRef()
                        .withName(UPSTREAM_CA_SECRET_NAME)
                        .withKind(Constants.SECRET)
                    .endRef()
                    .withKey(CA_CERT_KEY)
                .endTrustAnchorRef()
                .build();
        // @formatter:on

        kroxylicious = KroxyliciousBuilder.singleNodeBaseBuilder(Constants.KROXYLICIOUS_NAMESPACE, CLUSTER_NAME, 1)
                .withTls(upstreamTls)
                .build();
        kroxylicious.createOrUpdateResources();

        // When - the operator reconciles: it sets policy: RELAXED in the proxy config and
        // defaultMode: 0440 (= 288 decimal) on all secret volumes. The proxy reads those
        // files at startup; RELAXED accepts group-readable files (0440).
        String bootstrap = kroxylicious.getBootstrap(Constants.KROXYLICIOUS_NAMESPACE, CLUSTER_NAME);

        // Then - all secret volumes have defaultMode 0440
        await().atMost(Duration.ofMinutes(2)).untilAsserted(() -> {
            Deployment deployment = kubeClient().getDeployment(Constants.KROXYLICIOUS_NAMESPACE, Constants.KROXYLICIOUS_PROXY_SIMPLE_NAME);
            assertThat(deployment).isNotNull();
            assertThat(deployment.getSpec().getTemplate().getSpec().getVolumes())
                    .filteredOn(v -> v.getSecret() != null)
                    .isNotEmpty()
                    .allSatisfy(v -> assertThat(v.getSecret().getDefaultMode())
                            .as("defaultMode of secret volume '%s'", v.getName())
                            .isEqualTo(SECRET_VOLUME_DEFAULT_MODE_OCTAL_0440));
        });

        // Then - proxy is reachable and serves traffic (RELAXED policy did not reject the 0440 files)
        KafkaSteps.createTopic(namespace, topicName, bootstrap, 1, 1);
        KroxyliciousSteps.produceMessages(namespace, topicName, bootstrap, MESSAGE, 1);
        List<ConsumerRecord> result = KroxyliciousSteps.consumeMessages(namespace, topicName, bootstrap, 1, Duration.ofMinutes(2));
        assertThat(result).extracting(ConsumerRecord::getPayload)
                .hasSize(1)
                .allSatisfy(v -> assertThat(v).contains(MESSAGE));
    }
}
