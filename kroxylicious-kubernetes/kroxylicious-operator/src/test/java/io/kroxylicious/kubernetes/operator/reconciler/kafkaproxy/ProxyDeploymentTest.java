/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator.reconciler.kafkaproxy;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.api.model.Route;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.managed.DefaultManagedWorkflowAndDependentResourceContext;

import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxy;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxyBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaService;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaServiceBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.VirtualKafkaCluster;
import io.kroxylicious.kubernetes.api.v1alpha1.VirtualKafkaClusterBuilder;
import io.kroxylicious.kubernetes.operator.Annotations;
import io.kroxylicious.kubernetes.operator.ProxySecurityModel;
import io.kroxylicious.kubernetes.operator.ResourcesUtil;
import io.kroxylicious.kubernetes.operator.model.ProxyModel;
import io.kroxylicious.kubernetes.operator.model.networking.ProxyNetworkingModel;
import io.kroxylicious.kubernetes.operator.reconciler.virtualkafkacluster.VirtualKafkaClusterReconciler;
import io.kroxylicious.kubernetes.operator.resolver.ClusterResolutionResult;
import io.kroxylicious.kubernetes.operator.resolver.ResolutionResult;
import io.kroxylicious.testing.operator.assertj.OperatorAssertions;

import edu.umd.cs.findbugs.annotations.NonNull;

