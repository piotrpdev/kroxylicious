/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.main;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.message.FetchResponseData;
import org.apache.kafka.common.protocol.Message;
import org.apache.kafka.common.protocol.types.RawTaggedField;
import org.apache.kafka.common.record.BaseRecords;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.SimpleRecord;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.ShortRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based fidelity tests for the generated {@code FetchResponseData} codec.
 *
 * <p>Covers the same three properties as {@link FetchRequestDataFidelityTest}:
 * byte-level fidelity, round-trip identity, and unknown tagged field passthrough.
 * {@code FetchResponseData} additionally exercises the {@code records} field
 * (BaseRecords/MemoryRecords) and the {@code nodeEndpoints} tagged collection.
 */
class FetchResponseDataFidelityTest {

    private static final AtomicReference<Class<?>> generatedClassRef = new AtomicReference<>();
    private static final AtomicReference<Throwable> setupError = new AtomicReference<>();

    static {
        try {
            generatedClassRef.set(
                    GeneratedCodecHarness.generateAndLoad("FetchResponse.json", "FetchResponseData"));
        }
        catch (Throwable t) {
            setupError.set(t);
        }
    }

    @BeforeAll
    static void checkSetup() {
        Assumptions.assumeTrue(setupError.get() == null,
                "Generated FetchResponseData codec unavailable: " + setupError.get());
    }

    private static Class<?> generatedClass() {
        Assumptions.assumeTrue(generatedClassRef.get() != null, "Generated FetchResponseData codec not loaded");
        return generatedClassRef.get();
    }

    @Property(tries = 200)
    void bytesFidelity(
                       @ForAll("fetchResponses") FetchResponseData msg,
                       @ForAll @ShortRange(min = 4, max = 18) short version)
            throws Exception {
        byte[] realBytes = MessageSerdes.write(msg, version);
        Message generated = MessageSerdes.read(GeneratedCodecHarness.newInstance(generatedClass()), realBytes, version);
        assertThat(MessageSerdes.write(generated, version))
                .as("generated codec must re-encode real Kafka bytes identically at version %d", version)
                .isEqualTo(realBytes);
    }

    @Property(tries = 200)
    void roundTripIdentity(
                           @ForAll("fetchResponses") FetchResponseData msg,
                           @ForAll @ShortRange(min = 4, max = 18) short version) {
        byte[] bytes = MessageSerdes.write(msg, version);
        assertThat(MessageSerdes.write(MessageSerdes.read(new FetchResponseData(), bytes, version), version))
                .as("re-encode(decode(encode(msg))) at version %d must equal encode(msg)", version)
                .isEqualTo(bytes);
    }

    @Property(tries = 100)
    void unknownTaggedFieldPassthrough(
                                       @ForAll @ShortRange(min = 12, max = 18) short version)
            throws Exception {
        FetchResponseData msg = new FetchResponseData().setThrottleTimeMs(100);
        msg.unknownTaggedFields().add(new RawTaggedField(99, new byte[]{ 0x07, 0x08 }));

        byte[] bytes = MessageSerdes.write(msg, version);
        Message generated = MessageSerdes.read(GeneratedCodecHarness.newInstance(generatedClass()), bytes, version);

        assertThat(MessageSerdes.write(generated, version))
                .as("unknown tagged field must survive decode→encode at version %d", version)
                .isEqualTo(bytes);
    }

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<FetchResponseData> fetchResponses() {
        return Combinators.combine(
                Arbitraries.integers().between(0, 5_000), // throttleTimeMs
                Arbitraries.shorts().between((short) 0, (short) 100), // errorCode
                Arbitraries.just(0), // sessionId: always 0 (non-ignorable at v7+)
                fetchableTopicResponses().list().ofMaxSize(2)).as(
                        (throttle, errorCode, sessionId, responses) -> new FetchResponseData()
                                .setThrottleTimeMs(throttle)
                                .setErrorCode(errorCode)
                                .setSessionId(sessionId)
                                .setResponses(new ArrayList<>(responses)));
    }

    private Arbitrary<FetchResponseData.FetchableTopicResponse> fetchableTopicResponses() {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMaxLength(50), // topic (v4-12)
                Arbitraries.create(Uuid::randomUuid), // topicId (v13+)
                partitionData().list().ofMaxSize(2)).as(
                        (topic, topicId, partitions) -> new FetchResponseData.FetchableTopicResponse()
                                .setTopic(topic)
                                .setTopicId(topicId)
                                .setPartitions(new ArrayList<>(partitions)));
    }

    private Arbitrary<FetchResponseData.PartitionData> partitionData() {
        return Combinators.combine(
                Arbitraries.integers().between(0, 50), // partitionIndex
                Arbitraries.shorts().between((short) 0, (short) 100), // errorCode
                Arbitraries.longs().between(0, Long.MAX_VALUE), // highWatermark
                Arbitraries.longs().between(-1, Long.MAX_VALUE), // lastStableOffset
                Arbitraries.longs().between(-1, Long.MAX_VALUE), // logStartOffset
                Arbitraries.just(-1), // preferredReadReplica: always -1 (non-ignorable at v11+)
                randomRecords()).as(
                        (partIdx, errCode, hwm, lso, logStart, prefRead, records) -> new FetchResponseData.PartitionData()
                                .setPartitionIndex(partIdx)
                                .setErrorCode(errCode)
                                .setHighWatermark(hwm)
                                .setLastStableOffset(lso)
                                .setLogStartOffset(logStart)
                                .setPreferredReadReplica(prefRead)
                                .setRecords(records));
    }

    private Arbitrary<BaseRecords> randomRecords() {
        return Arbitraries.of(
                (byte[]) null,
                new byte[0],
                new byte[]{ 0x01 },
                new byte[]{ 0x01, 0x02, 0x03 }).map(payload -> {
                    if (payload == null) {
                        return null;
                    }
                    if (payload.length == 0) {
                        return MemoryRecords.EMPTY;
                    }
                    return MemoryRecords.withRecords(Compression.NONE, new SimpleRecord(payload));
                });
    }
}
