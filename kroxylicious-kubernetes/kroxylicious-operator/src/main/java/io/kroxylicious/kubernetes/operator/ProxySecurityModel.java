/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.api.model.Route;

/**
 * Security model constants and platform detection for the Kroxylicious proxy deployment.
 * <p>
 * Secret volumes are always mounted with {@code defaultMode} {@value #SECRET_VOLUME_DEFAULT_MODE}
 * (octal {@code 0440}): owner+group read-only, no world access.  How the container process can
 * then read those files depends on the platform:
 * <ul>
 *   <li><b>Plain Kubernetes:</b> the operator sets {@code fsGroup} and {@code runAsGroup} to
 *       {@value #PROXY_CONTAINER_GID} so the kubelet chowns volume files to that GID and the
 *       container process can read them via group membership.</li>
 *   <li><b>OpenShift:</b> OpenShift automatically adds GID 0 as a supplemental group to every
 *       container.  Secret volume files are owned by {@code root:root} (GID 0) with mode
 *       {@code 0440}, so the container can read them via the automatic GID 0 membership.
 *       No {@code fsGroup} or {@code runAsGroup} are needed, avoiding conflicts with
 *       OpenShift's namespace-allocated GID ranges enforced by the restricted SCC.</li>
 * </ul>
 */
public final class ProxySecurityModel {

    private ProxySecurityModel() {
    }

    /**
     * GID of the {@code kroxylicious} user in the proxy container image.  The Dockerfile
     * creates the group with the same numeric value as the UID ({@code groupadd -g $CONTAINER_USER_UID}),
     * so UID and GID are equal.  Used as {@code fsGroup} and {@code runAsGroup} on plain
     * Kubernetes deployments; not used on OpenShift (see class Javadoc).
     */
    public static final long PROXY_CONTAINER_GID = 185L;

    /**
     * Default file permission mode for Kubernetes Secret volumes: {@code 0440} octal
     * (owner+group read-only, no world access).
     */
    public static final int SECRET_VOLUME_DEFAULT_MODE = 0440;

    /**
     * Returns {@code true} if the cluster is OpenShift, detected by the presence of the
     * {@code route.openshift.io} API.
     *
     * @param client the Kubernetes client to use for API discovery
     * @return {@code true} if running on OpenShift, {@code false} otherwise
     */
    public static boolean isOpenShift(KubernetesClient client) {
        return client.supports(Route.class);
    }
}