import static io.kroxylicious.kubernetes.operator.reconciler.kafkaproxy.ProxyDeploymentDependentResource.KROXYLICIOUS_IMAGE_ENV_VAR;
import static io.kroxylicious.kubernetes.operator.resolver.DependencyResolver.EMPTY_RESOLUTION_RESULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProxyDeploymentTest {

    private static final String PROXY_NAME = "kproxy";

    private KafkaProxy kafkaProxy;
    private Context<KafkaProxy> kubernetesContext;
    private KubernetesClient kubernetesClient;
    private VirtualKafkaCluster virtualKafkaCluster;
    private KafkaService kafkaService;
    private DefaultManagedWorkflowAndDependentResourceContext<KafkaProxy> resourceContext;

    @BeforeEach
    void setUp() {
        kafkaProxy = new KafkaProxyBuilder().withNewMetadata().withName(PROXY_NAME).withUid(UUID.randomUUID().toString()).endMetadata()
                .withNewSpec().endSpec().build();
        kubernetesContext = setupContext();
    }

    @Test
    @ClearEnvironmentVariable(key = KROXYLICIOUS_IMAGE_ENV_VAR)
    void operandImageDefault() {
        assertThat(ProxyDeploymentDependentResource.getOperandImage())
                .matches("^quay.io/kroxylicious/proxy:.*");
    }

    @Test
    @SetEnvironmentVariable(key = KROXYLICIOUS_IMAGE_ENV_VAR, value = "quay.io/myorg/kroxylicious:1")
    void operandImageOverrideFromEnvironment() {
        assertThat(ProxyDeploymentDependentResource.getOperandImage())
                .isEqualTo("quay.io/myorg/kroxylicious:1");
    }

    @Test
    void proxyContainerInfrastructureResourcesPreferred() {
        // Given
        var proxyModel = new ProxyModel(EMPTY_RESOLUTION_RESULT, new ProxyNetworkingModel(List.of()), List.of());
        configureProxyModel(proxyModel);
        ProxyDeploymentDependentResource proxyDeploymentDependentResource = new ProxyDeploymentDependentResource();

        // When
        ResourceRequirements requirements = new ResourceRequirementsBuilder().withLimits(Map.of("cpu", Quantity.parse("400m"))).build();
        KafkaProxy proxy = kafkaProxy.edit().editOrNewSpec()
                .editOrNewInfrastructure()
                .editOrNewProxyContainer()
                .withResources(requirements)
                .endProxyContainer()
                .endInfrastructure()
                .endSpec()
                .build();
        Deployment actual = proxyDeploymentDependentResource.desired(proxy, kubernetesContext);

        // Then
        assertThat(actual.getSpec().getTemplate().getSpec().getContainers()).singleElement().satisfies(container -> {
            assertThat(container.getResources()).isEqualTo(requirements);
        });
    }

    @Test
    void shouldSpecifySecCompProfile() {
        // Given
        ProxyDeploymentDependentResource proxyDeploymentDependentResource = new ProxyDeploymentDependentResource();

        // When
        Deployment actual = proxyDeploymentDependentResource.desired(kafkaProxy, kubernetesContext);

        // Then
        assertThat(actual.getSpec().getTemplate().getSpec().getSecurityContext().getSeccompProfile().getType()).isEqualTo("RuntimeDefault");
    }

    @Test
    void shouldSetFsGroupOnPodSecurityContext() {
        // Given
        ProxyDeploymentDependentResource proxyDeploymentDependentResource = new ProxyDeploymentDependentResource();

        // When
        Deployment actual = proxyDeploymentDependentResource.desired(kafkaProxy, kubernetesContext);

        // Then - fsGroup ensures secret volume files are group-owned by the proxy GID
        assertThat(actual.getSpec().getTemplate().getSpec().getSecurityContext().getFsGroup())
                .isEqualTo(ProxySecurityModel.PROXY_CONTAINER_GID);
    }

    @Test
    void shouldSetRunAsGroupOnPodSecurityContext() {
        // Given
        ProxyDeploymentDependentResource proxyDeploymentDependentResource = new ProxyDeploymentDependentResource();

        // When
        Deployment actual = proxyDeploymentDependentResource.desired(kafkaProxy, kubernetesContext);

        // Then - runAsGroup matches fsGroup so the container process can read 0440 secret files
        assertThat(actual.getSpec().getTemplate().getSpec().getSecurityContext().getRunAsGroup())
                .isEqualTo(ProxySecurityModel.PROXY_CONTAINER_GID);
    }

    @Test
    void shouldOmitFsGroupAndRunAsGroupOnOpenShift() {
        // Given - simulate OpenShift by changing the shared client mock
        when(kubernetesClient.supports(Route.class)).thenReturn(true);
        ProxyDeploymentDependentResource proxyDeploymentDependentResource = new ProxyDeploymentDependentResource();

        // When
        Deployment actual = proxyDeploymentDependentResource.desired(kafkaProxy, kubernetesContext);

        // Then - fsGroup and runAsGroup must be absent: OpenShift's restricted SCC enforces
        // namespace-allocated GID ranges, and GID 0 is always supplemental so files owned by
        // root:root with mode 0440 are already readable without a custom fsGroup.
        var secCtx = actual.getSpec().getTemplate().getSpec().getSecurityContext();
        assertThat(secCtx.getFsGroup()).isNull();
        assertThat(secCtx.getRunAsGroup()).isNull();

        // Other security properties must still be present on OpenShift
        assertThat(secCtx.getRunAsNonRoot()).isTrue();
        assertThat(secCtx.getSeccompProfile()).isNotNull()
                .satisfies(p -> assertThat(p.getType()).isEqualTo("RuntimeDefault"));
    }

    @Test
    void shouldSetFsGroupAndRunAsGroupOnPlainKubernetes() {
        // Given - setupContext() already stubs a plain-Kubernetes client (supports(Route) = false)
        ProxyDeploymentDependentResource proxyDeploymentDependentResource = new ProxyDeploymentDependentResource();

        // When
        Deployment actual = proxyDeploymentDependentResource.desired(kafkaProxy, kubernetesContext);

        // Then - fsGroup and runAsGroup cause the kubelet to chown secret volume files to the
        // proxy GID so they are readable via group membership with defaultMode 0440
        var secCtx = actual.getSpec().getTemplate().getSpec().getSecurityContext();
        assertThat(secCtx.getFsGroup()).isEqualTo(ProxySecurityModel.PROXY_CONTAINER_GID);
        assertThat(secCtx.getRunAsGroup()).isEqualTo(ProxySecurityModel.PROXY_CONTAINER_GID);
        assertThat(secCtx.getRunAsNonRoot()).isTrue();
        assertThat(secCtx.getSeccompProfile()).isNotNull()
                .satisfies(p -> assertThat(p.getType()).isEqualTo("RuntimeDefault"));
    }

    @Test
    void shouldConfigureReadinessProbe() {
        // Given
        ProxyDeploymentDependentResource proxyDeploymentDependentResource = new ProxyDeploymentDependentResource();

        // When
        Deployment actual = proxyDeploymentDependentResource.desired(kafkaProxy, kubernetesContext);

        // Then
        assertThat(actual.getSpec().getTemplate().getSpec().getContainers()).singleElement()
                .satisfies(container -> assertThat(container.getReadinessProbe())
                        .isNotNull()
                        .satisfies(probe -> {
                            assertThat(probe.getHttpGet().getPath()).isEqualTo("/livez");
                            assertThat(probe.getHttpGet().getPort().getStrVal()).isEqualTo("management");
                        }));
    }

    @Test
    void shouldAddReferentChecksumAnnotation() {
        // Given
        ProxyDeploymentDependentResource proxyDeploymentDependentResource = new ProxyDeploymentDependentResource();

        // When
        Deployment actual = proxyDeploymentDependentResource.desired(kafkaProxy, kubernetesContext);

        // Then
        OperatorAssertions.assertThat(actual.getSpec().getTemplate().getMetadata())
                .hasAnnotationSatisfying(Annotations.REFERENT_CHECKSUM_ANNOTATION_KEY,
                        value -> assertThat(value).isNotBlank());
    }

    @NonNull
    @SuppressWarnings("unchecked")
    private Context<KafkaProxy> setupContext() {
        virtualKafkaCluster = new VirtualKafkaClusterBuilder().withNewMetadata().withName("vkc").withUid(UUID.randomUUID().toString())
                .withGeneration(3L).endMetadata().build();
        kafkaService = new KafkaServiceBuilder().withNewMetadata().withName(PROXY_NAME).endMetadata().build();

        var proxyModel = new ProxyModel(EMPTY_RESOLUTION_RESULT, new ProxyNetworkingModel(List.of()), List.of(clusterResolutionResultFor(virtualKafkaCluster)));

        Context<KafkaProxy> context = mock(Context.class);
        resourceContext = new DefaultManagedWorkflowAndDependentResourceContext<>(null, kafkaProxy, context);
        configureProxyModel(proxyModel);
        when(context.managedWorkflowAndDependentResourceContext()).thenReturn(resourceContext);

        // Default: plain Kubernetes (not OpenShift). Tests that need OpenShift behaviour
        // call when(kubernetesClient.supports(Route.class)).thenReturn(true) directly.
        kubernetesClient = mock(KubernetesClient.class);
        when(kubernetesClient.supports(Route.class)).thenReturn(false);
        when(context.getClient()).thenReturn(kubernetesClient);

        return context;
    }

    private void configureProxyModel(ProxyModel proxyModel) {
        resourceContext.put(KafkaProxyContext.KEY_CTX,
                new KafkaProxyContext(VirtualKafkaClusterReconciler.newStatusFactory(Clock.systemUTC()), proxyModel, Optional.empty(), List.of(), List.of()));
    }

    @NonNull
    private static LinkedHashMap<String, String> expectedLabels() {
        LinkedHashMap<String, String> expected = new LinkedHashMap<>();
        expected.put("app.kubernetes.io/managed-by", "kroxylicious-operator");
        expected.put("app.kubernetes.io/name", "kroxylicious");
        expected.put("app.kubernetes.io/component", "proxy");
        expected.put("app.kubernetes.io/instance", PROXY_NAME);
        return expected;
    }

    @NonNull
    private ClusterResolutionResult clusterResolutionResultFor(VirtualKafkaCluster virtualKafkaCluster) {
        return new ClusterResolutionResult(virtualKafkaCluster,
                buildResolutionResultFromCluster(kafkaProxy),
                List.of(),
                buildResolutionResultFromCluster(kafkaService),
                List.of());
    }

    @NonNull
    private <T extends HasMetadata> ResolutionResult<T> buildResolutionResultFromCluster(T referent) {
        return new ResolutionResult<>(ResourcesUtil.toLocalRef(virtualKafkaCluster), ResourcesUtil.toLocalRef(referent), referent);
    }
}
