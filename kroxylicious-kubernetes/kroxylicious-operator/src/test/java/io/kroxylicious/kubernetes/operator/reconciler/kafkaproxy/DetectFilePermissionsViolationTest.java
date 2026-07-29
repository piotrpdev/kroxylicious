/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator.reconciler.kafkaproxy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.managed.ManagedWorkflowAndDependentResourceContext;

import io.kroxylicious.kubernetes.api.common.Condition;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxy;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxyBuilder;
import io.kroxylicious.kubernetes.operator.SecureConfigInterpolator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DetectFilePermissionsViolationTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.EPOCH, ZoneId.of("Z"));

    @Mock(strictness = Mock.Strictness.LENIENT)
    Context<KafkaProxy> context;

    @Mock
    ManagedWorkflowAndDependentResourceContext workflowContext;

    private AutoCloseable closeable;

    @BeforeEach
    void openMocks() {
        closeable = MockitoAnnotations.openMocks(this);
        when(context.managedWorkflowAndDependentResourceContext()).thenReturn(workflowContext);
    }

    @AfterEach
    void releaseMocks() throws Exception {
        closeable.close();
    }

    @Test
    void detectsExitCode78InLastStateTerminated() {
        // Given
        stubDeploymentAndPods(podWithLastStateTerminated(78, "Permissions 0440 are too open."));

        // When
        var result = reconcile();

        // Then
        assertFilePermissionsCondition(result, Condition.Status.FALSE);
    }

    @Test
    void detectsExitCode78InStateTerminated() {
        // Given
        stubDeploymentAndPods(podWithStateTerminated(78, "Permissions 0440 are too open."));

        // When
        var result = reconcile();

        // Then
        assertFilePermissionsCondition(result, Condition.Status.FALSE);
    }

    @Test
    void ignoresExitCode78WhenContainerIsRunning() {
        // Given
        stubDeploymentAndPods(podWithRunningContainerAndLastStateExitCode(78));

        // When
        var result = reconcile();

        // Then
        assertFilePermissionsCondition(result, Condition.Status.TRUE);
    }

    @Test
    void trueWhenNoPodsExist() {
        // Given
        stubDeploymentAndPods();

        // When
        var result = reconcile();

        // Then
        assertFilePermissionsCondition(result, Condition.Status.TRUE);
    }

    @Test
    void trueWhenNoDeploymentExists() {
        // Given
        when(context.getSecondaryResource(Deployment.class, KafkaProxyReconciler.DEPLOYMENT_DEP))
                .thenReturn(Optional.empty());

        // When
        var result = reconcile();

        // Then
        assertFilePermissionsCondition(result, Condition.Status.TRUE);
    }

    @Test
    void trueWhenPodHasDifferentExitCode() {
        // Given
        stubDeploymentAndPods(podWithLastStateTerminated(1, "some other error"));

        // When
        var result = reconcile();

        // Then
        assertFilePermissionsCondition(result, Condition.Status.TRUE);
    }

    @Test
    void trueWhenPodStatusIsNull() {
        // Given
        var pod = new PodBuilder()
                .withNewMetadata().withName("simple-abc").withNamespace("ns").endMetadata()
                .build();
        stubDeploymentAndPods(pod);

        // When
        var result = reconcile();

        // Then
        assertFilePermissionsCondition(result, Condition.Status.TRUE);
    }

    @Test
    void trueWhenApiCallFails() {
        // Given
        var deployment = defaultDeployment();
        when(context.getSecondaryResource(Deployment.class, KafkaProxyReconciler.DEPLOYMENT_DEP))
                .thenReturn(Optional.of(deployment));
        when(context.getClient()).thenThrow(new RuntimeException("API unavailable"));

        // When
        var result = reconcile();

        // Then
        assertFilePermissionsCondition(result, Condition.Status.TRUE);
    }

    @Test
    void usesTerminationMessageWhenAvailable() {
        // Given
        String expectedMessage = "Permissions 0440 for '/opt/secrets/key.pem' are too open.";
        stubDeploymentAndPods(podWithLastStateTerminated(78, expectedMessage));

        // When
        var result = reconcile();

        // Then
        var proxy = result.getResource().orElseThrow();
        assertThat(proxy.getStatus().getConditions())
                .filteredOn(c -> c.getType() == Condition.Type.FilePermissionsValid)
                .singleElement()
                .satisfies(c -> assertThat(c.getMessage()).isEqualTo(expectedMessage));
    }

    @Test
    void fallbackMessageWhenTerminationMessageIsNull() {
        // Given
        stubDeploymentAndPods(podWithLastStateTerminated(78, null));

        // When
        var result = reconcile();

        // Then
        var proxy = result.getResource().orElseThrow();
        assertThat(proxy.getStatus().getConditions())
                .filteredOn(c -> c.getType() == Condition.Type.FilePermissionsValid)
                .singleElement()
                .satisfies(c -> assertThat(c.getMessage()).contains("exit code 78"));
    }

    private KafkaProxy defaultProxy() {
        return new KafkaProxyBuilder()
                .withNewMetadata()
                    .withName("simple")
                    .withNamespace("ns")
                    .withGeneration(1L)
                .endMetadata()
                .build();
    }

    private Deployment defaultDeployment() {
        return new DeploymentBuilder()
                .withNewMetadata().withName("simple").endMetadata()
                .withNewSpec()
                    .withNewSelector()
                        .withMatchLabels(Map.of("app.kubernetes.io/instance", "simple"))
                    .endSelector()
                .endSpec()
                .withNewStatus().withReadyReplicas(0).withReplicas(1).endStatus()
                .build();
    }

    @SuppressWarnings("unchecked")
    private void stubDeploymentAndPods(Pod... pods) {
        var deployment = defaultDeployment();
        when(context.getSecondaryResource(Deployment.class, KafkaProxyReconciler.DEPLOYMENT_DEP))
                .thenReturn(Optional.of(deployment));

        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation podsOp = mock(MixedOperation.class);
        NonNamespaceOperation namespacedPods = mock(NonNamespaceOperation.class);
        FilterWatchListDeletable labelledPods = mock(FilterWatchListDeletable.class);

        when(context.getClient()).thenReturn(client);
        when(client.pods()).thenReturn(podsOp);
        when(podsOp.inNamespace(any())).thenReturn(namespacedPods);
        when(namespacedPods.withLabels(anyMap())).thenReturn(labelledPods);
        when(labelledPods.list()).thenReturn(new PodListBuilder().withItems(pods).build());
    }

    private Pod podWithLastStateTerminated(int exitCode, String message) {
        // @formatter:off
        return new PodBuilder()
                .withNewMetadata().withName("simple-abc").withNamespace("ns").endMetadata()
                .withNewStatus()
                    .withContainerStatuses(new ContainerStatusBuilder()
                            .withName("proxy")
                            .withNewState()
                                .withNewWaiting().withReason("CrashLoopBackOff").endWaiting()
                            .endState()
                            .withNewLastState()
                                .withNewTerminated().withExitCode(exitCode).withMessage(message).endTerminated()
                            .endLastState()
                            .build())
                .endStatus()
                .build();
        // @formatter:on
    }

    private Pod podWithStateTerminated(int exitCode, String message) {
        // @formatter:off
        return new PodBuilder()
                .withNewMetadata().withName("simple-abc").withNamespace("ns").endMetadata()
                .withNewStatus()
                    .withContainerStatuses(new ContainerStatusBuilder()
                            .withName("proxy")
                            .withNewState()
                                .withNewTerminated().withExitCode(exitCode).withMessage(message).endTerminated()
                            .endState()
                            .build())
                .endStatus()
                .build();
        // @formatter:on
    }

    private Pod podWithRunningContainerAndLastStateExitCode(int exitCode) {
        // @formatter:off
        return new PodBuilder()
                .withNewMetadata().withName("simple-abc").withNamespace("ns").endMetadata()
                .withNewStatus()
                    .withContainerStatuses(new ContainerStatusBuilder()
                            .withName("proxy")
                            .withNewState()
                                .withNewRunning().endRunning()
                            .endState()
                            .withNewLastState()
                                .withNewTerminated().withExitCode(exitCode).endTerminated()
                            .endLastState()
                            .build())
                .endStatus()
                .build();
        // @formatter:on
    }

    private io.javaoperatorsdk.operator.api.reconciler.UpdateControl<KafkaProxy> reconcile() {
        return new KafkaProxyReconciler(TEST_CLOCK, SecureConfigInterpolator.DEFAULT_INTERPOLATOR).reconcile(defaultProxy(), context);
    }

    private void assertFilePermissionsCondition(
            io.javaoperatorsdk.operator.api.reconciler.UpdateControl<KafkaProxy> result,
            Condition.Status expectedStatus) {
        var proxy = result.getResource().orElseThrow();
        assertThat(proxy.getStatus().getConditions())
                .filteredOn(c -> c.getType() == Condition.Type.FilePermissionsValid)
                .singleElement()
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(expectedStatus));
    }
}