/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.model;

import io.kroxylicious.krpccodegen.schema.FieldSpec;
import io.kroxylicious.krpccodegen.schema.MessageSpec;
import io.kroxylicious.krpccodegen.schema.StructRegistry;
import io.kroxylicious.krpccodegen.schema.StructSpec;
import io.kroxylicious.krpccodegen.schema.Versions;

/**
 * Shared logic for schema version resolution, used by the
 * {@link StructSchemaChangesAtMethod} and {@link ResolveSchemaVersionMethod}
 * TemplateMethodModels.
 */
public class SchemaVersionLogic {

    private final Versions messageFlexibleVersions;
    private final StructRegistry structRegistry;
    private final short messageLowest;

    public SchemaVersionLogic(MessageSpec messageSpec, StructRegistry structRegistry) {
        this.messageFlexibleVersions = messageSpec.flexibleVersions();
        this.structRegistry = structRegistry;
        this.messageLowest = messageSpec.validVersions().lowest();
    }

    /**
     * Returns the effective lowest version for a struct:
     * the maximum of the struct's own lowest version and the message's lowest valid version.
     */
    short effectiveLow(StructSpec struct) {
        return (short) Math.max(struct.versions().lowest(), messageLowest);
    }

    /**
     * Returns true if the struct's schema changes between version-1 and version.
     * Checks field appearances/disappearances, tagged section changes,
     * serialization format changes, and recursively checks sub-struct schemas.
     */
    boolean structSchemaChangesAt(StructSpec struct, short version) {
        short prev = (short) (version - 1);
        for (FieldSpec field : struct.fields()) {
            boolean taggedAtV = field.taggedVersions().contains(version);
            boolean taggedAtPrev = field.taggedVersions().contains(prev);
            boolean presentAtV = field.versions().contains(version);
            boolean presentAtPrev = field.versions().contains(prev);

            if (!taggedAtV && presentAtV && (!presentAtPrev || taggedAtPrev)) {
                return true;
            }
            if (!taggedAtPrev && presentAtPrev && (!presentAtV || taggedAtV)) {
                return true;
            }
            if (taggedAtV && presentAtV && !(taggedAtPrev && presentAtPrev)) {
                return true;
            }
            if (taggedAtPrev && presentAtPrev && !(taggedAtV && presentAtV)) {
                return true;
            }
            if (presentAtV && presentAtPrev && !taggedAtV && !taggedAtPrev
                    && field.type().serializationIsDifferentInFlexibleVersions()) {
                Versions effFlex = field.flexibleVersions().orElse(messageFlexibleVersions);
                if (effFlex.contains(version) != effFlex.contains(prev)) {
                    return true;
                }
            }
            if ((field.type().isStructArray() || field.type().isStruct())
                    && presentAtV && presentAtPrev && !taggedAtV && !taggedAtPrev) {
                StructSpec substruct = structRegistry.findStruct(field);
                if (structSchemaChangesAt(substruct, version)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the canonical schema version constant name index for a struct at a given version.
     * Walks from effectiveLow upward, tracking the last version where the schema changed.
     */
    short resolveSchemaVersion(StructSpec struct, short version, short effLow) {
        short canonical = effLow;
        for (short v = (short) (effLow + 1); v <= version; v++) {
            if (structSchemaChangesAt(struct, v)) {
                canonical = v;
            }
        }
        return canonical;
    }

    StructRegistry structRegistry() {
        return structRegistry;
    }
}
