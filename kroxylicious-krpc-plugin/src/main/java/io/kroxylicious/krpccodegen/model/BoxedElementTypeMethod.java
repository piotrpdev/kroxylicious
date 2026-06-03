/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.model;

import java.util.List;
import java.util.Map;

import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import freemarker.template.TemplateScalarModel;

/**
 * FreeMarker method model that maps a Kafka protocol primitive type name to its
 * boxed Java type name, for use in generic type parameters (e.g. {@code List<Integer>}).
 */
public class BoxedElementTypeMethod implements TemplateMethodModelEx {

    private static final Map<String, String> BOXED_TYPES = Map.of(
            "int32", "Integer",
            "int64", "Long",
            "int8", "Byte",
            "int16", "Short",
            "uint16", "Integer",
            "bool", "Boolean",
            "uuid", "Uuid",
            "string", "String");

    /**
     * Constructs a BoxedElementTypeMethod.
     */
    public BoxedElementTypeMethod() {
    }

    @Override
    @SuppressWarnings("java:S3740")
    public Object exec(List arguments) throws TemplateModelException {
        String typeName = ((TemplateScalarModel) arguments.get(0)).getAsString();
        return BOXED_TYPES.getOrDefault(typeName, typeName);
    }
}
