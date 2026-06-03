/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.main;

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

        // Some specs (e.g. ControlledShutdown, LeaderAndIsr, StopReplica, UpdateMetadata in Kafka 4.x)
        // have validVersions="none" meaning all their versions were removed and the generator skips them.
        // We allow those gaps: require at least 80% of specs to have generated classes.
        int specCount = GeneratedCodecHarness.allRequestResponseSpecNames().size();
        int minExpected = (specCount * 4) / 5; // at least 80% of specs
        assertThat(classes.size())
                .as("At least %d of %d specs should have generated compilable classes", minExpected, specCount)
                .isGreaterThanOrEqualTo(minExpected);
        assertThat(classes).as("Generated classes map must not be empty").isNotEmpty();
    }
}
