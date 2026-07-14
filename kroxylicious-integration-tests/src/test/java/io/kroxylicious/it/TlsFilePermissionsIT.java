/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import io.kroxylicious.proxy.config.ClusterDefinition;
import io.kroxylicious.proxy.config.ConfigurationBuilder;
import io.kroxylicious.proxy.config.RouteTarget;
import io.kroxylicious.proxy.config.SecurityConfig;
import io.kroxylicious.proxy.config.VirtualClusterBuilder;
import io.kroxylicious.proxy.config.secret.FilePassword;
import io.kroxylicious.proxy.internal.tls.SslContextBuildException;
import io.kroxylicious.proxy.security.FilePermissionConfig;
import io.kroxylicious.proxy.security.FilePermissionValidator;
import io.kroxylicious.proxy.security.FilePermissionValidator.Policy;
import io.kroxylicious.testing.integration.tester.KroxyliciousConfigUtils;
import io.kroxylicious.testing.kafka.api.KafkaCluster;
import io.kroxylicious.testing.kafka.common.Tls;
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;

import static io.kroxylicious.testing.integration.tester.KroxyliciousConfigUtils.defaultPortIdentifiesNodeGatewayBuilder;
import static io.kroxylicious.testing.integration.tester.KroxyliciousTesters.kroxyliciousTester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link FilePermissionValidator} policies (STRICT, RELAXED, DISABLED) are
 * honoured end-to-end - from configuration parsing through to proxy startup success or failure.
 */
@ExtendWith(KafkaClusterExtension.class)
@EnabledOnOs({ OS.LINUX, OS.MAC })
class TlsFilePermissionsIT extends AbstractTlsIT {

    private static final String VIRTUAL_CLUSTER_NAME = "demo";
    private static final String TARGET_CLUSTER_NAME = "target";

    static KafkaCluster cluster;
    static @Tls KafkaCluster tlsCluster;

    @AfterEach
    void resetGlobalPolicy() {
        FilePermissionValidator.setGlobalPolicy(Policy.DISABLED);
    }

    @Test
    void strictPolicyRejectsStartupWhenKeystoreFileIsInsecure() throws Exception {
        // Given - a gateway keystore file made world-readable (0644)
        Path insecureKeystore = certsDirectory.resolve("insecure.p12");
        Files.copy(Path.of(downstreamCertificateGenerator.getKeyStoreLocation()), insecureKeystore);
        Files.setPosixFilePermissions(insecureKeystore, PosixFilePermissions.fromString("rw-r--r--"));

        // @formatter:off
        var builder = baseBuilderWithPolicy(Policy.STRICT)
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName(VIRTUAL_CLUSTER_NAME)
                        .withTarget(new RouteTarget(TARGET_CLUSTER_NAME, null))
                        .addToGateways(defaultPortIdentifiesNodeGatewayBuilder(PROXY_ADDRESS)
                                .withNewTls()
                                    .withNewKeyStoreKey()
                                        .withStoreFile(insecureKeystore.toString())
                                        .withNewInlinePasswordStoreProvider(downstreamCertificateGenerator.getPassword())
                                    .endKeyStoreKey()
                                .endTls()
                                .build())
                        .build());
        // @formatter:on

