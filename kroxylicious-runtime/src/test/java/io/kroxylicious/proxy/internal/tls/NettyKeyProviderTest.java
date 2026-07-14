/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.tls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.stream.Stream;

import javax.crypto.BadPaddingException;

import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.kroxylicious.proxy.config.secret.FilePassword;
import io.kroxylicious.proxy.config.secret.InlinePassword;
import io.kroxylicious.proxy.config.secret.PasswordProvider;
import io.kroxylicious.proxy.config.tls.KeyPair;
import io.kroxylicious.proxy.config.tls.KeyStore;
import io.kroxylicious.proxy.security.FilePermissionValidator.Policy;

import static io.kroxylicious.proxy.internal.tls.TlsTestConstants.BADPASS;
import static io.kroxylicious.proxy.internal.tls.TlsTestConstants.JKS;
import static io.kroxylicious.proxy.internal.tls.TlsTestConstants.KEYPASS;
import static io.kroxylicious.proxy.internal.tls.TlsTestConstants.KEYPASS_FILE_PASSWORD;
import static io.kroxylicious.proxy.internal.tls.TlsTestConstants.KEYSTORE_FILE_PASSWORD;
import static io.kroxylicious.proxy.internal.tls.TlsTestConstants.NOT_EXIST;
import static io.kroxylicious.proxy.internal.tls.TlsTestConstants.PEM;
import static io.kroxylicious.proxy.internal.tls.TlsTestConstants.PKCS_12;
import static io.kroxylicious.proxy.internal.tls.TlsTestConstants.STOREPASS;
import static io.kroxylicious.proxy.internal.tls.TlsTestConstants.getResourceLocationOnFilesystem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NettyKeyProviderTest {

    private static Stream<Arguments> withKeyStore() {
        return Stream.of(
                Arguments.of("Platform Default Store JKS", null, "server.jks", STOREPASS, null),
                Arguments.of("JKS store=key", JKS, "server.jks", STOREPASS, null),
                Arguments.of("JKS store=key explicit", JKS, "server.jks", STOREPASS, STOREPASS),
                Arguments.of("JKS store!=key", JKS, "server_diff_keypass.jks", STOREPASS, KEYPASS),
                Arguments.of("PKCS12", PKCS_12, "server.p12", STOREPASS, null),
                Arguments.of("Combined key/crt PEM passed as keyStore (KIP-651)", PEM, "server_key_crt.pem", null, null),
                Arguments.of("Combined key/crt PEM passed as keyStore (KIP-651) with encrypted key", PEM, "server_crt_encrypted_key.pem", null, KEYPASS),
                Arguments.of("JKS keystore from file", JKS, "server_diff_keypass.jks", KEYSTORE_FILE_PASSWORD, KEYPASS_FILE_PASSWORD));
    }

    public static Stream<Arguments> serverWithKeyStore() {
        return withKeyStore();
    }

    public static Stream<Arguments> clientWithKeyStore() {
        return withKeyStore();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void serverWithKeyStore(String name,
                            String storeType,
                            String storeFile, PasswordProvider storePassword, PasswordProvider keyPassword)
            throws Exception {
        var keyStore = new NettyKeyProvider(new KeyStore(getResourceLocationOnFilesystem(storeFile), storePassword, keyPassword, storeType));

        var sslContext = keyStore.forServer().build();
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isServer()).isTrue();
    }

    @Test
    void serverKeyStoreFileNotFound() {
        var keyStore = new NettyKeyProvider(new KeyStore(NOT_EXIST, null, null, null));

        assertThatCode(keyStore::forServer).hasRootCauseInstanceOf(IOException.class).hasMessageContaining(NOT_EXIST);
    }

    @Test
    void serverKeyStoreIncorrectPassword() {
        var keyStore = new NettyKeyProvider(new KeyStore(getResourceLocationOnFilesystem("server.jks"),
                BADPASS,
                null,
                null));

        assertThatCode(keyStore::forServer).hasRootCauseInstanceOf(UnrecoverableKeyException.class);
    }

    @Test
    void serverKeyStoreIncorrectKeyPassword() {
        var keyStore = new NettyKeyProvider(new KeyStore(getResourceLocationOnFilesystem("server_diff_keypass.jks"),
                STOREPASS,
                BADPASS,
                null));

        assertThatCode(keyStore::forServer).hasRootCauseInstanceOf(UnrecoverableKeyException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void clientWithKeyStore(String name,
                            String storeType,
                            String storeFile, PasswordProvider storePassword, PasswordProvider keyPassword)
            throws Exception {
        var keyStore = new NettyKeyProvider(new KeyStore(getResourceLocationOnFilesystem(storeFile), storePassword, keyPassword, storeType));

        var sslContext = keyStore.forClient().build();
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();
    }

    @Test
    void clientKeyStoreFileNotFound() {
        var keyStore = new NettyKeyProvider(new KeyStore(NOT_EXIST, null, null, null));

        assertThatCode(keyStore::forClient).hasRootCauseInstanceOf(IOException.class).hasMessageContaining(NOT_EXIST);
    }

    @Test
    void clientKeyStoreIncorrectPassword() {
        var keyStore = new NettyKeyProvider(new KeyStore(getResourceLocationOnFilesystem("server.jks"),
                BADPASS,
                null,
                null));

        assertThatCode(keyStore::forClient).hasRootCauseInstanceOf(UnrecoverableKeyException.class);
    }

    @Test
    void clientKeyStoreIncorrectKeyPassword() {
        var keyStore = new NettyKeyProvider(new KeyStore(getResourceLocationOnFilesystem("server_diff_keypass.jks"),
                STOREPASS,
                BADPASS,
                null));

        assertThatCode(keyStore::forClient).hasRootCauseInstanceOf(UnrecoverableKeyException.class);
    }

    @Test
    void serverKeyPair() throws Exception {
        var keyPair = new NettyKeyProvider(
                new KeyPair(TlsTestConstants.getResourceLocationOnFilesystem("server.key"), TlsTestConstants.getResourceLocationOnFilesystem("server.crt"), null));
        var sslContext = keyPair.forServer().build();
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isServer()).isTrue();
    }

    @Test
    void serverKeyPairKeyProtectedWithPassword() throws Exception {
        var keyPair = new NettyKeyProvider(new KeyPair(TlsTestConstants.getResourceLocationOnFilesystem("server_encrypted.key"),
                TlsTestConstants.getResourceLocationOnFilesystem("server.crt"), new InlinePassword("keypass")));

        var sslContext = keyPair.forServer().build();
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isServer()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void keyPairIncorrectKeyPassword(boolean forServer) {
        doFailingKeyPairTest(TlsTestConstants.getResourceLocationOnFilesystem("server_encrypted.key"),
                TlsTestConstants.getResourceLocationOnFilesystem("server.crt"), BADPASS, forServer)
                .hasRootCauseInstanceOf(BadPaddingException.class)
                .hasMessageContaining("server.crt")
                .hasMessageContaining("server_encrypted.key");
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void keyPairCertificateNotFound(boolean forServer) {
        doFailingKeyPairTest(TlsTestConstants.getResourceLocationOnFilesystem("server.key"), NOT_EXIST, null, forServer)
                .hasRootCauseInstanceOf(CertificateException.class)
                .hasMessageContaining(NOT_EXIST);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void keyPairKeyNotFound(boolean forServer) {
        doFailingKeyPairTest(NOT_EXIST, TlsTestConstants.getResourceLocationOnFilesystem("server.crt"), null, forServer)
                .hasRootCauseInstanceOf(KeyException.class)
                .hasMessageContaining(NOT_EXIST);
    }

    @Test
    void clientKeyPair() throws Exception {
        var keyPair = new NettyKeyProvider(
                new KeyPair(TlsTestConstants.getResourceLocationOnFilesystem("server.key"), TlsTestConstants.getResourceLocationOnFilesystem("server.crt"), null));
        var sslContext = keyPair.forClient().build();
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();
    }

    @Test
    void clientKeyPairKeyProtectedWithPassword() throws Exception {
        var keyPair = new NettyKeyProvider(new KeyPair(TlsTestConstants.getResourceLocationOnFilesystem("server_encrypted.key"),
                TlsTestConstants.getResourceLocationOnFilesystem("server.crt"), new InlinePassword("keypass")));

        var sslContext = keyPair.forClient().build();
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();
    }

    @Test
    @EnabledOnOs({ OS.LINUX, OS.MAC })
    void strictPolicyRejectsInsecurePrivateKeyFile(@TempDir Path tmp) throws IOException {
        // Given - a private key file with group-read permissions (0640)
        Path insecureKey = tmp.resolve("server.key");
        Files.copy(Path.of(TlsTestConstants.getResourceLocationOnFilesystem("server.key")), insecureKey);
        Files.setPosixFilePermissions(insecureKey, PosixFilePermissions.fromString("rw-r-----"));
        var keyPair = new NettyKeyProvider(
                new KeyPair(insecureKey.toString(), TlsTestConstants.getResourceLocationOnFilesystem("server.crt"), null),
                Policy.STRICT);

        // When / Then - SslContextBuildException wrapping the permission IllegalStateException
        assertThatCode(keyPair::forServer)
                .hasMessageContaining("Error building SSLContext")
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("too open")
                .hasMessageContaining("0640");
    }

    @Test
    @EnabledOnOs({ OS.LINUX, OS.MAC })
    void strictPolicyRejectsInsecureKeystoreFile(@TempDir Path tmp) throws IOException {
        // Given - a keystore file with world-read permissions (0644)
        Path insecureKeystore = tmp.resolve("server.jks");
        Files.copy(Path.of(TlsTestConstants.getResourceLocationOnFilesystem("server.jks")), insecureKeystore);
        Files.setPosixFilePermissions(insecureKeystore, PosixFilePermissions.fromString("rw-r--r--"));
        var keyStore = new NettyKeyProvider(new KeyStore(insecureKeystore.toString(), TlsTestConstants.STOREPASS, null, null), Policy.STRICT);

        // When / Then
        assertThatCode(keyStore::forServer)
                .hasMessageContaining("Error building SSLContext")
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("too open")
                .hasMessageContaining("0644");
    }

    @Test
    @EnabledOnOs({ OS.LINUX, OS.MAC })
    void strictPolicyRejectsInsecurePasswordFile(@TempDir Path tmp) throws IOException {
        // Given - a keystore with secure permissions and a FilePassword pointing to a group-readable file (0640)
        Path secureKeystore = tmp.resolve("server.jks");
        Files.copy(Path.of(TlsTestConstants.getResourceLocationOnFilesystem("server.jks")), secureKeystore);
        Files.setPosixFilePermissions(secureKeystore, PosixFilePermissions.fromString("rw-------"));

        Path insecurePassFile = tmp.resolve("password.txt");
        Files.writeString(insecurePassFile, TlsTestConstants.STOREPASS.getProvidedPassword());
        Files.setPosixFilePermissions(insecurePassFile, PosixFilePermissions.fromString("rw-r-----"));

        var keyStore = new NettyKeyProvider(
                new KeyStore(secureKeystore.toString(),
                        new FilePassword(insecurePassFile.toString()), null, null),
                Policy.STRICT);

        // When / Then - the password file permission check fires before the keystore is opened
        assertThatCode(keyStore::forServer)
                .hasMessageContaining("Error building SSLContext")
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("too open")
                .hasMessageContaining("password file");
    }

    @Test
    @EnabledOnOs({ OS.LINUX, OS.MAC })
    void strictPolicyAcceptsOwnerOnlyFiles(@TempDir Path tmp) throws IOException {
        // Given - private key copied to a 0600 temp file (owner-only, STRICT passes)
        Path secureKey = tmp.resolve("server.key");
        Files.copy(Path.of(TlsTestConstants.getResourceLocationOnFilesystem("server.key")), secureKey);
        Files.setPosixFilePermissions(secureKey, PosixFilePermissions.fromString("rw-------"));
        var keyPair = new NettyKeyProvider(
                new KeyPair(secureKey.toString(), TlsTestConstants.getResourceLocationOnFilesystem("server.crt"), null),
                Policy.STRICT);

        // When / Then - no permission exception; other exceptions (if any) are unrelated
        assertThatCode(keyPair::forServer)
                .doesNotThrowAnyException();
    }

    private AbstractThrowableAssert<?, ? extends Throwable> doFailingKeyPairTest(String privateKeyFile, String certificateFile,
                                                                                 PasswordProvider keyPassword, boolean forServer) {
        var keyPair = new NettyKeyProvider(new KeyPair(privateKeyFile, certificateFile, keyPassword));
        return assertThatCode(forServer ? keyPair::forServer : keyPair::forClient)
                .hasMessageContaining("Error building SSLContext");
    }

}
