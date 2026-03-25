/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaFluent;
import io.fabric8.kubernetes.client.utils.KubernetesResourceUtil;
import io.fabric8.openshift.api.model.Route;

import io.kroxylicious.kubernetes.operator.model.RouteHostDetails;
import io.kroxylicious.proxy.tag.VisibleForTesting;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Used to read/write annotations to/from Kubernetes resources and associated builders. This
 * class aims to encapsulate annotation logic so that we can make breaking changes to the annotation
 * keys or data, without changing client interfaces.
 */
public class Annotations {

    @VisibleForTesting
    static final String BOOTSTRAP_SERVERS_ANNOTATION_KEY = "kroxylicious.io/bootstrap-servers";

    @VisibleForTesting
    public static final String MANAGED_ROUTE_KEY = "kroxylicious.io/managed-route";

    @VisibleForTesting
    public static final String REFERENT_CHECKSUM_ANNOTATION_KEY = "kroxylicious.io/referent-checksum";

    private Annotations() {
    }

    /**
     * Adds a `kroxylicious.io/bootstrap-servers`annotation to the supplied metadata fluent
     * @param meta the Metadata fluent builder to add the annotation to
     * @param clusterIngressBootstrapServers the bootstrap servers to serialize into the annotation value
     */
    public static void annotateWithBootstrapServers(ObjectMetaFluent<?> meta, Set<ClusterIngressBootstrapServers> clusterIngressBootstrapServers) {
        Objects.requireNonNull(meta);
        Objects.requireNonNull(clusterIngressBootstrapServers);
        if (clusterIngressBootstrapServers.isEmpty()) {
            return;
        }
        meta.addToAnnotations(BOOTSTRAP_SERVERS_ANNOTATION_KEY, toAnnotation(clusterIngressBootstrapServers));
    }

    /**
     * Adds a `kroxylicious.io/bootstrap-servers`annotation to the supplied metadata fluent
     * @param meta the Metadata fluent builder to add the annotation to
     * @param managedRoute the managedd route to serialize into the annotation value
     */
    public static void annotateWithManagedRoute(ObjectMetaFluent<?> meta, ManagedRoute managedRoute) {
        Objects.requireNonNull(meta);
        Objects.requireNonNull(managedRoute);
        meta.addToAnnotations(MANAGED_ROUTE_KEY, toAnnotation(managedRoute));
    }

    /**
     * Adds a `kroxylicious.io/referent-checksum`annotation to the supplied metadata fluent
     * @param meta the Metadata fluent builder to add the annotation to
     * @param referentChecksum the checksum value to serialize into the annotation value
     */
    public static void annotateWithReferentChecksum(ObjectMetaFluent<?> meta, String referentChecksum) {
        Objects.requireNonNull(meta);
        Objects.requireNonNull(referentChecksum);
        if (referentChecksum.isEmpty()) {
            return;
        }
        meta.addToAnnotations(REFERENT_CHECKSUM_ANNOTATION_KEY, referentChecksum);
    }

    /**
     * Mutates a HasMetadata, adding an `kroxylicious.io/referent-checksum`annotation. Metadata and Annotations
     * objects are created on the HasMetadata if they are null.
     * @param hasMetadata the Metadata fluent builder to add the annotation to
     * @param referentChecksum the checksum value to serialize into the annotation value
     */
    public static void annotateWithReferentChecksum(HasMetadata hasMetadata, String referentChecksum) {
        Objects.requireNonNull(hasMetadata);
        Objects.requireNonNull(referentChecksum);
        if (referentChecksum.isEmpty()) {
            return;
        }
        Map<String, String> annotations = KubernetesResourceUtil.getOrCreateAnnotations(hasMetadata);
        annotations.put(REFERENT_CHECKSUM_ANNOTATION_KEY, referentChecksum);
    }

    /**
     * Read bootstrap servers from HasMetadata, extracting them from an `kroxylicious.io/bootstrap-servers`
     * annotation if present.
     * @param hasMetadata the resource to extract the bootstrap servers from
     * @return the bootstrap servers from the metadata if the annotation is present, else an empty Set
     */
    public static Set<ClusterIngressBootstrapServers> readBootstrapServersFrom(HasMetadata hasMetadata) {
        Map<String, String> annotations = annotations(hasMetadata);
        if (!annotations.containsKey(BOOTSTRAP_SERVERS_ANNOTATION_KEY)) {
            return Set.of();
        }
        else {
            return bootstrapServersFromAnnotation(annotations.get(BOOTSTRAP_SERVERS_ANNOTATION_KEY));
        }
    }

    /**
     * Read managed route details from Route, extracting them from an `kroxylicious.io/managed-route`
     * annotation if present.
     * @param route the route to extract the managed route from
     * @return the route from the metadata if the annotation is present, else empty
     */
    public static Optional<ManagedRoute> readManagedRouteFrom(Route route) {
        Map<String, String> annotations = annotations(route);
        if (!annotations.containsKey(MANAGED_ROUTE_KEY)) {
            return Optional.empty();
        }
        else {
            return Optional.of(managedRouteFromAnnotation(annotations.get(MANAGED_ROUTE_KEY)));
        }
    }

