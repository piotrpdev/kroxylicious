/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.app;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import io.kroxylicious.proxy.security.FilePermissionViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class FilePermissionExitCodeMapperTest {

    private final FilePermissionExitCodeMapper mapper = new FilePermissionExitCodeMapper();

    @Test
    void directFilePermissionViolationMapsToExConfig() {
        // Given
        var exception = new FilePermissionViolationException("Permissions 0644 for '/tmp/key' are too open.");

        // When / Then
        assertThat(mapper.getExitCode(exception)).isEqualTo(FilePermissionExitCodeMapper.EX_CONFIG);
    }

    @Test
    void wrappedFilePermissionViolationMapsToExConfig() {
        // Given
        var root = new FilePermissionViolationException("Permissions 0644 for '/tmp/key' are too open.");
        var wrapped = new RuntimeException("Error building SSLContext", root);

        // When / Then
        assertThat(mapper.getExitCode(wrapped)).isEqualTo(FilePermissionExitCodeMapper.EX_CONFIG);
    }

    @Test
    void deeplyNestedFilePermissionViolationMapsToExConfig() {
        // Given
        var root = new FilePermissionViolationException("Permissions 0640 for '/tmp/pass' are too open.");
        var level1 = new RuntimeException("SSL error", root);
        var level2 = new RuntimeException("Lifecycle error", level1);
        var level3 = new CompletionException(level2);

        // When / Then
        assertThat(mapper.getExitCode(level3)).isEqualTo(FilePermissionExitCodeMapper.EX_CONFIG);
    }

    @Test
    void nonPermissionIllegalStateExceptionMapsToSoftware() {
        // Given
        var exception = new IllegalStateException("KafkaProxy is not restartable");

        // When / Then
        assertThat(mapper.getExitCode(exception)).isEqualTo(1);
    }

    @Test
    void ioExceptionMapsToSoftware() {
        // Given
        var exception = new IOException("Connection refused");

        // When / Then
        assertThat(mapper.getExitCode(exception)).isEqualTo(1);
    }

    @Test
    void nullMessageIllegalStateExceptionMapsToSoftware() {
        // Given
        var exception = new IllegalStateException((String) null);

        // When / Then
        assertThat(mapper.getExitCode(exception)).isEqualTo(1);
    }

    @Test
    void wrappedNonPermissionExceptionMapsToSoftware() {
        // Given
        var root = new IllegalStateException("something else went wrong");
        var wrapped = new RuntimeException("startup failed", root);

        // When / Then
        assertThat(mapper.getExitCode(wrapped)).isEqualTo(1);
    }
}