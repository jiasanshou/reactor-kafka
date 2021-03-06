/*
 * Copyright (c) 2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.kafka.receiver.internals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidOffsetException;
import org.junit.Before;
import org.junit.Test;

import reactor.core.Cancellation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.mock.MockCluster;
import reactor.kafka.mock.MockConsumer;
import reactor.kafka.receiver.Receiver;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverPartition;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.util.TestUtils;
import reactor.test.StepVerifier;
import reactor.test.StepVerifier.Step;

/**
 * Kafka receiver tests using mock Kafka consumers.
 *
 */
public class KafkaReceiverTest {

    private final String groupId = "test-group";
    private final Queue<ConsumerRecord<Integer, String>> receivedMessages = new ConcurrentLinkedQueue<>();
    private final List<ConsumerRecord<Integer, String>> uncommittedMessages = new ArrayList<>();
    private Map<TopicPartition, Long> receiveStartOffsets = new HashMap<>();
    private final Set<TopicPartition> assignedPartitions = new HashSet<>();

    private Map<Integer, String> topics;
    private String topic;
    private MockCluster cluster;
    private MockConsumer.Pool consumerFactory;
    private MockConsumer consumer;
    private ReceiverOptions<Integer, String> receiverOptions;

    @Before
    public void setUp() {
        topics = new HashMap<>();
        for (int i : Arrays.asList(1, 2, 20, 200))
            topics.put(i, "topic" + i);
        topic = topics.get(2);
        cluster = new MockCluster(2, topics);
        receiverOptions = ReceiverOptions.<Integer, String>create()
                .consumerProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId)
                .consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .addAssignListener(partitions -> {
                        for (ReceiverPartition p : partitions)
                            assignedPartitions.add(p.topicPartition());
                    })
                .addRevokeListener(partitions -> {
                        for (ReceiverPartition p : partitions)
                            assignedPartitions.remove(p.topicPartition());
                    });
        consumer = new MockConsumer(cluster, false);
        consumerFactory = new MockConsumer.Pool(Arrays.asList(consumer));

