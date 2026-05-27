/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.model;

import java.util.List;

import freemarker.template.SimpleNumber;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;

/**
 * FreeMarker method model that returns {@code true} if a struct's schema changes
 * between version-1 and version. Recursively checks sub-struct schemas.
 * <p>
 * Template usage: {@code structSchemaChangesAt(struct, version)}
 */
public class StructSchemaChangesAtMethod implements TemplateMethodModelEx {

    private final SchemaVersionLogic logic;

    /**
     * Constructs a StructSchemaChangesAtMethod.
     *
     * @param logic shared schema version logic
     */
    public StructSchemaChangesAtMethod(SchemaVersionLogic logic) {
        this.logic = logic;
    }

    @Override
    @SuppressWarnings("java:S3740")
    public Object exec(List arguments) throws TemplateModelException {
        var structModel = (StructSpecModel) arguments.get(0);
        short version = ((SimpleNumber) arguments.get(1)).getAsNumber().shortValue();
        return logic.structSchemaChangesAt(structModel.spec, version);
    }
}
