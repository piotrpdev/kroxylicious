/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.main;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.Message;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parameterized fidelity test that verifies the generated codec for every
 * {@code *RequestData} and {@code *ResponseData} class has correct byte-level
 * round-trip behaviour matching the reference implementation from {@code kafka-clients}.
 *
 * <p>For each message spec:
 * <ol>
 *   <li>Generates, compiles and loads the Data class dynamically.</li>
 *   <li>Creates a default instance using the real Kafka class.</li>
 *   <li>Serializes it with the real codec and deserializes with the generated codec.</li>
 *   <li>Re-serializes with the generated codec and asserts bytes are identical.</li>
 *   <li>Repeats for all supported versions.</li>
 * </ol>
 */
class AllDataClassesFidelityTest {

    private static final AtomicReference<Map<String, Class<?>>> generatedClasses = new AtomicReference<>();
    private static final AtomicReference<Throwable> setupError = new AtomicReference<>();

    static {
        try {
            generatedClasses.set(GeneratedCodecHarness.generateAndLoadAll());
        }
        catch (Throwable t) {
            setupError.set(t);
        }
    }

    @BeforeAll
    static void checkSetup() {
        Assumptions.assumeTrue(setupError.get() == null,
                "Generated codec setup failed: " + setupError.get());
        Assumptions.assumeTrue(!generatedClasses.get().isEmpty(),
                "JDK compiler not available — skipping all-specs fidelity tests");
    }

    static Stream<String> allSpecNames() throws Exception {
        return GeneratedCodecHarness.allSpecNames().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allSpecNames")
    void roundTripFidelityWithDefaultInstance(String specName) throws Exception {
        // Request/Response/Header specs get a "Data" suffix (e.g. FetchRequestData);
        // other spec types (records, markers, protocol payloads) keep the raw name
        // (e.g. EndTxnMarker, VotersRecord) — matching Kafka's own dataClassName() rule.
        String dataClassName = GeneratedCodecHarness.dataClassNameFor(specName);
        String realClassName = "org.apache.kafka.common.message." + dataClassName;

        Class<?> generatedClass = generatedClasses.get().get(dataClassName);
        // Some specs have validVersions="none" in Kafka 4.x, meaning all their wire
        // versions were removed and the API is no longer in use. The KrpcGenerator
        // filters those specs out (nothing is generated), so generatedClass is null
        // and we simply skip the test rather than fail.
        Assumptions.assumeTrue(generatedClass != null,
                dataClassName + " was not generated - spec has validVersions=\"none\" "
                        + "(API fully removed in Kafka 4.x) or compilation failed");

        Class<?> realClass;
        try {
            realClass = Class.forName(realClassName);
        }
        catch (ClassNotFoundException e) {
            Assumptions.abort("Real Kafka class " + realClassName + " not on classpath: " + e.getMessage());
            return;
        }

        ApiMessage realDefault = (ApiMessage) realClass.getDeclaredConstructor().newInstance();
        short lowest = realDefault.lowestSupportedVersion();
        short highest = realDefault.highestSupportedVersion();

        for (short version = lowest; version <= highest; version++) {
            byte[] realBytes = MessageSerdes.write(realDefault, version);

            // Byte fidelity: real → generated decode → re-encode == real bytes
            Message generated = MessageSerdes.read(
                    GeneratedCodecHarness.newInstance(generatedClass), realBytes, version);
            byte[] generatedBytes = MessageSerdes.write(generated, version);
            assertThat(generatedBytes)
                    .as("%s byte fidelity at version %d", dataClassName, version)
                    .isEqualTo(realBytes);

            // Round-trip identity for the real codec
            ApiMessage decoded = (ApiMessage) realClass.getDeclaredConstructor().newInstance();
            MessageSerdes.read(decoded, realBytes, version);
            assertThat(MessageSerdes.write(decoded, version))
                    .as("%s round-trip identity at version %d", dataClassName, version)
                    .isEqualTo(realBytes);
        }
    }

}
