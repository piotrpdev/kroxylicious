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
 * compilable Java source code for ALL Kafka message specs, covering all field types
 * (including {@code uint16}, {@code bytes}, {@code float64}, {@code records}) and
 * struct patterns ({@code commonStructs}, keyed collections, nested tagged fields).
 *
 * <p><strong>Kafka version tracking.</strong> The spec JSON files are extracted from
 * the {@code kafka-clients} jar at build time. This test acts as a canary: if a
 * kafka-clients upgrade introduces new spec files or removes existing ones, the
 * {@code specCountMatchesKafkaClientsVersion} test will fail and force an explicit
 * review.  Update {@code EXPECTED_SPEC_COUNT} when bumping kafka-clients.
 */
class AllDataClassesCompileTest {

    /**
     * Total number of JSON spec files expected in the message-specs directory for
     * kafka-clients {@code 4.2.0}.  Update this constant when bumping kafka-clients —
     * a change here is a deliberate acknowledgement that new/removed APIs have been
     * reviewed and the template tested against them.
     */
    private static final int EXPECTED_SPEC_COUNT = 198;

    /**
     * Guards against silent spec drift when kafka-clients is bumped.  If Kafka adds
     * or removes API specs without this test being updated, the build fails loudly
     * rather than silently accepting an incomplete picture.
     */
    @Test
    void specCountMatchesKafkaClientsVersion() throws Exception {
        int actual = GeneratedCodecHarness.allSpecNames().size();
        assertThat(actual)
                .as("Spec count has changed from the expected %d. "
                        + "If you bumped kafka-clients, update EXPECTED_SPEC_COUNT in this class "
                        + "after reviewing added/removed specs and running the fidelity tests.",
                        EXPECTED_SPEC_COUNT)
                .isEqualTo(EXPECTED_SPEC_COUNT);
    }

    @Test
    void allSpecsGenerateCompilableCode() throws Exception {
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
            String dataClassName = GeneratedCodecHarness.dataClassNameFor(specName);
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

}
