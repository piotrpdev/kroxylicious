/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.model;

import java.util.List;
import java.util.Locale;

import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import freemarker.template.TemplateScalarModel;

/**
 * FreeMarker method model that converts a PascalCase field name to snake_case,
 * for use in Schema {@code Field} declarations (e.g. {@code "MaxWaitMs"} → {@code "max_wait_ms"}).
 */
public class SnakeCaseMethod implements TemplateMethodModelEx {

    /**
     * Constructs a SnakeCaseMethod.
     */
    public SnakeCaseMethod() {
    }

    @Override
    @SuppressWarnings("java:S3740")
    public Object exec(List arguments) throws TemplateModelException {
        String name = ((TemplateScalarModel) arguments.get(0)).getAsString();
        return name.replaceAll("(?<=[a-z])([A-Z])", "_$1").toLowerCase(Locale.ROOT);
    }
}
