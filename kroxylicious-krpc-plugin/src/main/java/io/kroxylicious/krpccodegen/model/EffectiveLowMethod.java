/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.model;

import java.util.List;

import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;

/**
 * FreeMarker method model that computes the effective lowest version for a struct:
 * the maximum of the struct's own lowest version and the message's lowest valid version.
 */
public class EffectiveLowMethod implements TemplateMethodModelEx {

    private final SchemaVersionLogic logic;

    /**
     * Constructs an EffectiveLowMethod.
     *
     * @param logic shared schema version logic providing the message's lowest valid version
     */
    public EffectiveLowMethod(SchemaVersionLogic logic) {
        this.logic = logic;
    }

    @Override
    @SuppressWarnings("java:S3740")
    public Object exec(List arguments) throws TemplateModelException {
        var structModel = (StructSpecModel) arguments.get(0);
        return logic.effectiveLow(structModel.spec);
    }
}