        for (TopicPartition partition : cluster.partitions())
            receiveStartOffsets.put(partition, 0L);
    }

    /**
     * Tests that a consumer is created when the inbound flux is subscribed to and
     * closed when the flux terminates.
     */
    @Test
    public void consumerLifecycle() {
        sendMessages(topic, 0, 1);
        receiverOptions = receiverOptions.subscription(Collections.singleton(topic));
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<>(consumerFactory, receiverOptions);
        assertEquals(0, consumerFactory.consumersInUse().size());
        Flux<ReceiverRecord<Integer, String>> flux = receiver.receive();
        assertEquals(0, consumerFactory.consumersInUse().size());
        Cancellation c = flux.subscribe();
        TestUtils.waitUntil("Consumer not created using factory", null, f -> f.consumersInUse().size() > 0, consumerFactory, Duration.ofMillis(500));
        assertEquals(Arrays.asList(consumer), consumerFactory.consumersInUse());
        assertFalse("Consumer closed", consumer.closed());
        c.dispose();
        assertTrue("Consumer closed", consumer.closed());
    }

    /**
     * Send and receive one message.
     */
    @Test
    public void receiveOne() {
        receiverOptions = receiverOptions.subscription(Collections.singleton(topic));
        sendReceiveAndVerify(1, 1);
    }

    /**
     * Send and receive messages from multiple partitions using one receiver.
     */
    @Test
    public void receiveMultiplePartitions() {
        receiverOptions = receiverOptions.subscription(Collections.singleton(topic));
        sendReceiveAndVerify(10, 10);
    }

    /**
     * Tests that assign callbacks are invoked before any records are delivered
     * when partitions are assigned using group management.
     */
    @Test
    public void assignCallback() {
        receiverOptions = receiverOptions.subscription(Collections.singleton(topic));
        sendMessages(topic, 0, 10);
        receiveAndVerify(10, r -> assertTrue("Assign callback not invoked", assignedPartitions.contains(r.offset().topicPartition())));
    }

    /**
     * Consume from first available offset of partitions by seeking to start of all partitions in the assign listener.
     */
    @Test
    public void seekToBeginning() throws Exception {
        sendMessages(topic, 0, 10);
        Semaphore assignSemaphore = new Semaphore(0);
        receiverOptions = receiverOptions
                .consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .addAssignListener(partitions -> {
                        for (ReceiverPartition p : partitions)
                            p.seekToBeginning();
                        assignSemaphore.release();
                    })
                .subscription(Collections.singleton(topic));
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<>(consumerFactory, receiverOptions);
        receiveWithOneOffAction(receiver, 10, 10, () -> sendMessages(topic, 10, 20));
        assertTrue("Assign callback not invoked", assignSemaphore.tryAcquire(1, TimeUnit.SECONDS));
    }

    /**
     * Consume from latest offsets of partitions by seeking to end of all partitions in the assign listener.
     */
    @Test
    public void seekToEnd() throws Exception {
        sendMessages(topic, 0, 10);
        Semaphore assignSemaphore = new Semaphore(0);
        receiverOptions = receiverOptions
                .addAssignListener(partitions -> {
                        for (ReceiverPartition p : partitions)
                            p.seekToEnd();
                        assignSemaphore.release();
                    })
                .subscription(Collections.singleton(topic));

        for (TopicPartition partition : cluster.partitions(topic))
            receiveStartOffsets.put(partition, (long) cluster.log(partition).size());
        CountDownLatch latch = new CountDownLatch(10);
        Cancellation cancellation = asyncReceive(latch);
        assertTrue("Assign callback not invoked", assignSemaphore.tryAcquire(1, TimeUnit.SECONDS));

        sendMessages(topic, 10, 20);
        assertTrue("Messages not received", latch.await(1, TimeUnit.SECONDS));
        verifyMessages(10);
        cancellation.dispose();
    }

    /**
     * Consume from specific offsets of partitions by seeking to offset in the assign listener.
     */
    @Test
    public void seekToOffset() throws Exception {
        sendMessages(topic, 0, 10);
        long startOffset = 2;
        Semaphore assignSemaphore = new Semaphore(0);
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic))
                .addAssignListener(partitions -> {
                        for (ReceiverPartition p : partitions)
                            p.seek(startOffset);
                        assignSemaphore.release();
                    });
        int receiveCount = 10;
        for (TopicPartition partition : cluster.partitions(topic)) {
            receiveStartOffsets.put(partition, startOffset);
            receiveCount += cluster.log(partition).size() - startOffset;
        }
        CountDownLatch latch = new CountDownLatch(receiveCount);
        Cancellation cancellation = asyncReceive(latch);
        assertTrue("Assign callback not invoked", assignSemaphore.tryAcquire(1, TimeUnit.SECONDS));

        sendMessages(topic, 10, 20);
        assertTrue("Messages not received", latch.await(1, TimeUnit.SECONDS));
        verifyMessages(receiveCount);
        cancellation.dispose();
    }

    /**
     * Tests that failure in seek in the assign listener terminates the inbound flux with an error.
     */
    @Test
    public void seekFailure() throws Exception {
        sendMessages(topic, 0, 10);
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic))
                .addAssignListener(partitions -> {
                        for (ReceiverPartition p : partitions)
                            p.seek(20);
                    })
                .subscription(Collections.singleton(topic));
        receiveVerifyError(InvalidOffsetException.class, r -> { });
    }

    /**
     * Send and receive using manual assignment of partitions.
     */
    @Test
    public void manualAssignment() {
        receiverOptions = receiverOptions.assignment(cluster.partitions(topic));
        sendMessages(topic, 0, 10);
        receiveAndVerify(10, r -> assertTrue("Assign callback not invoked", assignedPartitions.contains(r.offset().topicPartition())));
    }

    /**
     * Send and receive using wildcard subscription with group management.
     */
    @Test
    public void wildcardSubscription() {
        receiverOptions = receiverOptions.subscription(Pattern.compile("[a-z]*2"));
        sendReceiveAndVerify(10, 10);
    }

    /**
     * Tests {@link Receiver#receiveAtmostOnce()} good path without failures.
     */
    @Test
    public void atmostOnce() {
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic));
        sendMessages(topic, 0, 20);
        Flux<ConsumerRecord<Integer, String>> inboundFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                .receiveAtmostOnce()
                .filter(r -> cluster.committedOffset(groupId, topicPartition(r)) >= r.offset());
        verifyMessages(inboundFlux.take(10), 10);
        verifyCommits(groupId, topic, 10);
    }

    /**
     * Tests {@link Receiver#receiveAtmostOnce()} with commit-ahead.
     */
    @Test
    public void atmostOnceCommitAheadSize() {
        int commitAhead = 5;
        receiverOptions = receiverOptions
                .atmostOnceCommitAheadSize(commitAhead)
                .subscription(Collections.singleton(topic));
        sendMessages(topic, 0, 50);
        Map<TopicPartition, Long> consumedOffsets = new HashMap<>();
        Flux<ConsumerRecord<Integer, String>> inboundFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                .receiveAtmostOnce()
                .filter(r -> {
                        long committed = cluster.committedOffset(groupId, topicPartition(r));
                        return committed >= r.offset() && committed <= r.offset() + commitAhead + 1;
                    })
                .doOnNext(r -> consumedOffsets.put(new TopicPartition(r.topic(), r.partition()), r.offset()));
        int consumeCount = 17;
        StepVerifier.create(inboundFlux, consumeCount)
            .recordWith(() -> receivedMessages)
            .expectNextCount(consumeCount)
            .thenCancel()
            .verify();
        verifyMessages(consumeCount);
        for (int i = 0; i < cluster.partitions(topic).size(); i++) {
            TopicPartition topicPartition = new TopicPartition(topic, i);
            long consumed = consumedOffsets.get(topicPartition);
            consumerFactory.addConsumer(new MockConsumer(cluster, true));
            receiverOptions = receiverOptions.assignment(Collections.singleton(topicPartition));
            inboundFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                    .receiveAtmostOnce();
            StepVerifier.create(inboundFlux, 1)
                .expectNextMatches(r -> r.offset() > consumed && r.offset() <= consumed + commitAhead + 1)
                .thenCancel()
                .verify();
        }

    }

    /**
     * Tests that transient commit failures are retried with {@link Receiver#receiveAtmostOnce()}.
     */
    @Test
    public void atmostOnceCommitAttempts() throws Exception {
        consumer.addCommitException(new RetriableCommitFailedException("coordinator failed"), 2);
        receiverOptions = receiverOptions
                .maxCommitAttempts(10)
                .subscription(Collections.singletonList(topic));

        sendMessages(topic, 0, 20);
        Flux<ConsumerRecord<Integer, String>> inboundFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                .receiveAtmostOnce();
        verifyMessages(inboundFlux.take(10), 10);
        verifyCommits(groupId, topic, 10);
    }

    /**
     * Tests that {@link Receiver#receiveAtmostOnce()} commit failures terminate the inbound flux with
     * an error.
     */
    @Test
    public void atmostOnceCommitFailure() throws Exception {
        consumer.addCommitException(new RetriableCommitFailedException("coordinator failed"), 10);
        int count = 10;
        receiverOptions = receiverOptions
                .maxCommitAttempts(2)
                .subscription(Collections.singletonList(topic));
        sendMessages(topic, 0, count + 10);

        Flux<ConsumerRecord<Integer, String>> inboundFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                .receiveAtmostOnce();
        StepVerifier.create(inboundFlux)
            .expectError(RetriableCommitFailedException.class)
            .verify();
    }

    /**
     * Tests that messages are not redelivered if there are downstream message processing exceptions
     * with {@link Receiver#receiveAtmostOnce()}.
     */
    @Test
    public void atmostOnceMessageProcessingFailure() {
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic));
        sendMessages(topic, 0, 20);
        Flux<ConsumerRecord<Integer, String>> inboundFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                .receiveAtmostOnce()
                .doOnNext(r -> {
                        receiveStartOffsets.put(topicPartition(r), r.offset() + 1);
                        throw new RuntimeException("Test exception");
                    });
        StepVerifier.create(inboundFlux)
            .expectError(RuntimeException.class)
            .verify();

        consumerFactory.addConsumer(new MockConsumer(cluster, true));
        Flux<ConsumerRecord<Integer, String>> newFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                .receiveAtmostOnce();
        verifyMessages(newFlux.take(9), 9);
        verifyCommits(groupId, topic, 10);
    }

    /**
     * Tests good path auto-ack acknowledgement mode {@link Receiver#receiveAutoAck()}.
     */
    @Test
    public void autoAck() {
        receiverOptions = receiverOptions
                .consumerProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2)
                .commitBatchSize(1)
                .subscription(Collections.singleton(topic));
        sendMessages(topic, 0, 20);
        Flux<ConsumerRecord<Integer, String>> inboundFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                .receiveAutoAck()
                .concatMap(r -> r)
                .filter(r -> {
                        Long committed = cluster.committedOffset(groupId, topicPartition(r));
                        return committed == null || committed.longValue() <= r.offset();
                    });
        verifyMessages(inboundFlux.take(11), 11);
        receivedMessages.removeIf(r -> r.offset() >= 5); // Last record should not be committed
        verifyCommits(groupId, topic, 10);
    }

    /**
     * Tests that retriable commit exceptions are retried with {@link Receiver#receiveAutoAck()}
     */
    @Test
    public void autoAckCommitTransientError() {
        consumer.addCommitException(new RetriableCommitFailedException("coordinator failed"), 3);
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic))
                .maxCommitAttempts(5)
                .commitBatchSize(2);
        sendMessages(topic, 0, 20);
        Flux<ConsumerRecord<Integer, String>> inboundFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                .receiveAutoAck()
                .concatMap(r -> r);
        verifyMessages(inboundFlux.take(11), 11);
        receivedMessages.removeIf(r -> r.offset() >= 5); // Last record should not be committed
        verifyCommits(groupId, topic, 10);
    }

    /**
     * Tests that inbound flux is terminated with an error if transient commit error persists
     * beyond maximum configured limit.
     */
    @Test
    public void autoAckCommitTransientErrorMaxRetries() throws Exception {
        consumer.addCommitException(new RetriableCommitFailedException("coordinator failed"), 5);
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic))
                .maxCommitAttempts(5)
                .commitBatchSize(2);
        int count = 100;
        sendMessages(topic, 0, count);
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<>(consumerFactory, receiverOptions);
        Semaphore errorSemaphore = new Semaphore(0);
        receiver.receiveAutoAck()
                .concatMap(r -> r)
                .doOnNext(r -> receivedMessages.add(r))
                .doOnError(e -> errorSemaphore.release())
                .subscribe();
        assertTrue("Flux did not fail", errorSemaphore.tryAcquire(1, TimeUnit.SECONDS));
        assertTrue("Commit failure did not fail flux", receivedMessages.size() < count);
    }

    /**
     * Tests that inbound flux is terminated with an error if commit fails with non-retriable error.
     */
    @Test
    public void autoAckCommitFatalError() throws Exception {
        consumer.addCommitException(new InvalidOffsetException("invalid offset"), 1);
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic))
                .maxCommitAttempts(5)
                .commitBatchSize(2);
        int count = 100;
        sendMessages(topic, 0, count);
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<>(consumerFactory, receiverOptions);
        Semaphore errorSemaphore = new Semaphore(0);
        receiver.receiveAutoAck()
                .concatMap(r -> r)
                .doOnNext(r -> receivedMessages.add(r))
                .doOnError(e -> errorSemaphore.release())
                .subscribe();
        assertTrue("Flux did not fail", errorSemaphore.tryAcquire(1, TimeUnit.SECONDS));
        assertTrue("Commit failure did not fail flux", receivedMessages.size() < count);
    }

    /**
     * Tests that only acknowledged offsets are committed with manual-ack using
     * {@link Receiver#receive()}.
     */
    @Test
    public void manualAck() {
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic))
                .commitBatchSize(1);
        Map<TopicPartition, Long> acknowledged = new HashMap<>();
        for (TopicPartition partition : cluster.partitions(topic))
            acknowledged.put(partition, -1L);
        sendMessages(topic, 0, 20);
        receiveAndVerify(10, r -> {
                ReceiverOffset offset = r.offset();
                TopicPartition partition = offset.topicPartition();
                Long committedOffset = cluster.committedOffset(groupId, partition);
                boolean valid = committedOffset == null || acknowledged.get(partition) >= committedOffset - 1;
                if (offset.offset() % 3 == 0) {
                    offset.acknowledge();
                    acknowledged.put(partition, offset.offset());
                }
                assertTrue("Unexpected commit state", valid);
            });
        for (Map.Entry<TopicPartition, Long> entry : acknowledged.entrySet()) {
            Long committedOffset = cluster.committedOffset(groupId, entry.getKey());
            assertEquals(entry.getValue() + 1, committedOffset.longValue());
        }
    }

    /**
     * Tests that acknowledged offsets are committed using the configured batch size.
     */
    @Test
    public void manualAckCommitBatchSize() {
        topic = topics.get(1);
        int batchSize = 4;
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic))
                .commitBatchSize(batchSize);
        sendMessages(topic, 0, 20);
        AtomicLong lastCommitted = new AtomicLong(-1);
        receiveAndVerify(15, r -> {
                long offset = r.offset().offset();
                if (offset < 10) {
                    r.offset().acknowledge();
                    if (((offset + 1) % batchSize) == 0)
                        lastCommitted.set(offset);
                } else
                    uncommittedMessages.add(r.record());
                verifyCommit(r, lastCommitted.get());
            });
        verifyCommits(groupId, topic, 10);
    }

    /**
     * Tests that acknowledged offsets are committed using the configured commit interval.
     */
    @Test
    public void manualAckCommitInterval() {
        topic = topics.get(1);
        Duration interval = Duration.ofMillis(500);
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic))
                .commitInterval(interval);
        sendMessages(topic, 0, 20);
        final int delayIndex = 5;
        AtomicLong lastCommitted = new AtomicLong(-1);
        receiveAndVerify(15, r -> {
                long offset = r.offset().offset();
                if (r.offset().offset() < 10) {
                    r.offset().acknowledge();
                    if (offset == delayIndex) {
                        TestUtils.sleep(interval.toMillis());
                        lastCommitted.set(offset);
                    }
                } else
                    uncommittedMessages.add(r.record());
                verifyCommit(r, lastCommitted.get());
            });
        verifyCommits(groupId, topic, 10);
    }


    /**
     * Tests that acknowledged offsets are committed using the configured commit interval
     * and the commit batch size if both are configured.
     */
    @Test
    public void manualAckCommitIntervalOrBatchSize() {
        Duration interval = Duration.ofMillis(500);
        topic = topics.get(1);
        int batchSize = 3;
        final int delayIndex = 5;
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic))
                .commitInterval(interval)
                .commitBatchSize(batchSize);
        sendMessages(topic, 0, 20);
        AtomicLong lastCommitted = new AtomicLong(-1);
        receiveAndVerify(15, r -> {
                long offset = r.offset().offset();
                if (offset < 10) {
                    r.offset().acknowledge();
                    if (offset == delayIndex) {
                        TestUtils.sleep(interval.toMillis());
                        lastCommitted.set(offset);
                    }
                    if (((offset + 1) % batchSize) == 0)
                        lastCommitted.set(offset);
                } else
                    uncommittedMessages.add(r.record());
                verifyCommit(r, lastCommitted.get());
            });

        verifyCommits(groupId, topic, 10);
    }
    /**
     * Tests that all acknowledged offsets are committed during graceful close.
     */
    @Test
    public void manualAckClose() throws Exception {
        receiverOptions = receiverOptions
                .subscription(Collections.singletonList(topic));
        sendMessages(topic, 0, 20);
        receiveAndVerify(20, r -> {
                if (r.offset().offset() < 5)
                    r.offset().acknowledge();
            });
        receivedMessages.removeIf(r -> r.offset() >= 5);
        consumerFactory.addConsumer(new MockConsumer(cluster, true));
        receiveAndVerify(10, r -> { });
    }

    /**
     * Tests manual commits for {@link Receiver#receive()} with asynchronous commits.
     * Tests that commits are completed when the flux is closed gracefully.
     */
    @Test
    public void manualCommitAsync() throws Exception {
        int count = 10;
        CountDownLatch commitLatch = new CountDownLatch(count);
        receiverOptions = receiverOptions
                .commitBatchSize(0)
                .commitInterval(Duration.ofMillis(Long.MAX_VALUE))
                .subscription(Collections.singletonList(topic));

        sendMessages(topic, 0, count + 10);
        receiveAndVerify(10, record -> {
                record.offset()
                      .commit()
                      .doOnSuccess(i -> commitLatch.countDown())
                      .subscribe();
            });
        verifyCommits(groupId, topic, 10);
        assertTrue("Offsets not committed", commitLatch.await(1, TimeUnit.SECONDS));
    }

    /**
     * Tests manual commits for {@link Receiver#receive()} with asynchronous commits
     * when there are no polls due to back-pressure.
     */
    @Test
    public void manualCommitAsyncNoPoll() throws Exception {
        int count = 10;
        receiverOptions = receiverOptions
                .commitBatchSize(0)
                .commitInterval(Duration.ofMillis(Long.MAX_VALUE))
                .subscription(Collections.singletonList(topic));

        sendMessages(topic, 0, count + 10);

        Semaphore commitSemaphore = new Semaphore(0);
        Flux<ReceiverRecord<Integer, String>> inboundFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                .receive()
                .doOnNext(record -> {
                        receivedMessages.add(record.record());
                        record.offset().commit().doOnSuccess(v -> commitSemaphore.release()).subscribe();
                    });
        StepVerifier.create(inboundFlux, 1)
                    .consumeNextWith(record -> {
                            try {
                                assertTrue("Commit did not complete", commitSemaphore.tryAcquire(5, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                fail("Interrupted");
                            }
                        })
                    .thenCancel()
                    .verify();

        verifyCommits(groupId, topic, 19);
    }

    /**
     * Tests manual commits for {@link Receiver#receive()} with synchronous commits
     * using {@link Mono#block()} when there are no polls due to back-pressure.
     */
    @Test
    public void manualCommitBlockNoPoll() throws Exception {
        int count = 10;
        receiverOptions = receiverOptions
                .commitBatchSize(0)
                .commitInterval(Duration.ofMillis(Long.MAX_VALUE))
                .subscription(Collections.singletonList(topic));

        sendMessages(topic, 0, count + 10);

        Flux<ReceiverRecord<Integer, String>> inboundFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                .receive();
        StepVerifier.create(inboundFlux, 1)
                    .consumeNextWith(record -> {
                            receivedMessages.add(record.record());
                            record.offset().commit().block();
                        })
                    .thenCancel()
                    .verify();

        verifyCommits(groupId, topic, 19);
    }

    /**
     * Tests manual commits for {@link Receiver#receive()} with synchronous commits
     * after message processing.
     */
    @Test
    public void manualCommitSync() throws Exception {
        int count = 10;
        receiverOptions = receiverOptions
                .commitBatchSize(0)
                .commitInterval(Duration.ofMillis(Long.MAX_VALUE))
                .subscription(Collections.singletonList(topic));

        sendMessages(topic, 0, count + 10);
        receiveAndVerify(10, record -> {
                StepVerifier.create(record.offset().commit()).expectComplete().verify();
            });
        verifyCommits(groupId, topic, 10);
    }

    /**
     * Tests that offsets that are not committed explicitly are not committed
     * on close and that uncommitted records are redelivered on the next receive.
     */
    @Test
    public void manualCommitClose() throws Exception {
        receiverOptions = receiverOptions
                .commitBatchSize(0)
                .commitInterval(Duration.ZERO)
                .subscription(Collections.singletonList(topic));
        sendMessages(topic, 0, 20);
        receiveAndVerify(20, r -> {
                if (r.offset().offset() < 5)
                    r.offset().commit().block();
            });
        receivedMessages.removeIf(r -> r.offset() >= 5);
        consumerFactory.addConsumer(new MockConsumer(cluster, true));
        receiveAndVerify(10, r -> { });
    }

    /**
     * Tests that all acknowledged records are committed on close
     */
    @Test
    public void autoCommitClose() throws Exception {
        receiverOptions = receiverOptions
                .commitBatchSize(100)
                .commitInterval(Duration.ofMillis(Long.MAX_VALUE))
                .subscription(Collections.singletonList(topic));
        sendMessages(topic, 0, 20);
        receiveAndVerify(20, r -> {
                if (r.offset().offset() < 5)
                    r.offset().acknowledge();
            });
        receivedMessages.removeIf(r -> r.offset() >= 5);
        consumerFactory.addConsumer(new MockConsumer(cluster, true));
        receiveAndVerify(10, r -> { });
    }

    /**
     * Tests that commits are disabled completely if periodic commits by batch size
     * and periodic commits by interval are both disabled.
     */
    @Test
    public void autoCommitDisable() throws Exception {
        receiverOptions = receiverOptions
                .commitBatchSize(0)
                .commitInterval(Duration.ZERO)
                .subscription(Collections.singletonList(topic));
        sendMessages(topic, 0, 20);
        receiveAndVerify(20, r -> { });
        receivedMessages.clear();
        consumerFactory.addConsumer(new MockConsumer(cluster, true));
        receiveAndVerify(20, r -> { });
    }

    /**
     * Tests that commits are retried if the failure is transient and the manual commit Mono
     * is not failed if the commit succeeds within the configured number of attempts.
     */
    @Test
    public void manualCommitAttempts() throws Exception {
        consumer.addCommitException(new RetriableCommitFailedException("coordinator failed"), 2);
        int count = 10;
        receiverOptions = receiverOptions
                .commitBatchSize(0)
                .commitInterval(Duration.ofMillis(Long.MAX_VALUE))
                .maxCommitAttempts(10)
                .subscription(Collections.singletonList(topic));

        sendMessages(topic, 0, count + 10);
        receiveAndVerify(10, record -> record.offset().commit().block());
        verifyCommits(groupId, topic, 10);
    }

    @Test
    public void manualCommitRetry() throws Exception {
        consumer.addCommitException(new RetriableCommitFailedException("coordinator failed"), 2);
        int count = 10;
        receiverOptions = receiverOptions
                .commitBatchSize(0)
                .commitInterval(Duration.ofMillis(Long.MAX_VALUE))
                .maxCommitAttempts(1)
                .subscription(Collections.singletonList(topic));

        sendMessages(topic, 0, count + 10);
        receiveAndVerify(10, record -> record.offset().commit().retry().block());
        verifyCommits(groupId, topic, 10);
    }

    /**
     * Tests that manual commit Mono is failed if commits did not succeed after a transient error
     * within the configured number of attempts.
     */
    @Test
    public void manualCommitFailure() throws Exception {
        consumer.addCommitException(new RetriableCommitFailedException("coordinator failed"), 10);
        int count = 10;
        receiverOptions = receiverOptions
                .commitBatchSize(0)
                .commitInterval(Duration.ofMillis(Long.MAX_VALUE))
                .maxCommitAttempts(2)
                .subscription(Collections.singletonList(topic));

        sendMessages(topic, 0, count + 10);
        receiveVerifyError(RetriableCommitFailedException.class, record -> {
                record.offset().commit().retry(5).block();
            });
    }

    /**
     * Tests that inbound flux can be resumed after an error and that uncommitted messages
     * are redelivered to the new flux.
     */
    @Test
    public void resumeAfterFailure() throws Exception {
        int count = 10;
        consumerFactory.addConsumer(new MockConsumer(cluster, true));
        receiverOptions = receiverOptions
                .subscription(Collections.singletonList(topic));
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<>(consumerFactory, receiverOptions);
        Flux<ReceiverRecord<Integer, String>> inboundFlux = receiver
                .receive()
                .doOnNext(record -> {
                        if (receivedMessages.size() == 2)
                            throw new RuntimeException("Failing onNext");
                    })
                .onErrorResumeWith(e -> receiver.receive().doOnSubscribe(s -> receivedMessages.clear()));

        sendMessages(topic, 0, count);
        receiveAndVerify(inboundFlux, 10);
    }

    /**
     * Tests that downstream exceptions terminate the inbound flux gracefully.
     */
    @Test
    public void messageProcessorFailure() throws Exception {
        int count = 10;
        receiverOptions = receiverOptions
                .subscription(Collections.singletonList(topic));

        sendMessages(topic, 0, count);
        receiveVerifyError(RuntimeException.class, record -> {
                receivedMessages.add(record.record());
                if (receivedMessages.size() == 1)
                    throw new RuntimeException("Failing onNext");
            });
        assertTrue("Consumer not closed", consumer.closed());
    }

    /**
     * Tests elastic scheduler with groupBy(partition) for a consumer processing large number of partitions.
     * <p/>
     * When there are a large number of partitions, groupBy(partition) with an elastic scheduler creates as many
     * threads as partitions unless the flux itself is bounded (here each partition flux is limited with take()).
     * In general, it may be better to group the partitions together in groupBy() to limit the number of threads
     * when using elastic scheduler with a large number of partitions
     */
    @Test
    public void groupByPartitionElasticScheduling() throws Exception {
        int countPerPartition = 50;
        topic = topics.get(20);
        int partitions = cluster.partitions(topic).size();
        CountDownLatch[] latch = new CountDownLatch[partitions];
        for (int i = 0; i < partitions; i++)
            latch[i] = new CountDownLatch(countPerPartition);
        Scheduler scheduler = Schedulers.newElastic("test-groupBy", 10, true);
        Map<String, Set<Integer>> threadMap = new ConcurrentHashMap<>();

        receiverOptions = receiverOptions.subscription(Collections.singletonList(topic));
        List<Cancellation> groupCancels = new ArrayList<>();
        Cancellation cancellation = new KafkaReceiver<>(consumerFactory, receiverOptions)
            .receive()
            .groupBy(m -> m.offset().topicPartition().partition())
            .subscribe(partitionFlux -> groupCancels.add(partitionFlux.take(countPerPartition).publishOn(scheduler, 1).subscribe(record -> {
                    String thread = Thread.currentThread().getName();
                    int partition = record.record().partition();
                    Set<Integer> partitionSet = threadMap.get(thread);
                    if (partitionSet == null) {
                        partitionSet = new HashSet<Integer>();
                        threadMap.put(thread, partitionSet);
                    }
                    partitionSet.add(partition);
                    receivedMessages.add(record.record());
                    latch[partition].countDown();
                })));

        try {
            sendMessagesToPartition(topic, 0, 0, countPerPartition);
            TestUtils.waitForLatch("Messages not received on partition 0", latch[0], Duration.ofSeconds(2));
            for (int i = 1; i < 10; i++)
                sendMessagesToPartition(topic, i, i * countPerPartition, countPerPartition);
            for (int i = 1; i < 10; i++)
                TestUtils.waitForLatch("Messages not received on partition " + i, latch[i], Duration.ofSeconds(10));
            assertTrue("Threads not allocated elastically " + threadMap, threadMap.size() > 1 && threadMap.size() <= 10);
            for (int i = 10; i < partitions; i++)
                sendMessagesToPartition(topic, i, i * countPerPartition, countPerPartition);
            for (int i = 10; i < partitions; i++)
                TestUtils.waitForLatch("Messages not received on partition " + i, latch[i], Duration.ofSeconds(10));
            assertTrue("Threads not allocated elastically " + threadMap, threadMap.size() > 1 && threadMap.size() < partitions);
            verifyMessages(countPerPartition * partitions);
        } finally {
            for (Cancellation groupCancel : groupCancels)
                groupCancel.dispose();
            cancellation.dispose();
            scheduler.shutdown();
        }
    }



    /**
     * Tests groupBy(partition) with a large number of partitions distributed on a small number of threads.
     * Ordering is guaranteed for partitions with thread affinity. Delays in processing one partition
     * affect all partitions on that thread.
     */
    @Test
    public void groupByPartitionThreadSharing() throws Exception {
        int countPerPartition = 10;
        topic = topics.get(200);
        int partitions = cluster.partitions(topic).size();
        CountDownLatch latch = new CountDownLatch(countPerPartition * partitions);
        int parallelism = 4;
        Scheduler scheduler = Schedulers.newParallel("test-groupBy", parallelism);
        Map<Integer, Integer> receiveCounts = new ConcurrentHashMap<>();
        for (int i = 0; i < partitions; i++)
            receiveCounts.put(i, 0);
        Map<String, Set<Integer>> threadMap = new ConcurrentHashMap<>();
        Set<Integer> inProgress = new HashSet<Integer>();
        AtomicInteger maxInProgress = new AtomicInteger();

        receiverOptions = receiverOptions.subscription(Collections.singletonList(topic));
        List<Cancellation> groupCancels = new ArrayList<>();
        Cancellation cancellation = new KafkaReceiver<>(consumerFactory, receiverOptions)
            .receive()
            .groupBy(m -> m.offset().topicPartition())
            .subscribe(partitionFlux -> groupCancels.add(partitionFlux.publishOn(scheduler, 1).subscribe(record -> {
                    int partition = record.record().partition();
                    String thread = Thread.currentThread().getName();
                    Set<Integer> partitionSet = threadMap.get(thread);
                    if (partitionSet == null) {
                        partitionSet = new HashSet<Integer>();
                        threadMap.put(thread, partitionSet);
                    }
                    partitionSet.add(partition);
                    receivedMessages.add(record.record());
                    receiveCounts.put(partition, receiveCounts.get(partition) + 1);
                    latch.countDown();
                    synchronized (KafkaReceiverTest.this) {
                        if (receiveCounts.get(partition) == countPerPartition)
                            inProgress.remove(partition);
                        else if (inProgress.add(partition))
                            maxInProgress.incrementAndGet();
                    }
                })));

        try {
            sendMessages(topic, 0, countPerPartition * partitions);
            TestUtils.waitForLatch("Messages not received", latch, Duration.ofSeconds(60));
            verifyMessages(countPerPartition * partitions);
            assertEquals(parallelism, threadMap.size());
            // Thread assignment is currently not perfectly balanced, hence the lenient check
            for (Map.Entry<String, Set<Integer>> entry : threadMap.entrySet())
                assertTrue("Thread assignment not balanced: " + threadMap, entry.getValue().size() > 1);
            assertEquals(partitions, maxInProgress.get());
        } finally {
            scheduler.shutdown();
            for (Cancellation groupCancel : groupCancels)
                groupCancel.dispose();
            cancellation.dispose();
        }
    }



    /**
     * Tests parallel processing without grouping by partition. This does not guarantee
     * partition-based message ordering. Long processing time on one rail enables other
     * rails to continue (but a whole rail is delayed).
     */
    @Test
    public void parallelRoundRobinScheduler() throws Exception {
        topic = topics.get(200);
        int partitions = cluster.partitions(topic).size();
        int countPerPartition = 10;
        int count = countPerPartition * partitions;
        int threads = 4;
        Scheduler scheduler = Schedulers.newParallel("test-parallel", threads);
        AtomicBoolean firstMessage = new AtomicBoolean(true);
        Semaphore blocker = new Semaphore(0);

        receiverOptions = receiverOptions.subscription(Collections.singletonList(topic));
        new KafkaReceiver<>(consumerFactory, receiverOptions)
            .receive()
            .take(count)
            .parallel(4, 1)
            .runOn(scheduler)
            .subscribe(record -> {
                    if (firstMessage.compareAndSet(true, false))
                        blocker.acquireUninterruptibly();
                    receivedMessages.add(record.record());
                });
        try {
            sendMessages(topic, 0, count);
            Duration waitMs = Duration.ofSeconds(20);
            // No ordering guarantees, but blocking of one thread should still allow messages to be
            // processed on other threads
            TestUtils.waitUntil("Messages not received ", () -> receivedMessages.size(), list -> list.size() >= count / 2, receivedMessages, waitMs);
            blocker.release();
            TestUtils.waitUntil("Messages not received ", null, list -> list.size() == count, receivedMessages, waitMs);
        } finally {
            scheduler.shutdown();
        }
    }

    /**
     * Tests that sessions don't timeout when message processing takes longer than session timeout
     * when background heartbeating in Kafka consumers is not enabled. This tests the heartbeat flux
     * in KafkaReceiver for Kafka version 0.10.0.x
     */
    @Test
    public void heartbeatFluxEnable() throws Exception {
        long sessionTimeoutMs = 500;
        consumer = new MockConsumer(cluster, false);
        consumerFactory = new MockConsumer.Pool(Arrays.asList(consumer));
        receiverOptions = receiverOptions
                .consumerProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, String.valueOf(sessionTimeoutMs))
                .consumerProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "100")
                .subscription(Collections.singleton(topic));
        sendMessages(topic, 0, 10);
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<Integer, String>(consumerFactory, receiverOptions) {
            @Override
            protected boolean autoHeartbeatEnabledInConsumer() {
                return false;
            }
        };
        receiveWithOneOffAction(receiver, 1, 9, () -> TestUtils.sleep(sessionTimeoutMs + 500));
    }

    /**
     * Tests that sessions don't timeout when message processing takes longer than session timeout
     * when background heartbeating in Kafka consumers is enabled. Heartbeat flux is disabled in this case.
     */
    @Test
    public void heartbeatFluxDisable() throws Exception {
        long sessionTimeoutMs = 500;
        consumer = new MockConsumer(cluster, true);
        consumerFactory = new MockConsumer.Pool(Arrays.asList(consumer));
        receiverOptions = receiverOptions
                .consumerProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, String.valueOf(sessionTimeoutMs))
                .consumerProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "100")
                .subscription(Collections.singleton(topic));
        sendMessages(topic, 0, 10);
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<>(consumerFactory, receiverOptions);
        receiveWithOneOffAction(receiver, 1, 9, () -> TestUtils.sleep(sessionTimeoutMs + 500));
    }

    /**
     * Tests that heartbeat flux is actually disabled causing sessions to expire during delays
     * when the KafkaReceiver is created in auto-heartbeat mode.
     */
    @Test
    public void heartbeatTimeoutWithoutHeartbeatFlux() throws Exception {
        long sessionTimeoutMs = 500;
        consumer = new MockConsumer(cluster, false);
        consumerFactory = new MockConsumer.Pool(Arrays.asList(consumer));
        receiverOptions = receiverOptions
                .consumerProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, String.valueOf(sessionTimeoutMs))
                .consumerProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "100")
                .subscription(Collections.singleton(topic));
        int count = 10;
        sendMessages(topic, 0, count);
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<Integer, String>(consumerFactory, receiverOptions) {
            @Override
            protected boolean autoHeartbeatEnabledInConsumer() {
                return true;
            }
        };
        StepVerifier.create(receiver.receive().take(count).map(r -> r.record()), 1)
                .recordWith(() -> receivedMessages)
                .expectNextCount(1)
                .thenRequest(1)
                .consumeNextWith(r -> TestUtils.sleep(sessionTimeoutMs + 500))
                .thenRequest(count - 2)
                .expectNoEvent(Duration.ofMillis(sessionTimeoutMs + 1000))
                .thenCancel()
                .verify();
    }

    @Test
    public void backPressureReceive() throws Exception {
        receiverOptions = receiverOptions.consumerProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1")
            .subscription(Collections.singleton(topic));
        Flux<?> flux = new KafkaReceiver<>(consumerFactory, receiverOptions).receive();
        testBackPressure(flux);
    }

    @Test
    public void backPressureReceiveAutoAck() throws Exception {
        receiverOptions = receiverOptions.consumerProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1")
            .subscription(Collections.singleton(topic));
        Flux<?> flux = new KafkaReceiver<>(consumerFactory, receiverOptions).receiveAutoAck();
        testBackPressure(flux);
    }

    @Test
    public void backPressureReceiveAtmostOnce() throws Exception {
        receiverOptions = receiverOptions.consumerProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1")
            .subscription(Collections.singleton(topic));
        Flux<?> flux = new KafkaReceiver<>(consumerFactory, receiverOptions).receiveAtmostOnce();
        testBackPressure(flux);
    }

    private void testBackPressure(Flux<?> flux) throws Exception {
        int count = 5;
        sendMessages(topic, 0, count);
        Step<?> step = StepVerifier.create(flux.take(count), 1);
        for (int i = 0; i < count - 1; i++) {
            step = step.expectNextCount(1)
                       .then(() -> {
                               long pollCount = consumer.pollCount();
                               TestUtils.sleep(100);
                               assertEquals(pollCount, consumer.pollCount());
                           })
                       .thenRequest(1);
        }
        step.expectNextCount(1).expectComplete().verify();
    }

    @Test
    public void consumerMethods() throws Exception {
        testConsumerMethod(c -> assertEquals(this.assignedPartitions, c.assignment()));
        testConsumerMethod(c -> assertEquals(Collections.singleton(topic), c.subscription()));
        testConsumerMethod(c -> assertEquals(2, c.partitionsFor(topics.get(2)).size()));
        testConsumerMethod(c -> assertEquals(topics.size(), c.listTopics().size()));
        testConsumerMethod(c -> assertEquals(0, c.metrics().size()));

        testConsumerMethod(c -> {
                Collection<TopicPartition> partitions = Collections.singleton(new TopicPartition(topic, 1));
                c.pause(partitions);
                assertEquals(partitions, c.paused());
                c.resume(partitions);
            });
        testConsumerMethod(c -> {
                TopicPartition partition = new TopicPartition(topic, 1);
                Collection<TopicPartition> partitions = Collections.singleton(partition);
                long position = c.position(partition);
                c.seekToBeginning(partitions);
                assertEquals(0, c.position(partition));
                c.seekToEnd(partitions);
                assertTrue("Did not seek to end", c.position(partition) > 0);
                c.seek(partition, position);
            });
    }

    private void testConsumerMethod(Consumer<org.apache.kafka.clients.consumer.Consumer<Integer, String>> method) {
        receivedMessages.clear();
        consumerFactory.addConsumer(new MockConsumer(cluster, true));
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic));
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<>(consumerFactory, receiverOptions);
        Flux<ReceiverRecord<Integer, String>> inboundFlux = receiver
                .receive()
                .doOnNext(r -> {
                        Mono<?> mono = receiver.doOnConsumer(c -> {
                                method.accept(c);
                                return true;
                            });
                        mono.block();
                    });
        sendMessages(topic, 0, 10);
        receiveAndVerify(inboundFlux, 10);
    }

    /**
     * Tests methods not permitted on KafkaConsumer using {@link Receiver#doOnConsumer(java.util.function.Function)}
     */
    @Test
    public void consumerDisallowedMethods() {
        ConsumerRebalanceListener rebalanceListener = new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            }
            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            }
        };
        OffsetCommitCallback commitListener = new OffsetCommitCallback() {
            @Override
            public void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
            }
        };
        testDisallowedConsumerMethod(c -> c.poll(0));
        testDisallowedConsumerMethod(c -> c.close());
        testDisallowedConsumerMethod(c -> c.assign(Collections.singleton(new TopicPartition(topic, 0))));
        testDisallowedConsumerMethod(c -> c.subscribe(Collections.singleton(topic)));
        testDisallowedConsumerMethod(c -> c.subscribe(Collections.singleton(topic), rebalanceListener));
        testDisallowedConsumerMethod(c -> c.subscribe(Pattern.compile(".*"), rebalanceListener));
        testDisallowedConsumerMethod(c -> c.unsubscribe());
        testDisallowedConsumerMethod(c -> c.commitAsync());
        testDisallowedConsumerMethod(c -> c.commitAsync(commitListener));
        testDisallowedConsumerMethod(c -> c.commitAsync(new HashMap<>(), commitListener));
        testDisallowedConsumerMethod(c -> c.commitSync());
        testDisallowedConsumerMethod(c -> c.commitSync(new HashMap<>()));
        testDisallowedConsumerMethod(c -> c.wakeup());
    }

    private void testDisallowedConsumerMethod(Consumer<org.apache.kafka.clients.consumer.Consumer<Integer, String>> method) {
        consumerFactory.addConsumer(new MockConsumer(cluster, true));
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic));
        sendMessages(topic, 0, 10);
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<>(consumerFactory, receiverOptions);
        Flux<ReceiverRecord<Integer, String>> inboundFlux = receiver
                .receive()
                .doOnNext(r -> {
                        Mono<?> mono = receiver.doOnConsumer(c -> {
                                method.accept(c);
                                return true;
                            });
                        mono.block();
                    });
        StepVerifier.create(inboundFlux)
            .expectError(UnsupportedOperationException.class)
            .verify();
    }

    /**
     * Tests that a receiver can receive again after the first receive terminates, but
     * not while the first receive is still active.
     */
    @Test
    public void multipleReceives() {
        for (int i = 0; i < 5; i++)
            consumerFactory.addConsumer(new MockConsumer(cluster, true));
        receiverOptions = receiverOptions
                .subscription(Collections.singleton(topic));
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<>(consumerFactory, receiverOptions);
        Flux<ReceiverRecord<Integer, String>> inboundFlux = receiver.receive()
                .doOnNext(r -> r.offset().acknowledge());
        try {
            receiver.receive();
            fail("Multiple outstanding receives on the same receiver");
        } catch (IllegalStateException e) {
            // Expected exception
        }
        try {
            receiver.receiveAtmostOnce();
            fail("Multiple outstanding receives on the same receiver");
        } catch (IllegalStateException e) {
            // Expected exception
        }

        try {
            receiver.receiveAutoAck();
            fail("Multiple outstanding receives on the same receiver");
        } catch (IllegalStateException e) {
            // Expected exception
        }

        sendMessages(topic, 0, 10);
        receiveAndVerify(inboundFlux, 10);

        inboundFlux = receiver.receive().doOnNext(r -> r.offset().acknowledge());
        sendMessages(topic, 10, 10);
        receiveAndVerify(inboundFlux, 10);

        sendMessages(topic, 20, 10);
        verifyMessages(receiver.receiveAtmostOnce().take(10), 10);

        sendMessages(topic, 30, 10);
        verifyMessages(receiver.receiveAutoAck().concatMap(r -> r).take(10), 10);
    }

    private void sendMessages(String topic, int startIndex, int count) {
        int partitions = cluster.cluster().partitionCountForTopic(topic);
        for (int i = 0; i < count; i++) {
            int key = startIndex + i;
            int partition = key % partitions;
            cluster.appendMessage(new ProducerRecord<Integer, String>(topic, partition, key, "Message-" + key));
        }
    }

    private void sendMessagesToPartition(String topic, int partition, int startIndex, int count) {
        for (int i = 0; i < count; i++) {
            int key = startIndex + i;
            cluster.appendMessage(new ProducerRecord<Integer, String>(topic, partition, key, "Message-" + key));
        }
    }

    private void sendReceiveAndVerify(int sendCount, int receiveCount) {
        sendMessages(topic, 0, sendCount);
        receiveAndVerify(receiveCount, r -> { });
    }

    private void receiveAndVerify(int receiveCount, Consumer<ReceiverRecord<Integer, String>> onNext) {
        Flux<ReceiverRecord<Integer, String>> inboundFlux = new KafkaReceiver<>(consumerFactory, receiverOptions)
                .receive()
                .doOnNext(onNext);
        receiveAndVerify(inboundFlux, receiveCount);
    }

    private void receiveAndVerify(Flux<ReceiverRecord<Integer, String>> inboundFlux, int receiveCount) {
        Flux<ConsumerRecord<Integer, String>> flux = inboundFlux
                .map(r -> r.record());
        verifyMessages(flux.take(receiveCount), receiveCount);
    }

    private void verifyMessages(Flux<ConsumerRecord<Integer, String>> inboundFlux, int receiveCount) {
        StepVerifier.create(inboundFlux)
                .recordWith(() -> receivedMessages)
                .expectNextCount(receiveCount)
                .expectComplete()
                .verify();
        verifyMessages(receiveCount);
    }

    private void receiveVerifyError(Class<? extends Throwable> exceptionClass, Consumer<ReceiverRecord<Integer, String>> onNext) {
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<>(consumerFactory, receiverOptions);
        StepVerifier.create(receiver.receive().doOnNext(onNext))
            .expectError(exceptionClass)
            .verify();
    }

    private void receiveWithOneOffAction(KafkaReceiver<Integer, String> receiver, int receiveCount1, int receiveCount2, Runnable task) {
        StepVerifier.create(receiver.receive().take(receiveCount1 + receiveCount2).map(r -> r.record()), receiveCount1)
                .recordWith(() -> receivedMessages)
                .expectNextCount(receiveCount1)
                .then(task)
                .thenRequest(1)
                .expectNextCount(1)
                .thenRequest(receiveCount2 - 1)
                .expectNextCount(receiveCount2 - 1)
                .expectComplete()
                .verify();
        verifyMessages(receiveCount1  + receiveCount2);
    }

    private Map<TopicPartition, List<ConsumerRecord<Integer, String>>> receivedByPartition() {
        Map<TopicPartition, List<ConsumerRecord<Integer, String>>> received = new HashMap<>();
        for (PartitionInfo partitionInfo: cluster.cluster().partitionsForTopic(topic)) {
            TopicPartition partition = new TopicPartition(topic, partitionInfo.partition());
            List<ConsumerRecord<Integer, String>> list = new ArrayList<>();
            received.put(partition, list);
            for (ConsumerRecord<Integer, String> r : receivedMessages) {
                if (r.topic().equals(topic) && r.partition() == partition.partition())
                    list.add(r);
            }
        }
        return received;
    }

    public void verifyMessages(int count) {
        Map<TopicPartition, Long> offsets = new HashMap<>(receiveStartOffsets);
        for (ConsumerRecord<Integer, String> received : receivedMessages) {
            TopicPartition partition = topicPartition(received);
            long offset = offsets.get(partition);
            offsets.put(partition, offset + 1);
            assertEquals(offset, received.offset());
            assertEquals(cluster.log(partition).get((int) offset).value(), received.value());
        }
    }

    private void verifyCommits(String groupId, String topic, int remaining) {
        receivedMessages.removeAll(uncommittedMessages);
        for (Map.Entry<TopicPartition, List<ConsumerRecord<Integer, String>>> entry: receivedByPartition().entrySet()) {
            Long committedOffset = cluster.committedOffset(groupId, entry.getKey());
            List<ConsumerRecord<Integer, String>> list = entry.getValue();
            if (committedOffset != null) {
                assertFalse("No records received on " + entry.getKey(), list.isEmpty());
                assertEquals(list.get(list.size() - 1).offset() + 1, committedOffset.longValue());
            }
        }
        consumerFactory.addConsumer(new MockConsumer(cluster, true));
        receiveAndVerify(remaining, r -> { });
    }

    private void verifyCommit(ReceiverRecord<Integer, String> r, long lastCommitted) {
        TopicPartition partition = r.offset().topicPartition();
        Long committedOffset = cluster.committedOffset(groupId, partition);
        long offset = r.offset().offset();
        if (lastCommitted >= 0 && offset == lastCommitted) {
            TestUtils.waitUntil("Offset not committed", null,
                    p -> cluster.committedOffset(groupId, p) == (Long) (offset + 1), partition, Duration.ofSeconds(1));
        }
        committedOffset = cluster.committedOffset(groupId, partition);
        assertEquals(committedOffset, lastCommitted == -1 ? null : lastCommitted + 1);
    }

    private Cancellation asyncReceive(CountDownLatch latch) {
        KafkaReceiver<Integer, String> receiver = new KafkaReceiver<>(consumerFactory, receiverOptions);
        return receiver.receive()
                .doOnNext(r -> {
                        receivedMessages.add(r.record());
                        latch.countDown();
                    })
                .subscribe();
    }

    private TopicPartition topicPartition(ConsumerRecord<?, ?> record) {
        return new TopicPartition(record.topic(), record.partition());
    }
}
