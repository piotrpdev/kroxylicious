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
 * FreeMarker method model that returns the canonical schema version constant index
 * for a struct at a given version — i.e. the last version at or before {@code version}
 * where the schema actually changed, walking from {@code effectiveLow}.
 * <p>
 * Template usage: {@code resolveSchemaVersion(struct, version, effectiveLow)}
 */
public class ResolveSchemaVersionMethod implements TemplateMethodModelEx {

    private final SchemaVersionLogic logic;

    /**
     * Constructs a ResolveSchemaVersionMethod.
     *
     * @param logic shared schema version logic
     */
    public ResolveSchemaVersionMethod(SchemaVersionLogic logic) {
        this.logic = logic;
    }

    @Override
    @SuppressWarnings("java:S3740")
    public Object exec(List arguments) throws TemplateModelException {
        var structModel = (StructSpecModel) arguments.get(0);
        short version = ((SimpleNumber) arguments.get(1)).getAsNumber().shortValue();
        short effLow = ((SimpleNumber) arguments.get(2)).getAsNumber().shortValue();
        return logic.resolveSchemaVersion(structModel.spec, version, effLow);
    }
}
