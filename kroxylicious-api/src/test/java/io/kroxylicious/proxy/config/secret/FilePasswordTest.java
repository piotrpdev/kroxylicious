/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.secret;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Function;
import java.util.stream.Stream;

import org.assertj.core.api.Condition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kroxylicious.proxy.security.FilePermissionValidator;
import io.kroxylicious.proxy.security.FilePermissionValidator.Category;
import io.kroxylicious.proxy.security.FilePermissionValidator.Policy;
import io.kroxylicious.proxy.security.FilePermissionViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePasswordTest {

    private File file;

    @BeforeEach
    void setUp() throws Exception {
        file = File.createTempFile("password", "txt");
        file.deleteOnExit();
    }

    @AfterEach
    void afterEach() {
        FilePermissionValidator.resetGlobalPolicies();
        if (file != null && Files.exists(file.toPath()) && !file.delete()) {
            throw new IllegalStateException("Could not delete temp file: " + file.getAbsolutePath());
        }
    }

    static Stream<Arguments> readPassword() {
        Function<String, PasswordProvider> filePassword = FilePassword::new;
        return Stream.of(
                Arguments.of(filePassword, "mypassword", "mypassword"),
                Arguments.of(filePassword, "mypassword\n", "mypassword"),
                Arguments.of(filePassword, "mypassword\nignores\nadditional lines", "mypassword"));
    }

    @ParameterizedTest
    @MethodSource
    void readPassword(Function<String, PasswordProvider> providerFunc, String input, String expected) throws Exception {
        Files.writeString(file.toPath(), input);
        var provider = providerFunc.apply(file.getAbsolutePath());
        assertThat(provider)
                .extracting(PasswordProvider::getProvidedPassword)
                .isEqualTo(expected);
    }

    @Test
    void toStringDoesNotLeakPassword() throws Exception {
        var password = "mypassword";
        Files.writeString(file.toPath(), password);
        var provider = new FilePassword(file.getAbsolutePath());
        assertThat(provider)
                .extracting(Object::toString)
                .doesNotHave(new Condition<>(s -> s.contains(password), "contains password"));
    }

    @Test
    void passwordFileNotFound() {
        assertThat(file.delete()).isTrue();

        String path = file.getAbsolutePath();
        var provider = new FilePassword(file.getAbsolutePath());
        assertThatThrownBy(provider::getProvidedPassword)
                .hasMessageContaining(path)
                .hasRootCauseInstanceOf(FileNotFoundException.class);
    }

    @Test
    @EnabledOnOs({ OS.LINUX, OS.MAC })
    void strictGlobalPolicyRejectsInsecurePasswordFile() throws Exception {
        // Given - a password file with group-read permissions and global policy set to STRICT
        Files.writeString(file.toPath(), "secret");
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r-----"));
        FilePermissionValidator.setGlobalPolicy(Category.SECRETS, Policy.STRICT);

        // When / Then - getProvidedPassword() throws because the file is too open
        var provider = new FilePassword(file.getAbsolutePath());
        assertThatThrownBy(provider::getProvidedPassword)
                .isInstanceOf(FilePermissionViolationException.class)
                .hasMessageContaining("too open");
    }

    @Test
    @EnabledOnOs({ OS.LINUX, OS.MAC })
    void strictGlobalPolicyAcceptsOwnerOnlyPasswordFile() throws Exception {
        // Given - a password file with owner-only permissions and global policy set to STRICT
        Files.writeString(file.toPath(), "secret");
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
        FilePermissionValidator.setGlobalPolicy(Category.SECRETS, Policy.STRICT);

        // When / Then - no exception thrown; password is read successfully
        var provider = new FilePassword(file.getAbsolutePath());
        assertThat(provider.getProvidedPassword()).isEqualTo("secret");
    }
}
