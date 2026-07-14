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
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;

import static io.kroxylicious.testing.integration.tester.KroxyliciousConfigUtils.defaultPortIdentifiesNodeGatewayBuilder;
import static io.kroxylicious.testing.integration.tester.KroxyliciousTesters.kroxyliciousTester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link FilePermissionValidator} STRICT policy rejects insecure TLS file
 * permissions end-to-end — from configuration parsing through to proxy startup failure.
 */
@ExtendWith(KafkaClusterExtension.class)
@EnabledOnOs({ OS.LINUX, OS.MAC })
class TlsFilePermissionsIT extends AbstractTlsIT {

    static KafkaCluster cluster;

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
        var builder = KroxyliciousConfigUtils.baseConfigurationBuilder()
                .withSecurity(new SecurityConfig(new FilePermissionConfig(Policy.STRICT)))
                .addToClusterDefinitions(new ClusterDefinition("target", cluster.getBootstrapServers(), null))
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName("demo")
                        .withTarget(new RouteTarget("target", null))
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
        var builder = KroxyliciousConfigUtils.baseConfigurationBuilder()
                .withSecurity(new SecurityConfig(new FilePermissionConfig(Policy.STRICT)))
                .addToClusterDefinitions(new ClusterDefinition("target", cluster.getBootstrapServers(), null))
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName("demo")
                        .withTarget(new RouteTarget("target", null))
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
        var builder = KroxyliciousConfigUtils.baseConfigurationBuilder()
                .withSecurity(new SecurityConfig(new FilePermissionConfig(Policy.STRICT)))
                .addToClusterDefinitions(new ClusterDefinition("target", cluster.getBootstrapServers(), null))
                .addToVirtualClusters(new VirtualClusterBuilder()
                        .withName("demo")
                        .withTarget(new RouteTarget("target", null))
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
                var admin = tester.admin("demo",
                        Map.of(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SSL.name,
                                SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, clientTrustStore.toAbsolutePath().toString(),
                                SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, downstreamCertificateGenerator.getPassword()))) {
            assertThat(admin.describeCluster().nodes()).succeedsWithin(10, TimeUnit.SECONDS).isNotNull();
        }
    }
}
