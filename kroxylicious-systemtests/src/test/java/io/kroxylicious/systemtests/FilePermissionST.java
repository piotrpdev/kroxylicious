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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.strimzi.api.kafka.model.kafka.listener.ListenerStatus;

import io.kroxylicious.kubernetes.api.common.Condition;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxy;
import io.kroxylicious.kubernetes.api.v1alpha1.kafkaproxyspec.security.FilePermissions;
import io.kroxylicious.kubernetes.api.v1alpha1.kafkaservicespec.Tls;
import io.kroxylicious.kubernetes.api.v1alpha1.kafkaservicespec.TlsBuilder;
import io.kroxylicious.systemtests.clients.records.ConsumerRecord;
import io.kroxylicious.systemtests.installation.kroxylicious.Kroxylicious;
import io.kroxylicious.systemtests.installation.kroxylicious.KroxyliciousBuilder;
import io.kroxylicious.systemtests.installation.kroxylicious.KroxyliciousOperator;
import io.kroxylicious.systemtests.steps.KafkaSteps;
import io.kroxylicious.systemtests.steps.KroxyliciousSteps;
import io.kroxylicious.systemtests.templates.kroxylicious.KroxyliciousKafkaClusterRefTemplates;
import io.kroxylicious.systemtests.templates.kroxylicious.KroxyliciousKafkaProxyIngressTemplates;
import io.kroxylicious.systemtests.templates.kroxylicious.KroxyliciousKafkaProxyTemplates;
import io.kroxylicious.systemtests.templates.kroxylicious.KroxyliciousVirtualKafkaClusterTemplates;
import io.kroxylicious.systemtests.templates.strimzi.KafkaNodePoolTemplates;
import io.kroxylicious.systemtests.templates.strimzi.KafkaTemplates;
import io.kroxylicious.systemtests.utils.KafkaUtils;

