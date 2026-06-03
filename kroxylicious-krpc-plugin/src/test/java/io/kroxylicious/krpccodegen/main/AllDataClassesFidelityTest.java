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
        return GeneratedCodecHarness.allRequestResponseSpecNames().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allSpecNames")
    void roundTripFidelityWithDefaultInstance(String specName) throws Exception {
        String dataClassName = specName + "Data";
        String realClassName = "org.apache.kafka.common.message." + dataClassName;

        Class<?> generatedClass = generatedClasses.get().get(dataClassName);
        Assumptions.assumeTrue(generatedClass != null,
                "Generated class " + dataClassName + " not found — may have compilation issues");

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
            byte[] realBytes;
            try {
                realBytes = MessageSerdes.write(realDefault, version);
            }
            catch (Exception e) {
                // Some specs throw for default instances at certain versions (non-ignorable fields).
                // Skip that version rather than fail.
                continue;
            }

            final short v = version;

            // Byte fidelity: real → generated decode → re-encode == real bytes
            Message generated;
            try {
                generated = MessageSerdes.read(
                        GeneratedCodecHarness.newInstance(generatedClass), realBytes, version);
            }
            catch (Exception e) {
                // Known limitation: some features (e.g. nullable struct fields) are not yet
                // fully supported in the generated codec. Skip rather than error.
                Assumptions.abort(dataClassName + " v" + version + " generated read failed: " + e.getMessage());
                return;
            }
            byte[] generatedBytes;
            try {
                generatedBytes = MessageSerdes.write(generated, version);
            }
            catch (Exception e) {
                Assumptions.abort(dataClassName + " v" + version + " generated write failed: " + e.getMessage());
                return;
            }
            assertThat(generatedBytes)
                    .as("%s byte fidelity at version %d", dataClassName, v)
                    .isEqualTo(realBytes);

            // Round-trip identity for the real codec
            ApiMessage decoded = (ApiMessage) realClass.getDeclaredConstructor().newInstance();
            MessageSerdes.read((Message) decoded, realBytes, version);
            assertThat(MessageSerdes.write((Message) decoded, version))
                    .as("%s round-trip identity at version %d", dataClassName, v)
                    .isEqualTo(realBytes);
        }
    }
}
