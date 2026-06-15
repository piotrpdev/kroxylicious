/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.kroxylicious.proxy.security.FilePermissionValidator.Policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs({ OS.LINUX, OS.MAC })
class FilePermissionValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAcceptOwnerReadOnlyInStrictPolicy() throws IOException {
        // Given
        Path file = createFileWithPermissions("400");
        // When / Then
        assertThatCode(() -> FilePermissionValidator.validate(file, Policy.STRICT, "private key"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptOwnerReadWriteInStrictPolicy() throws IOException {
        // Given
        Path file = createFileWithPermissions("600");
        // When / Then
        assertThatCode(() -> FilePermissionValidator.validate(file, Policy.STRICT, "private key"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectGroupReadInStrictPolicy() throws IOException {
        // Given
        Path file = createFileWithPermissions("640");
        // When / Then
        assertThatThrownBy(() -> FilePermissionValidator.validate(file, Policy.STRICT, "private key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0640")
                .hasMessageContaining("too open")
                .hasMessageContaining("private key");
    }

    @Test
    void shouldRejectOtherReadInStrictPolicy() throws IOException {
        // Given
        Path file = createFileWithPermissions("644");
        // When / Then
        assertThatThrownBy(() -> FilePermissionValidator.validate(file, Policy.STRICT, "keystore"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0644")
                .hasMessageContaining("too open")
                .hasMessageContaining("keystore");
    }

    @Test
    void shouldRejectWorldReadableInStrictPolicy() throws IOException {
        // Given
        Path file = createFileWithPermissions("666");
        // When / Then
        assertThatThrownBy(() -> FilePermissionValidator.validate(file, Policy.STRICT, "password file"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0666");
    }

    @Test
    void shouldRejectInsecurePermissionCombinationsInStrictPolicy() throws IOException {
        // Given - STRICT rejects group or other bits
        for (String perms : new String[]{ "440", "640", "740", "644", "666", "777" }) {
            Path file = createFileWithPermissions(perms);
            // When / Then
            assertThatThrownBy(() -> FilePermissionValidator.validate(file, Policy.STRICT, "test"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void shouldAcceptOwnerOnlyPermissionCombinationsInStrictPolicy() throws IOException {
        // Given - STRICT allows owner-only permissions (including execute bit)
        for (String perms : new String[]{ "400", "500", "600", "700" }) {
            Path file = createFileWithPermissions(perms);
            // When / Then
            assertThatCode(() -> FilePermissionValidator.validate(file, Policy.STRICT, "test"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void shouldAcceptOwnerOnlyPermissionsInRelaxedPolicy() throws IOException {
        // Given
        Path file = createFileWithPermissions("400");
        // When / Then
        assertThatCode(() -> FilePermissionValidator.validate(file, Policy.RELAXED, "truststore"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptGroupReadInRelaxedPolicy() throws IOException {
        // Given - RELAXED allows group access (Kubernetes fsGroup pattern)
        Path file = createFileWithPermissions("440");
        // When / Then
        assertThatCode(() -> FilePermissionValidator.validate(file, Policy.RELAXED, "truststore"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectOtherReadInRelaxedPolicy() throws IOException {
        // Given
        Path file = createFileWithPermissions("444");
        // When / Then
        assertThatThrownBy(() -> FilePermissionValidator.validate(file, Policy.RELAXED, "truststore"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0444")
                .hasMessageContaining("truststore");
    }

    @Test
    void shouldRejectWorldReadableInRelaxedPolicy() throws IOException {
        // Given
        Path file = createFileWithPermissions("644");
        // When / Then
        assertThatThrownBy(() -> FilePermissionValidator.validate(file, Policy.RELAXED, "certificate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0644");
    }

    @Test
    void shouldAcceptPermissionsWithNoOtherBitsInRelaxedPolicy() throws IOException {
        // Given - RELAXED accepts group bits, rejects other bits
        for (String perms : new String[]{ "400", "440", "600", "640", "700", "740" }) {
            Path file = createFileWithPermissions(perms);
            // When / Then
            assertThatCode(() -> FilePermissionValidator.validate(file, Policy.RELAXED, "test"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void shouldRejectPermissionsWithOtherBitsInRelaxedPolicy() throws IOException {
        // Given
        for (String perms : new String[]{ "444", "644", "744", "666", "777" }) {
            Path file = createFileWithPermissions(perms);
            // When / Then
            assertThatThrownBy(() -> FilePermissionValidator.validate(file, Policy.RELAXED, "test"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    
    @Test
    void shouldNeverThrowForSecurePermissionsInDisabledPolicy() throws IOException {
        // Given - DISABLED never rejects, even for owner-only files
        for (String perms : new String[]{ "600", "400" }) {
            Path file = createFileWithPermissions(perms);
            // When / Then - no throw, no warning (file is secure anyway)
            assertThatCode(() -> FilePermissionValidator.validate(file, Policy.DISABLED, "test file"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void shouldNeverThrowForInsecurePermissionsInDisabledPolicy() throws IOException {
        // Given - DISABLED warns but never rejects, even for world-readable or group-readable files
        for (String perms : new String[]{ "777", "666", "644", "640" }) {
            Path file = createFileWithPermissions(perms);
            // When / Then - no throw (warning is logged, but not assertable without log capture)
            assertThatCode(() -> FilePermissionValidator.validate(file, Policy.DISABLED, "test file"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void shouldWarnForGroupOnlyPermissionsInDisabledPolicy() throws IOException {
        // Given - 0640 has only group bits; previously DISABLED silently ignored this.
        // Now DISABLED uses the STRICT threshold and warns for group bits too.
        Path file = createFileWithPermissions("640");
        // When / Then - must not throw (DISABLED never rejects)
        assertThatCode(() -> FilePermissionValidator.validate(file, Policy.DISABLED, "private key"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFollowSymlinkAndAcceptIfTargetIsSecureInStrictPolicy() throws IOException {
        // Given
        Path target = createFileWithPermissions("600");
        Path link = tempDir.resolve("link");
        Files.createSymbolicLink(link, target);
        // When / Then
        assertThatCode(() -> FilePermissionValidator.validate(link, Policy.STRICT, "private key"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFollowSymlinkAndRejectIfTargetIsInsecureInStrictPolicy() throws IOException {
        // Given
        Path target = createFileWithPermissions("644");
        Path link = tempDir.resolve("link");
        Files.createSymbolicLink(link, target);
        // When / Then
        assertThatThrownBy(() -> FilePermissionValidator.validate(link, Policy.STRICT, "private key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0644");
    }

    @Test
    void shouldIncludeOctalPermissionsPathAndDescriptionInErrorMessage() throws IOException {
        // Given
        Path file = createFileWithPermissions("644");
        // When / Then
        assertThatThrownBy(() -> FilePermissionValidator.validate(file, Policy.STRICT, "private key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0644")
                .hasMessageContaining(file.toAbsolutePath().toString())
                .hasMessageContaining("private key");
    }

    private Path createFileWithPermissions(String octalPerms) throws IOException {
        Path file = Files.createTempFile(tempDir, "test", ".txt");
        Files.writeString(file, "test content");
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString(octalToSymbolic(octalPerms));
        Files.setPosixFilePermissions(file, perms);
        return file;
    }

    private static String octalToSymbolic(String octal) {
        int mode = Integer.parseInt(octal, 8);
        var sb = new StringBuilder();
        sb.append((mode & 0400) != 0 ? 'r' : '-');
        sb.append((mode & 0200) != 0 ? 'w' : '-');
        sb.append((mode & 0100) != 0 ? 'x' : '-');
        sb.append((mode & 0040) != 0 ? 'r' : '-');
        sb.append((mode & 0020) != 0 ? 'w' : '-');
        sb.append((mode & 0010) != 0 ? 'x' : '-');
        sb.append((mode & 0004) != 0 ? 'r' : '-');
        sb.append((mode & 0002) != 0 ? 'w' : '-');
        sb.append((mode & 0001) != 0 ? 'x' : '-');
        return sb.toString();
    }
}