/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.model;

import java.util.List;

import io.kroxylicious.krpccodegen.schema.StructRegistry;

import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;

/**
 * FreeMarker method model that returns {@code true} if the struct referenced by
 * a struct-array field has any {@code mapKey=true} fields, meaning the generated
 * collection type should extend {@code ImplicitLinkedHashMultiCollection}.
 */
public class StructHasKeysMethod implements TemplateMethodModelEx {

    private final StructRegistry structRegistry;

    /**
     * Constructs a StructHasKeysMethod.
     *
     * @param structRegistry the struct registry for this message spec
     */
    public StructHasKeysMethod(StructRegistry structRegistry) {
        this.structRegistry = structRegistry;
    }

    @Override
    @SuppressWarnings("java:S3740")
    public Object exec(List arguments) throws TemplateModelException {
        var fieldModel = (FieldSpecModel) arguments.get(0);
        if (!fieldModel.spec.type().isStructArray()) {
            return false;
        }
        return structRegistry.findStruct(fieldModel.spec).hasKeys();
    }
}
