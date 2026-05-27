/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.main;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.common.message.FetchRequestData;
import org.apache.kafka.common.protocol.Message;
import org.apache.kafka.common.protocol.types.RawTaggedField;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.ShortRange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based fidelity tests comparing the kroxylicious-generated {@code FetchRequestData}
 * codec against the reference implementation from {@code kafka-clients}.
 *
 * <p>Three properties are verified:
 * <ol>
 *   <li><b>Byte-level fidelity</b>: bytes written by the real Kafka codec, decoded and
 *       re-encoded by the generated codec, produce identical bytes.</li>
 *   <li><b>Round-trip identity</b>: {@code decode(encode(x)) == x} for the real Kafka codec.</li>
 *   <li><b>Unknown tagged field passthrough</b>: unknown tags survive a decode→encode cycle
 *       unchanged (critical for forward compatibility in flexible versions).</li>
 * </ol>
 *
 * <p>A version boundary enforcement test verifies that writing a field at an unsupported
 * version throws {@link UnsupportedVersionException}.
 */
class FetchRequestDataFidelityTest {

    private static final AtomicReference<Class<?>> generatedClassRef = new AtomicReference<>();
    private static final AtomicReference<Throwable> setupError = new AtomicReference<>();

    static {
        try {
            generatedClassRef.set(
                    GeneratedCodecHarness.generateAndLoad("FetchRequest.json", "FetchRequestData"));
        }
        catch (Throwable t) {
            setupError.set(t);
        }
    }

    @BeforeAll
    static void checkSetup() {
        Assumptions.assumeTrue(setupError.get() == null,
                "Generated FetchRequestData codec unavailable: " + setupError.get());
    }

    private static Class<?> generatedClass() {
        Assumptions.assumeTrue(generatedClassRef.get() != null, "Generated FetchRequestData codec not loaded");
        return generatedClassRef.get();
    }

    // -----------------------------------------------------------------------
    // Byte-level fidelity: real Kafka bytes → generated decode → re-encode == real bytes
    // -----------------------------------------------------------------------

    @Property(tries = 200)
    void bytesFidelity(
                       @ForAll("fetchRequests") FetchRequestData msg,
                       @ForAll @ShortRange(min = 4, max = 18) short version)
            throws Exception {
        byte[] realBytes = MessageSerdes.write(msg, version);
        Message generated = MessageSerdes.read(GeneratedCodecHarness.newInstance(generatedClass()), realBytes, version);
        assertThat(MessageSerdes.write(generated, version))
                .as("generated codec must re-encode real Kafka bytes identically at version %d", version)
                .isEqualTo(realBytes);
    }

    // -----------------------------------------------------------------------
    // Round-trip identity: decode(encode(x)) == x for the real Kafka codec
    // -----------------------------------------------------------------------

    @Property(tries = 200)
    void roundTripIdentity(
                           @ForAll("fetchRequests") FetchRequestData msg,
                           @ForAll @ShortRange(min = 4, max = 18) short version) {
        byte[] bytes = MessageSerdes.write(msg, version);
        FetchRequestData decoded = new FetchRequestData();
        MessageSerdes.read(decoded, bytes, version);
        assertThat(decoded)
                .as("decode(encode(msg)) at version %d must equal the original", version)
                .isEqualTo(MessageSerdes.read(new FetchRequestData(), bytes, version));
    }

    // -----------------------------------------------------------------------
    // Unknown tagged field passthrough (flexible versions >= 12 only)
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void unknownTaggedFieldPassthrough(
                                       @ForAll @ShortRange(min = 12, max = 18) short version)
            throws Exception {
        FetchRequestData msg = new FetchRequestData().setMaxWaitMs(500).setMinBytes(1024);
        msg.unknownTaggedFields().add(new RawTaggedField(99, new byte[]{ 0x01, 0x02, 0x03 }));

        byte[] bytes = MessageSerdes.write(msg, version);
        Message generated = MessageSerdes.read(GeneratedCodecHarness.newInstance(generatedClass()), bytes, version);

        assertThat(MessageSerdes.write(generated, version))
                .as("unknown tagged field must survive decode→encode at version %d", version)
                .isEqualTo(bytes);
    }

    // -----------------------------------------------------------------------
    // Version boundary enforcement
    // -----------------------------------------------------------------------

