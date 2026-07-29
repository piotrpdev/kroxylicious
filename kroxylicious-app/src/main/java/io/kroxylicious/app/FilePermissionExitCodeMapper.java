/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.app;

import io.kroxylicious.proxy.security.FilePermissionViolationException;

import picocli.CommandLine;

/**
 * Maps file permission validation failures to exit code 78 ({@code EX_CONFIG} from sysexits.h)
 * so the Kubernetes operator can distinguish permission errors from other startup failures
 * by inspecting {@code containerStatuses[*].lastState.terminated.exitCode}.
 */
class FilePermissionExitCodeMapper implements CommandLine.IExitCodeExceptionMapper {

    static final int EX_CONFIG = 78;

    @Override
    public int getExitCode(Throwable exception) {
        if (isFilePermissionViolation(exception)) {
            return EX_CONFIG;
        }
        return CommandLine.ExitCode.SOFTWARE;
    }

    private static boolean isFilePermissionViolation(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof FilePermissionViolationException) {
                return true;
            }
        }
        return false;
    }
}