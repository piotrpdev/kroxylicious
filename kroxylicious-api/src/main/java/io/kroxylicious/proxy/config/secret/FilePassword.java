/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.secret;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.kroxylicious.proxy.security.FilePermissionValidator;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A reference to the file containing a nonempty plain text password in UTF-8 encoding.  If the password file
 * contains more than one line, only the characters of the first line are taken to be the password,
 * excluding the line ending.  Subsequent lines are ignored.
 *
 * <p>File permissions are checked with {@link FilePermissionValidator.Policy#DISABLED} each time the password is read:
 * a warning is logged if the file is accessible by group or other users, but the read is never
 * rejected.  Enforcement (rejection) is applied by the runtime at TLS call sites where the
 * operator-configured policy is available.
 *
 * @param passwordFile file containing the password.
 */
public record FilePassword(@JsonProperty(required = true) String passwordFile) implements PasswordProvider {

    public FilePassword {
        Objects.requireNonNull(passwordFile);
    }

    @Override
    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "Path comes from operator-controlled configuration, not user input.")
    public String getProvidedPassword() {
        FilePermissionValidator.validate(Path.of(passwordFile), "password file");
        return readPasswordFile(passwordFile);
    }

    @Override
    public String toString() {
        return "FilePassword[" +
                "passwordFile=" + passwordFile + ']';
    }

    static String readPasswordFile(String passwordFile) {
        try (var fr = new BufferedReader(new FileReader(passwordFile, StandardCharsets.UTF_8))) {
            String line = fr.readLine();
            if (line == null) {
                throw new IOException("Empty file");
            }
            return line;
        }
        catch (IOException e) {
            throw new UncheckedIOException("Exception reading " + passwordFile, e);
        }
    }

}