        // When / Then - proxy fails to start; the permission violation surfaces as SslContextBuildException
        assertThatThrownBy(() -> {
            try (var ignored = kroxyliciousTester(builder)) {
                return; // suppress empty-try-block warning; exception is expected before body runs
            }
        })
                .isInstanceOf(SslContextBuildException.class)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too open");
    }

    @Test
    void strictPolicyRejectsStartupWhenPasswordFileIsInsecure() throws Exception {
        // Given - secure keystore (0600) but a FilePassword pointing to a group-readable file (0640)
        Path secureKeystore = certsDirectory.resolve("secure.p12");
        Files.copy(Path.of(downstreamCertificateGenerator.getKeyStoreLocation()), secureKeystore);
        Files.setPosixFilePermissions(secureKeystore, PosixFilePermissions.fromString("rw-------"));

        Path insecurePassFile = certsDirectory.resolve("password.txt");
        Files.writeString(insecurePassFile, downstreamCertificateGenerator.getPassword());
        Files.setPosixFilePermissions(insecurePassFile, PosixFilePermissions.fromString("rw-r-----"));

        // @formatter:off
        var builder = baseBuilderWithPolicy(Policy.STRICT)
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName(VIRTUAL_CLUSTER_NAME)
                        .withTarget(new RouteTarget(TARGET_CLUSTER_NAME, null))
                        .addToGateways(defaultPortIdentifiesNodeGatewayBuilder(PROXY_ADDRESS)
                                .withNewTls()
                                    .withNewKeyStoreKey()
                                        .withStoreFile(secureKeystore.toString())
                                        .withStorePasswordProvider(new FilePassword(insecurePassFile.toString()))
                                    .endKeyStoreKey()
                                .endTls()
                                .build())
                        .build());
        // @formatter:on

        // When / Then - proxy fails to start; the permission violation on the password file is reported
        assertThatThrownBy(() -> {
            try (var ignored = kroxyliciousTester(builder)) {
                return; // suppress empty-try-block warning; exception is expected before body runs
            }
        })
                .isInstanceOf(SslContextBuildException.class)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too open")
                .hasMessageContaining("password file");
    }

    @Test
    void strictPolicyPermitsStartupWhenFilesAreSecure() throws Exception {
        // Given - keystore with owner-only permissions (0600)
        Path secureKeystore = certsDirectory.resolve("secure.p12");
        Files.copy(Path.of(downstreamCertificateGenerator.getKeyStoreLocation()), secureKeystore);
        Files.setPosixFilePermissions(secureKeystore, PosixFilePermissions.fromString("rw-------"));

        // @formatter:off
        var builder = baseBuilderWithPolicy(Policy.STRICT)
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName(VIRTUAL_CLUSTER_NAME)
                        .withTarget(new RouteTarget(TARGET_CLUSTER_NAME, null))
                        .addToGateways(defaultPortIdentifiesNodeGatewayBuilder(PROXY_ADDRESS)
                                .withNewTls()
                                    .withNewKeyStoreKey()
                                        .withStoreFile(secureKeystore.toString())
                                        .withNewInlinePasswordStoreProvider(downstreamCertificateGenerator.getPassword())
                                    .endKeyStoreKey()
                                .endTls()
                                .build())
                        .build());
        // @formatter:on

        // When / Then - proxy starts successfully; a TLS client can connect and operate
        try (var tester = kroxyliciousTester(builder);
                var admin = tester.admin(VIRTUAL_CLUSTER_NAME, tlsAdminClientConfig())) {
            assertThat(admin.describeCluster().nodes()).succeedsWithin(10, TimeUnit.SECONDS).isNotNull();
        }
    }

    @Test
    void strictPolicyRejectsStartupWhenUpstreamTruststoreIsInsecure() throws Exception {
        // Given - the broker truststore made world-readable (0644)
        var brokerTruststore = (String) tlsCluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG);
        var brokerTruststorePassword = (String) tlsCluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG);

        Path insecureTruststore = certsDirectory.resolve("broker-trust.jks");
        Files.copy(Path.of(brokerTruststore), insecureTruststore);
        Files.setPosixFilePermissions(insecureTruststore, PosixFilePermissions.fromString("rw-r--r--"));

        // @formatter:off
        var builder = KroxyliciousConfigUtils.baseConfigurationBuilder()
                .withSecurity(new SecurityConfig(new FilePermissionConfig(Policy.STRICT)))
                .addNewClusterDefinition()
                    .withName(TARGET_CLUSTER_NAME)
                    .withBootstrapServers(tlsCluster.getBootstrapServers())
                    .withNewTls()
                        .withNewTrustStoreTrust()
                            .withStoreFile(insecureTruststore.toString())
                            .withNewInlinePasswordStoreProvider(brokerTruststorePassword)
                        .endTrustStoreTrust()
                    .endTls()
                .endClusterDefinition()
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName(VIRTUAL_CLUSTER_NAME)
                        .withTarget(new RouteTarget(TARGET_CLUSTER_NAME, null))
                        .addToGateways(defaultPortIdentifiesNodeGatewayBuilder(PROXY_ADDRESS).build())
                        .build());
        // @formatter:on

        // When / Then - proxy fails to start; upstream truststore permission violation
        assertThatThrownBy(() -> {
            try (var ignored = kroxyliciousTester(builder)) {
                return; // suppress empty-try-block warning; exception is expected before body runs
            }
        })
                .isInstanceOf(SslContextBuildException.class)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too open");
    }

    @Test
    void relaxedPolicyAcceptsGroupReadableUpstreamTruststoreFile() throws Exception {
        // Given - broker truststore with owner+group read (0440); the Kubernetes fsGroup scenario for upstream TLS
        var brokerTruststore = (String) tlsCluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG);
        var brokerTruststorePassword = (String) tlsCluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG);

        Path groupReadableTruststore = certsDirectory.resolve("broker-trust.jks");
        Files.copy(Path.of(brokerTruststore), groupReadableTruststore);
        Files.setPosixFilePermissions(groupReadableTruststore, PosixFilePermissions.fromString("r--r-----"));

        // @formatter:off
        var builder = KroxyliciousConfigUtils.baseConfigurationBuilder()
                .withSecurity(new SecurityConfig(new FilePermissionConfig(Policy.RELAXED)))
                .addNewClusterDefinition()
                    .withName(TARGET_CLUSTER_NAME)
                    .withBootstrapServers(tlsCluster.getBootstrapServers())
                    .withNewTls()
                        .withNewTrustStoreTrust()
                            .withStoreFile(groupReadableTruststore.toString())
                            .withNewInlinePasswordStoreProvider(brokerTruststorePassword)
                        .endTrustStoreTrust()
                    .endTls()
                .endClusterDefinition()
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName(VIRTUAL_CLUSTER_NAME)
                        .withTarget(new RouteTarget(TARGET_CLUSTER_NAME, null))
                        .addToGateways(defaultPortIdentifiesNodeGatewayBuilder(PROXY_ADDRESS).build())
                        .build());
        // @formatter:on

        // When / Then - proxy starts successfully; group-readable upstream truststore accepted by RELAXED
        try (var tester = kroxyliciousTester(builder);
                var admin = tester.admin(VIRTUAL_CLUSTER_NAME)) {
            assertThat(admin.describeCluster().nodes()).succeedsWithin(10, TimeUnit.SECONDS).isNotNull();
        }
    }

    @Test
    void strictPolicyRejectsStartupWhenUpstreamTruststorePasswordFileIsInsecure() throws Exception {
        // Given - secure upstream truststore (0600) but its FilePassword-backed password file is group-readable (0640)
        var brokerTruststore = (String) tlsCluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG);
        var brokerTruststorePassword = (String) tlsCluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG);

        Path secureTruststore = certsDirectory.resolve("broker-trust.jks");
        Files.copy(Path.of(brokerTruststore), secureTruststore);
        Files.setPosixFilePermissions(secureTruststore, PosixFilePermissions.fromString("rw-------"));

        Path insecurePassFile = certsDirectory.resolve("truststore-password.txt");
        Files.writeString(insecurePassFile, brokerTruststorePassword);
        Files.setPosixFilePermissions(insecurePassFile, PosixFilePermissions.fromString("rw-r-----"));

        // @formatter:off
        var builder = KroxyliciousConfigUtils.baseConfigurationBuilder()
                .withSecurity(new SecurityConfig(new FilePermissionConfig(Policy.STRICT)))
                .addNewClusterDefinition()
                    .withName(TARGET_CLUSTER_NAME)
                    .withBootstrapServers(tlsCluster.getBootstrapServers())
                    .withNewTls()
                        .withNewTrustStoreTrust()
                            .withStoreFile(secureTruststore.toString())
                            .withStorePasswordProvider(new FilePassword(insecurePassFile.toString()))
                        .endTrustStoreTrust()
                    .endTls()
                .endClusterDefinition()
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName(VIRTUAL_CLUSTER_NAME)
                        .withTarget(new RouteTarget(TARGET_CLUSTER_NAME, null))
                        .addToGateways(defaultPortIdentifiesNodeGatewayBuilder(PROXY_ADDRESS).build())
                        .build());
        // @formatter:on

        // When / Then - proxy fails to start; upstream truststore password file permission violation
        assertThatThrownBy(() -> {
            try (var ignored = kroxyliciousTester(builder)) {
                return; // suppress empty-try-block warning; exception is expected before body runs
            }
        })
                .isInstanceOf(SslContextBuildException.class)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too open")
                .hasMessageContaining("password file");
    }

    @Test
    void relaxedPolicyAcceptsGroupReadableKeystoreFile() throws Exception {
        // Given - keystore with owner+group read (0440); the Kubernetes fsGroup scenario
        Path groupReadableKeystore = certsDirectory.resolve("group-readable.p12");
        Files.copy(Path.of(downstreamCertificateGenerator.getKeyStoreLocation()), groupReadableKeystore);
        Files.setPosixFilePermissions(groupReadableKeystore, PosixFilePermissions.fromString("r--r-----"));

        // @formatter:off
        var builder = baseBuilderWithPolicy(Policy.RELAXED)
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName(VIRTUAL_CLUSTER_NAME)
                        .withTarget(new RouteTarget(TARGET_CLUSTER_NAME, null))
                        .addToGateways(defaultPortIdentifiesNodeGatewayBuilder(PROXY_ADDRESS)
                                .withNewTls()
                                    .withNewKeyStoreKey()
                                        .withStoreFile(groupReadableKeystore.toString())
                                        .withNewInlinePasswordStoreProvider(downstreamCertificateGenerator.getPassword())
                                    .endKeyStoreKey()
                                .endTls()
                                .build())
                        .build());
        // @formatter:on

        // When / Then - proxy starts successfully; group-readable files are accepted by RELAXED
        try (var tester = kroxyliciousTester(builder);
                var admin = tester.admin(VIRTUAL_CLUSTER_NAME, tlsAdminClientConfig())) {
            assertThat(admin.describeCluster().nodes()).succeedsWithin(10, TimeUnit.SECONDS).isNotNull();
        }
    }

    @Test
    void relaxedPolicyRejectsWorldReadableKeystoreFile() throws Exception {
        // Given - keystore with world-readable permissions (0644)
        Path worldReadableKeystore = certsDirectory.resolve("world-readable.p12");
        Files.copy(Path.of(downstreamCertificateGenerator.getKeyStoreLocation()), worldReadableKeystore);
        Files.setPosixFilePermissions(worldReadableKeystore, PosixFilePermissions.fromString("rw-r--r--"));

        // @formatter:off
        var builder = baseBuilderWithPolicy(Policy.RELAXED)
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName(VIRTUAL_CLUSTER_NAME)
                        .withTarget(new RouteTarget(TARGET_CLUSTER_NAME, null))
                        .addToGateways(defaultPortIdentifiesNodeGatewayBuilder(PROXY_ADDRESS)
                                .withNewTls()
                                    .withNewKeyStoreKey()
                                        .withStoreFile(worldReadableKeystore.toString())
                                        .withNewInlinePasswordStoreProvider(downstreamCertificateGenerator.getPassword())
                                    .endKeyStoreKey()
                                .endTls()
                                .build())
                        .build());
        // @formatter:on

        // When / Then - proxy fails to start; world-readable files are rejected by RELAXED
        assertThatThrownBy(() -> {
            try (var ignored = kroxyliciousTester(builder)) {
                return; // suppress empty-try-block warning; exception is expected before body runs
            }
        })
                .isInstanceOf(SslContextBuildException.class)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too open");
    }

    @Test
    void disabledPolicyPermitsStartupWithInsecureKeystoreFile() throws Exception {
        // Given - keystore with world-readable permissions (0644) and DISABLED policy
        Path insecureKeystore = certsDirectory.resolve("insecure.p12");
        Files.copy(Path.of(downstreamCertificateGenerator.getKeyStoreLocation()), insecureKeystore);
        Files.setPosixFilePermissions(insecureKeystore, PosixFilePermissions.fromString("rw-r--r--"));

        // @formatter:off
        var builder = baseBuilderWithPolicy(Policy.DISABLED)
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName(VIRTUAL_CLUSTER_NAME)
                        .withTarget(new RouteTarget(TARGET_CLUSTER_NAME, null))
                        .addToGateways(defaultPortIdentifiesNodeGatewayBuilder(PROXY_ADDRESS)
                                .withNewTls()
                                    .withNewKeyStoreKey()
                                        .withStoreFile(insecureKeystore.toString())
                                        .withNewInlinePasswordStoreProvider(downstreamCertificateGenerator.getPassword())
                                    .endKeyStoreKey()
                                .endTls()
                                .build())
                        .build());
        // @formatter:on

        // When / Then - proxy starts and operates normally; insecure file only produces a warning log
        try (var tester = kroxyliciousTester(builder);
                var admin = tester.admin(VIRTUAL_CLUSTER_NAME, tlsAdminClientConfig())) {
            assertThat(admin.describeCluster().nodes()).succeedsWithin(10, TimeUnit.SECONDS).isNotNull();
        }
    }

    private ConfigurationBuilder baseBuilderWithPolicy(Policy policy) {
        return KroxyliciousConfigUtils.baseConfigurationBuilder()
                .withSecurity(new SecurityConfig(new FilePermissionConfig(policy)))
                .addToClusterDefinitions(new ClusterDefinition(TARGET_CLUSTER_NAME, cluster.getBootstrapServers(), null));
    }

    private Map<String, Object> tlsAdminClientConfig() {
        return Map.of(
                CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SSL.name,
                SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, clientTrustStore.toAbsolutePath().toString(),
                SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, downstreamCertificateGenerator.getPassword());
    }
}