import static io.kroxylicious.systemtests.k8s.KubeClusterResource.kubeClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies that the proxy correctly handles file permission validation in a real Kubernetes
 * deployment. The operator injects {@code policy: RELAXED} and sets {@code defaultMode: 0440}
 * on secret volumes; these tests confirm that RELAXED accepts 0440 files and STRICT rejects them.
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

    @BeforeEach
    void deleteExistingProxy() {
        kubeClient().getClient().resources(KafkaProxy.class)
                .inNamespace(Constants.KROXYLICIOUS_NAMESPACE)
                .withName(Constants.KROXYLICIOUS_PROXY_SIMPLE_NAME)
                .delete();
        await().atMost(Duration.ofMinutes(1)).untilAsserted(() -> assertThat(kubeClient().getClient().resources(KafkaProxy.class)
                .inNamespace(Constants.KROXYLICIOUS_NAMESPACE)
                .withName(Constants.KROXYLICIOUS_PROXY_SIMPLE_NAME)
                .get()).isNull());
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
        var caCert = getCaCert();

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
        var proxyWithRelaxedPolicy = KroxyliciousKafkaProxyTemplates.defaultKafkaProxyCR(1)
                .editSpec()
                    .withNewSecurity()
                        .withNewFilePermissions()
                            .withPolicy(FilePermissions.Policy.RELAXED)
                        .endFilePermissions()
                    .endSecurity()
                .endSpec()
                .build();
        // @formatter:on

        kroxylicious = KroxyliciousBuilder.singleNodeBaseBuilder(Constants.KROXYLICIOUS_NAMESPACE, CLUSTER_NAME, 1)
                .withKafkaProxy(proxyWithRelaxedPolicy)
                .withTls(secretBackedUpstreamTls())
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

    @Test
    void strictPolicyPreventsProxyFromStartingWithGroupReadableSecretVolume(String namespace) {
        // Given - CA cert in a Secret + proxy configured with STRICT policy.
        // The operator still sets defaultMode: 0440 on secret volumes regardless of policy.
        // STRICT rejects group-readable files (0440), so the proxy exits with code 78 (EX_CONFIG).
        // The operator detects this and sets FilePermissionsValid=False on the KafkaProxy.
        //
        // We bypass Kroxylicious.createOrUpdateResources() because it waits for deployment
        // readiness, which never happens when the proxy is intentionally crashing.
        var caCert = getCaCert();

        // @formatter:off
        resourceManager.createOrUpdateResourceFromBuilderWithWait(
                new SecretBuilder()
                        .withNewMetadata()
                            .withName(UPSTREAM_CA_SECRET_NAME)
                            .withNamespace(Constants.KROXYLICIOUS_NAMESPACE)
                        .endMetadata()
                        .withStringData(Map.of(CA_CERT_KEY, caCert)));

        var proxyWithStrictPolicy = KroxyliciousKafkaProxyTemplates.defaultKafkaProxyCR(1)
                .editMetadata()
                    .withNamespace(Constants.KROXYLICIOUS_NAMESPACE)
                .endMetadata()
                .editSpec()
                    .withNewSecurity()
                        .withNewFilePermissions()
                            .withPolicy(FilePermissions.Policy.STRICT)
                        .endFilePermissions()
                    .endSecurity()
                .endSpec()
                .build();
        // @formatter:on

        var ingress = KroxyliciousKafkaProxyIngressTemplates.kafkaProxyIngressClusterIpCR()
                .editMetadata().withNamespace(Constants.KROXYLICIOUS_NAMESPACE).endMetadata()
                .build();

        var kafkaService = KroxyliciousKafkaClusterRefTemplates.defaultKafkaClusterRefCR(CLUSTER_NAME)
                .editMetadata().withNamespace(Constants.KROXYLICIOUS_NAMESPACE).endMetadata()
                .editSpec()
                .withBootstrapServers(CLUSTER_NAME + "-kafka-bootstrap." + Constants.KAFKA_DEFAULT_NAMESPACE + ".svc.cluster.local:9093")
                .withTls(secretBackedUpstreamTls())
                .endSpec()
                .build();

        var vkc = KroxyliciousVirtualKafkaClusterTemplates.defaultVirtualKafkaClusterCR(CLUSTER_NAME, Constants.KROXYLICIOUS_INGRESS_CLUSTER_IP)
                .editMetadata().withNamespace(Constants.KROXYLICIOUS_NAMESPACE).endMetadata()
                .build();

        // When - apply CRDs; the operator reconciles and the proxy crashes with exit code 78
        resourceManager.createOrUpdateResourceWithWait(proxyWithStrictPolicy, ingress, kafkaService, vkc);

        // Then - the operator sets FilePermissionsValid=False on the KafkaProxy status
        await().atMost(Duration.ofMinutes(3)).untilAsserted(() -> {
            KafkaProxy proxy = kubeClient().getClient().resources(KafkaProxy.class)
                    .inNamespace(Constants.KROXYLICIOUS_NAMESPACE)
                    .withName(Constants.KROXYLICIOUS_PROXY_SIMPLE_NAME).get();
            assertThat(proxy).isNotNull();
            assertThat(proxy.getStatus()).isNotNull();
            assertThat(proxy.getStatus().getConditions())
                    .anySatisfy(c -> {
                        assertThat(c.getType()).isEqualTo(Condition.Type.FilePermissionsValid);
                        assertThat(c.getStatus()).isEqualTo(Condition.Status.FALSE);
                        assertThat(c.getReason()).isEqualTo(Condition.REASON_FILE_PERMISSIONS_VIOLATION);
                        assertThat(c.getMessage()).contains("too open");
                    });
        });
    }

    private static String getCaCert() {
        return KafkaUtils.getKafkaListenerStatus("tls")
                .stream()
                .map(ListenerStatus::getCertificates)
                .findFirst()
                .orElseThrow()
                .getFirst();
    }

    private static Tls secretBackedUpstreamTls() {
        // @formatter:off
        return new TlsBuilder()
                .withNewTrustAnchorRef()
                    .withNewRef()
                        .withName(UPSTREAM_CA_SECRET_NAME)
                        .withKind(Constants.SECRET)
                    .endRef()
                    .withKey(CA_CERT_KEY)
                .endTrustAnchorRef()
                .build();
        // @formatter:on
    }
}