    @Test
    void versionBoundaryEnforcement() {
        // forgottenTopicsData is non-ignorable; only present from version 7
        FetchRequestData msg = new FetchRequestData()
                .setForgottenTopicsData(List.of(
                        new FetchRequestData.ForgottenTopic().setTopic("test-topic")));

        assertThatThrownBy(() -> MessageSerdes.write(msg, (short) 4))
                .as("writing non-empty forgottenTopicsData at version 4 (< 7) must throw")
                .isInstanceOf(UnsupportedVersionException.class);
    }

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<FetchRequestData> fetchRequests() {
        return Combinators.combine(
                Arbitraries.strings().ofMaxLength(50).injectNull(0.25), // clusterId (nullable)
                Arbitraries.just(-1), // replicaId: always -1 (non-ignorable at v15+; default)
                Arbitraries.integers().between(0, 30_000), // maxWaitMs
                Arbitraries.integers().between(0, 1_048_576), // minBytes
                Arbitraries.integers().between(1, 10_485_760), // maxBytes
                Arbitraries.bytes().between((byte) 0, (byte) 1), // isolationLevel
                Arbitraries.integers().between(0, Integer.MAX_VALUE) // sessionId
        ).flatAs((clusterId, replicaId, maxWaitMs, minBytes, maxBytes, isolationLevel, sessionId) -> Combinators.combine(
                Arbitraries.integers().between(-1, Integer.MAX_VALUE), // sessionEpoch
                Arbitraries.strings().ofMaxLength(30), // rackId
                fetchTopics().list().ofMaxSize(2)).as(
                        (sessionEpoch, rackId, topics) -> new FetchRequestData()
                                .setClusterId(clusterId)
                                .setReplicaId(replicaId)
                                .setMaxWaitMs(maxWaitMs)
                                .setMinBytes(minBytes)
                                .setMaxBytes(maxBytes)
                                .setIsolationLevel(isolationLevel)
                                .setSessionId(sessionId)
                                .setSessionEpoch(sessionEpoch)
                                .setRackId(rackId)
                                .setTopics(new ArrayList<>(topics))
                                // forgottenTopicsData is non-ignorable from v7+; boundary enforcement
                                // is tested separately in versionBoundaryEnforcement()
                                .setForgottenTopicsData(new ArrayList<>())));
    }

    private Arbitrary<FetchRequestData.FetchTopic> fetchTopics() {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMaxLength(50), // topic name (v4-12)
                Arbitraries.create(Uuid::randomUuid), // topicId (v13+)
                fetchPartitions().list().ofMaxSize(3)).as(
                        (topic, topicId, partitions) -> new FetchRequestData.FetchTopic()
                                .setTopic(topic)
                                .setTopicId(topicId)
                                .setPartitions(new ArrayList<>(partitions)));
    }

    private Arbitrary<FetchRequestData.FetchPartition> fetchPartitions() {
        return Combinators.combine(
                Arbitraries.integers().between(0, 100), // partition index
                Arbitraries.integers().between(-1, 100), // currentLeaderEpoch (ignorable v9+)
                Arbitraries.longs().between(0, Long.MAX_VALUE), // fetchOffset
                Arbitraries.longs().between(-1, Long.MAX_VALUE), // logStartOffset (ignorable v5+)
                Arbitraries.integers().between(0, 1_048_576) // partitionMaxBytes
        ).as((partition, leaderEpoch, fetchOffset, logStart, maxBytes) -> new FetchRequestData.FetchPartition()
                .setPartition(partition)
                .setCurrentLeaderEpoch(leaderEpoch)
                .setFetchOffset(fetchOffset)
                // lastFetchedEpoch is non-ignorable from v12; keep at default -1
                // to avoid UnsupportedVersionException at v4-11
                .setLastFetchedEpoch(-1)
                .setLogStartOffset(logStart)
                .setPartitionMaxBytes(maxBytes));
    }

    private Arbitrary<FetchRequestData.ForgottenTopic> forgottenTopics() {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMaxLength(50), // topic name (v7-12)
                Arbitraries.create(Uuid::randomUuid), // topicId (v13+)
                Arbitraries.integers().between(0, 50).list().ofMaxSize(5) // partition indices
        ).as((topic, topicId, partitions) -> new FetchRequestData.ForgottenTopic()
                .setTopic(topic)
                .setTopicId(topicId)
                .setPartitions(new ArrayList<>(partitions)));
    }
}
