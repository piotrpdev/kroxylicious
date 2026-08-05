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
import java.util.EnumMap;
import java.util.Map;
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
    private static final AtomicReference<Map<Category, Policy>> GLOBAL_POLICIES = new AtomicReference<>(defaultPolicies());

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
         * Never reject - log a warning for world-readable files. Escape hatch for environments
         * where POSIX permission checks are not meaningful.
         */
        DISABLED
    }

    /**
     * Categories of confidential files, each with an independent policy.
     */
    public enum Category {
        /**
         * TLS private keys, keystores, and password files. User/operator-controlled, high sensitivity.
         */
        SECRETS,

        /**
         * TLS truststore files. User/operator-controlled, lower sensitivity (public certificates).
         */
        TRUSTSTORES,

        /**
         * Platform-managed credential files (e.g. AWS IRSA tokens, Pod Identity tokens).
         * Permissions are controlled by the cloud platform, not the user.
         */
        PLATFORM_CREDENTIALS
    }

    private FilePermissionValidator() {
    }

    private static Map<Category, Policy> defaultPolicies() {
        var map = new EnumMap<Category, Policy>(Category.class);
        map.put(Category.SECRETS, Policy.DISABLED);
        map.put(Category.TRUSTSTORES, Policy.DISABLED);
        map.put(Category.PLATFORM_CREDENTIALS, Policy.DISABLED);
        return map;
    }

    /**
     * Sets the global policy for a single category.
     *
     * <p>This is an internal Kroxylicious method. Do not call it from
     * application or plugin code: doing so will alter the validation policy
     * for confidential file reads across the proxy.
     *
     * @param category the file category
     * @param policy the policy to apply
     */
    public static void setGlobalPolicy(@NonNull Category category, @NonNull Policy policy) {
        GLOBAL_POLICIES.updateAndGet(current -> {
            var updated = new EnumMap<>(current);
            updated.put(category, policy);
            return updated;
        });
    }

    /**
     * Sets the global policy for all categories at once.
     *
     * @param policies the per-category policies
     */
    public static void setGlobalPolicies(@NonNull Map<Category, Policy> policies) {
        var updated = new EnumMap<>(defaultPolicies());
        updated.putAll(policies);
        GLOBAL_POLICIES.set(updated);
    }

    /**
     * Validates file permissions using the global policy for the given category.
     *
     * @param file the file to validate
     * @param category the file category (determines which policy applies)
     * @param fileDescription human-readable description used in error messages (e.g. "password file")
     * @throws FilePermissionViolationException if permissions are too permissive
     */
    public static void validate(@NonNull Path file, @NonNull Category category, @NonNull String fileDescription) {
        Policy policy = GLOBAL_POLICIES.get().getOrDefault(category, Policy.DISABLED);
        validate(file, policy, fileDescription);
    }

    /**
     * Validates file permissions according to the specified policy.
     *
     * @param file the file to validate
     * @param policy the validation policy to apply
     * @param fileDescription human-readable description used in error messages (e.g. "private key", "keystore")
     * @throws FilePermissionViolationException if permissions are too permissive and policy is STRICT or RELAXED
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

    /**
     * Resets all global policies to their defaults. Intended for test cleanup only.
     */
    public static void resetGlobalPolicies() {
        GLOBAL_POLICIES.set(defaultPolicies());
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
                                "To enforce minimum permissions now, configure 'security.filePermissions'.");
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