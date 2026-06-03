/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.main;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the kroxylicious {@code Data/example.ftl} template can generate
 * compilable Java source code for ALL {@code *Request} and {@code *Response} Kafka
 * message specs, covering all field types (including {@code uint16}, {@code bytes},
 * {@code float64}, {@code records}) and struct patterns ({@code commonStructs}, keyed
 * collections, nested tagged fields).
 */
class AllDataClassesCompileTest {

    @Test
    void allRequestResponseSpecsGenerateCompilableCode() throws Exception {
        Assumptions.assumeTrue(javax.tools.ToolProvider.getSystemJavaCompiler() != null,
                "JDK compiler not available — skipping compilation test");

        Map<String, Class<?>> classes = GeneratedCodecHarness.generateAndLoadAll();
        assertThat(classes).as("Generated classes map must not be empty").isNotEmpty();

        // Every spec must either have a generated compilable class, or have
        // validVersions="none" in its JSON file. validVersions="none" means all wire
        // versions of that API were removed in Kafka 4.x (e.g. ControlledShutdown,
        // LeaderAndIsr, StopReplica, UpdateMetadata) - the broker no longer supports
        // them at all. KrpcGenerator skips those specs intentionally. Any other gap
        // is a code-generation bug and will cause this assertion to fail with a clear
        // message identifying the offending spec.
        List<String> allSpecNames = GeneratedCodecHarness.allSpecNames();
        for (String specName : allSpecNames) {
            String dataClassName = dataClassNameFor(specName);
            if (!classes.containsKey(dataClassName)) {
                String specContent = Files.readString(
                        GeneratedCodecHarness.specDirectory().resolve(specName + ".json"));
                assertThat(specContent)
                        .as("Spec '%s' was not generated but does not declare validVersions=\"none\". "
                                + "This is a code-generation bug - the template cannot handle this spec.",
                                specName)
                        .contains("\"validVersions\": \"none\"");
            }
        }
    }

    /** Mirrors {@code MessageSpec.dataClassName()}: "Data" suffix only for Request/Response/Header specs. */
    private static String dataClassNameFor(String specName) {
        if (specName.endsWith("Request") || specName.endsWith("Response") || specName.endsWith("Header")) {
            return specName + "Data";
        }
        return specName;
    }
}