    /**
     * Read referent checksum annotation from ObjectMeta, extracting them from an `kroxylicious.io/referent-checksum`
     * annotation if present.
     * @param hasMetadata the resource to extract the referent checksum from
     * @return optional completed with the referent checksum if present, else empty
     */
    public static Optional<String> readReferentChecksumFrom(HasMetadata hasMetadata) {
        Objects.requireNonNull(hasMetadata);
        Map<String, String> annotations = annotations(hasMetadata);
        return Optional.ofNullable(annotations.get(REFERENT_CHECKSUM_ANNOTATION_KEY));
    }

    @NonNull
    private static Map<String, String> annotations(HasMetadata hasMetadata) {
        return Optional.ofNullable(hasMetadata.getMetadata())
                .map(ObjectMeta::getAnnotations)
                .orElse(Map.of());
    }

    /**
     * Describes a bootstrapServers string that we expect clients to use to connect with for a specific VirtualKafkaCluster
     * and KafkaProxyIngress combination.
     * @param clusterName VirtualKafkaCluster name
     * @param ingressName KafkaProxyIngress name
     * @param bootstrapServers client facing bootstrap servers
     */
    @JsonPropertyOrder({ "clusterName", "ingressName", "bootstrapServers" })
    public record ClusterIngressBootstrapServers(String clusterName, String ingressName, String bootstrapServers) {
        public ClusterIngressBootstrapServers {
            Objects.requireNonNull(clusterName);
            Objects.requireNonNull(ingressName);
            Objects.requireNonNull(bootstrapServers);
        }

        public boolean matchesIngress(String ingressName, String clusterName) {
            return this.ingressName.equals(ingressName) && this.clusterName.equals(clusterName);
        }
    }

    /**
     * Describes a bootstrapServers string that we expect clients to use to connect with for a specific VirtualKafkaCluster
     * and KafkaProxyIngress combination.
     * @param clusterName VirtualKafkaCluster name
     * @param ingressName KafkaProxyIngress name
     * @param routeTarget target of the route
     */
    @JsonPropertyOrder({ "clusterName", "ingressName", "bootstrapServers" })
    public record ManagedRoute(String clusterName, String ingressName, RouteHostDetails.RouteFor routeTarget) {
        public ManagedRoute {
            Objects.requireNonNull(clusterName);
            Objects.requireNonNull(ingressName);
            Objects.requireNonNull(routeTarget);
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @VisibleForTesting
    @JsonPropertyOrder({ "version", "bootstrapServers" })
    record BootstrapWrapper(String version, List<ClusterIngressBootstrapServers> bootstrapServers) {
        BootstrapWrapper {
            Objects.requireNonNull(version);
            Objects.requireNonNull(bootstrapServers);
        }
    }

    @VisibleForTesting
    @JsonPropertyOrder({ "version", "managedRout" })
    record ManagedRouteWrapper(String version, ManagedRoute managedRoute) {
        ManagedRouteWrapper {
            Objects.requireNonNull(version);
            Objects.requireNonNull(managedRoute);
        }
    }

    private static String toAnnotation(Set<ClusterIngressBootstrapServers> clusterIngressBootstrapServers) {
        List<ClusterIngressBootstrapServers> list = clusterIngressBootstrapServers.stream()
                .sorted(Comparator.comparing(ClusterIngressBootstrapServers::clusterName).thenComparing(ClusterIngressBootstrapServers::ingressName).thenComparing(
                        ClusterIngressBootstrapServers::bootstrapServers))
                .toList();
        BootstrapWrapper bootstrapWrapper = new BootstrapWrapper("0.13.0", list);
        try {
            return OBJECT_MAPPER.writeValueAsString(bootstrapWrapper);
        }
        catch (JsonProcessingException e) {
            throw new AnnotationSerializationException(e);
        }
    }

    private static String toAnnotation(ManagedRoute managedRoute) {
        ManagedRouteWrapper bootstrapWrapper = new ManagedRouteWrapper("0.20.0", managedRoute);
        try {
            return OBJECT_MAPPER.writeValueAsString(bootstrapWrapper);
        }
        catch (JsonProcessingException e) {
            throw new AnnotationSerializationException(e);
        }
    }

    private static Set<ClusterIngressBootstrapServers> bootstrapServersFromAnnotation(String bootstrapServers) {
        try {
            BootstrapWrapper bootstrapWrapper = OBJECT_MAPPER.readValue(bootstrapServers, BootstrapWrapper.class);
            return new HashSet<>(bootstrapWrapper.bootstrapServers());
        }
        catch (JsonProcessingException e) {
            throw new AnnotationSerializationException(e);
        }
    }

    private static ManagedRoute managedRouteFromAnnotation(String managedRoute) {
        try {
            ManagedRouteWrapper bootstrapWrapper = OBJECT_MAPPER.readValue(managedRoute, ManagedRouteWrapper.class);
            return bootstrapWrapper.managedRoute();
        }
        catch (JsonProcessingException e) {
            throw new AnnotationSerializationException(e);
        }
    }
}
