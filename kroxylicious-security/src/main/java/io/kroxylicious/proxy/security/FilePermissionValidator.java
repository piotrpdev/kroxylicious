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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validates file permissions for confidential files (passwords, private keys, keystores).
 * Ensures files are not overly permissive, similar to SSH's behaviour.
 */
public class FilePermissionValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilePermissionValidator.class);
    private static final AtomicBoolean NON_POSIX_WARNING_LOGGED = new AtomicBoolean(false);
    private static final Set<Path> DISABLED_POLICY_WARNED = ConcurrentHashMap.newKeySet();
    private static final AtomicReference<Policy> GLOBAL_POLICY = new AtomicReference<>(Policy.DISABLED);

    /**
     * Permission validation policy.
     */
    public enum Policy {
        /**
         * No group or other bits allowed (like SSH). Permissions must be owner-only (e.g. 0400, 0600).
         */
        STRICT,

        /**
         * Other bits forbidden; group bits allowed. Suitable for Kubernetes environments with fsGroup.
         */
        RELAXED,

        /**
         * Never reject — log a warning for world-readable files. Escape hatch for environments
         * where POSIX permission checks are not meaningful.
         */
        DISABLED
    }

    private FilePermissionValidator() {
    }

    /**
     * Sets the global policy used by {@link #validate(Path, String)}.
     *
     * <p>This is an internal Kroxylicious method. Do not call it from
     * application or plugin code: doing so will alter the validation policy
     * for all confidential file reads across the proxy.
     *
     * @param policy the policy to apply globally
     */
    public static void setGlobalPolicy(@NonNull Policy policy) {
        GLOBAL_POLICY.set(policy);
    }

    /**
     * Validates file permissions using the global policy set by {@link #setGlobalPolicy(Policy)}.
     * Intended for call sites (e.g. credential providers) that do not receive the policy explicitly.
     *
     * @param file the file to validate
     * @param fileDescription human-readable description used in error messages (e.g. "password file")
     * @throws IllegalStateException if permissions are too permissive and the global policy is STRICT or RELAXED
     */
    public static void validate(@NonNull Path file, @NonNull String fileDescription) {
        validate(file, GLOBAL_POLICY.get(), fileDescription);
    }

    /**
     * Validates file permissions according to the specified policy.
     *
     * @param file the file to validate
     * @param policy the validation policy to apply
     * @param fileDescription human-readable description used in error messages (e.g. "private key", "keystore")
     * @throws IllegalStateException if permissions are too permissive and policy is STRICT or RELAXED
     */
    public static void validate(@NonNull Path file, @NonNull Policy policy, @NonNull String fileDescription) {
        validate(file, policy, fileDescription, LOGGER, DISABLED_POLICY_WARNED);
    }

    /**
     * Package-private overload for testing: accepts a specific logger and warned-paths set so
     * tests can inject a mock logger and a fresh set without sharing global state.
     */
    static void validate(@NonNull Path file, @NonNull Policy policy, @NonNull String fileDescription,
                         @NonNull Logger logger, @NonNull Set<Path> disabledPolicyWarned) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            checkPermissions(file, perms, policy, fileDescription, logger, disabledPolicyWarned);
        }
        catch (UnsupportedOperationException e) {
            if (NON_POSIX_WARNING_LOGGED.compareAndSet(false, true)) {
                logger.atWarn()
                        .log("File permission validation is not supported on this filesystem (POSIX permissions unavailable). Security checks will be skipped.");
            }
        }
        catch (IOException e) {
            logger.atWarn()
                    .addKeyValue("file", file)
                    .addKeyValue("error", e.getMessage())
                    .log("Failed to read file permissions for confidential file");
        }
    }

    private static void checkPermissions(Path file, Set<PosixFilePermission> perms, Policy policy,
                                         String fileDescription, Logger logger, Set<Path> disabledPolicyWarned) {
        boolean otherAccess = perms.stream().anyMatch(p -> p.name().startsWith("OTHERS"));
        boolean groupAccess = perms.stream().anyMatch(p -> p.name().startsWith("GROUP"));

        boolean tooOpen = switch (policy) {
            case STRICT -> otherAccess || groupAccess;
            case RELAXED -> otherAccess;
            // Use the STRICT threshold so group-only files (e.g. 0640) also generate a warning.
            case DISABLED -> otherAccess || groupAccess;
        };

        if (!tooOpen) {
            return;
        }

        String octalPerms = toOctalString(perms);

        if (policy == Policy.DISABLED) {
            if (disabledPolicyWarned.add(file.toAbsolutePath().normalize())) {
                logger.atWarn()
                        .addKeyValue("file", file)
                        .addKeyValue("permissions", octalPerms)
                        .addKeyValue("fileType", fileDescription)
                        .log("Confidential file has permissions that would be rejected by STRICT or RELAXED policy. " +
                                "File permission checking is currently disabled - DISABLED is the default for backward compatibility " +
                                "but this will be changed in a future release. " +
                                "To enforce minimum permissions now, set 'security.filePermissions.policy: STRICT'.");
            }
        }
        else {
            throw new FilePermissionViolationException(String.format(
                    "Permissions %s for '%s' are too open.%n" +
                            "It is required that your %s is NOT accessible by others.%n" +
                            "This file will not be used.",
                    octalPerms, file.toAbsolutePath(), fileDescription));
        }
    }

    private static String toOctalString(Set<PosixFilePermission> perms) {
        int mode = 0;
        for (PosixFilePermission p : perms) {
            mode |= switch (p) {
                case OWNER_READ -> 0400;
                case OWNER_WRITE -> 0200;
                case OWNER_EXECUTE -> 0100;
                case GROUP_READ -> 0040;
                case GROUP_WRITE -> 0020;
                case GROUP_EXECUTE -> 0010;
                case OTHERS_READ -> 0004;
                case OTHERS_WRITE -> 0002;
                case OTHERS_EXECUTE -> 0001;
            };
        }
        return String.format("0%03o", mode);
    }
}