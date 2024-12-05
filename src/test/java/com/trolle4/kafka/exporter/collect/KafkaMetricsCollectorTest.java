package com.trolle4.kafka.exporter.collect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaMetricsCollectorTest {

    @Mock
    private AdminClient adminClient;

    private MeterRegistry meterRegistry;
    private KafkaMetricsCollector collector;
    private KafkaMetricsCollector.CollectorConf collectorConf;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        collectorConf = new KafkaMetricsCollector.CollectorConf();
        collectorConf.setTopicWhiteListRegex(".*");
        collectorConf.setTopicBlackListRegex("");
        collectorConf.setMinTimeBetweenUpdatesMillis(60000);

        collector = new KafkaMetricsCollector(adminClient, collectorConf);
    }

    @Test
    void testGetTopics() throws Exception {
        // Setup
        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        when(listTopicsResult.names()).thenReturn(
                KafkaFuture.completedFuture(Set.of("test-topic"))
        );
        when(adminClient.listTopics()).thenReturn(listTopicsResult);

        Node leader = new Node(1, "localhost", 9092);
        Node replica = new Node(2, "localhost", 9093);
        TopicPartitionInfo partitionInfo = new TopicPartitionInfo(
                0, leader, List.of(leader, replica), List.of(leader)
        );

        TopicDescription topicDescription = new TopicDescription(
                "test-topic", false, List.of(partitionInfo)
        );

        DescribeTopicsResult describeTopicsResult = mock(DescribeTopicsResult.class);
        when(describeTopicsResult.allTopicNames()).thenReturn(
                KafkaFuture.completedFuture(Map.of("test-topic", topicDescription))
        );
        when(adminClient.describeTopics(anyCollection())).thenReturn(describeTopicsResult);

        // Execute
        Map<String, TopicDescription> result = collector.getTopics();

        // Verify
        assertEquals(1, result.size());
        assertEquals("test-topic", result.keySet().iterator().next());
        verify(adminClient).listTopics();
        verify(adminClient).describeTopics(anyCollection());
    }

    //@Test
    void testUpdateMetrics() throws Exception {
        // Setup topic metadata
        setupTopicMetadata();

        // Setup offsets
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> earliestOffsets = new HashMap<>();
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets = new HashMap<>();
        TopicPartition tp = new TopicPartition("test-topic", 0);

        earliestOffsets.put(tp, new ListOffsetsResult.ListOffsetsResultInfo(0L, 0L, Optional.empty()));
        latestOffsets.put(tp, new ListOffsetsResult.ListOffsetsResultInfo(100L, 0L, Optional.empty()));

        // Mock getOffsets method for both earliest and latest calls
        when(collector.getOffsets(anyList(), any(OffsetSpec.class)))
                .thenReturn(earliestOffsets)
                .thenReturn(latestOffsets);

        // Setup consumer group data
        ConsumerGroupListing groupListing = new ConsumerGroupListing("test-group", true);
        when(collector.getConsumerGroups()).thenReturn(List.of(groupListing));

        Map<TopicPartition, OffsetAndMetadata> groupOffsets = new HashMap<>();
        groupOffsets.put(tp, new OffsetAndMetadata(50L));
        Map<String, Map<TopicPartition, OffsetAndMetadata>> consumerGroupOffsets = Map.of(
                "test-group", groupOffsets
        );
        when(collector.getConsumerGroupOffsets(anyCollection())).thenReturn(consumerGroupOffsets);

        // Execute
        collector.updateMetrics();

        // Verify metrics
        assertEquals(1.0, meterRegistry.get(KafkaMetricsCollector.KAFKA_TOPIC_PARTITIONS)
                .tag("topic", "test-topic")
                .gauge()
                .value());

        assertEquals(100.0, meterRegistry.get(KafkaMetricsCollector.KAFKA_TOPIC_PARTITION_CURRENT_OFFSET)
                .tag("topic", "test-topic")
                .tag("partition", "0")
                .gauge()
                .value());

        assertEquals(50.0, meterRegistry.get(KafkaMetricsCollector.KAFKA_CONSUMERGROUP_LAG)
                .tag("consumergroup", "test-group")
                .tag("topic", "test-topic")
                .tag("partition", "0")
                .gauge()
                .value());
    }

    private void setupTopicMetadata() throws InterruptedException, ExecutionException, TimeoutException {
        // Mock listTopics
        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        when(listTopicsResult.names()).thenReturn(
                KafkaFuture.completedFuture(Set.of("test-topic"))
        );
        when(adminClient.listTopics()).thenReturn(listTopicsResult);

        // Mock topic description
        Node leader = new Node(1, "localhost", 9092);
        Node replica = new Node(2, "localhost", 9093);
        TopicPartitionInfo partitionInfo = new TopicPartitionInfo(
                0, leader, List.of(leader, replica), List.of(leader)
        );

        TopicDescription topicDescription = new TopicDescription(
                "test-topic", false, List.of(partitionInfo)
        );

        DescribeTopicsResult describeTopicsResult = mock(DescribeTopicsResult.class);
        when(describeTopicsResult.allTopicNames()).thenReturn(
                KafkaFuture.completedFuture(Map.of("test-topic", topicDescription))
        );
        when(adminClient.describeTopics(anyCollection())).thenReturn(describeTopicsResult);
    }

    @Test
    void testTopicFilteringWithWhitelist() throws Exception {
        // Setup whitelist to only allow topics starting with "test-"
        collectorConf.setTopicWhiteListRegex("test-.*");

        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        when(listTopicsResult.names()).thenReturn(
                KafkaFuture.completedFuture(Set.of("test-topic", "other-topic"))
        );
        when(adminClient.listTopics()).thenReturn(listTopicsResult);

        Node leader = new Node(1, "localhost", 9092);
        TopicPartitionInfo partitionInfo = new TopicPartitionInfo(
                0, leader, List.of(leader), List.of(leader)
        );

        TopicDescription topicDescription = new TopicDescription(
                "test-topic", false, List.of(partitionInfo)
        );

        DescribeTopicsResult describeTopicsResult = mock(DescribeTopicsResult.class);
        when(describeTopicsResult.allTopicNames()).thenReturn(
                KafkaFuture.completedFuture(Map.of("test-topic", topicDescription))
        );
        when(adminClient.describeTopics(anyCollection())).thenReturn(describeTopicsResult);

        // Execute
        Map<String, TopicDescription> result = collector.getTopics();

        // Verify that only "test-topic" was included
        assertEquals(1, result.size());
        assertEquals("test-topic", result.keySet().iterator().next());
    }

    @Test
    void testTopicFilteringWithBlacklist() throws Exception {
        // Setup blacklist to exclude topics containing "internal"
        collectorConf.setTopicBlackListRegex(".*internal.*");

        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        when(listTopicsResult.names()).thenReturn(
                KafkaFuture.completedFuture(Set.of("test-topic", "internal-topic"))
        );
        when(adminClient.listTopics()).thenReturn(listTopicsResult);

        Node leader = new Node(1, "localhost", 9092);
        TopicPartitionInfo partitionInfo = new TopicPartitionInfo(
                0, leader, List.of(leader), List.of(leader)
        );

        TopicDescription topicDescription = new TopicDescription(
                "test-topic", false, List.of(partitionInfo)
        );

        DescribeTopicsResult describeTopicsResult = mock(DescribeTopicsResult.class);
        when(describeTopicsResult.allTopicNames()).thenReturn(
                KafkaFuture.completedFuture(Map.of("test-topic", topicDescription))
        );
        when(adminClient.describeTopics(anyCollection())).thenReturn(describeTopicsResult);

        // Execute
        Map<String, TopicDescription> result = collector.getTopics();

        // Verify that only "test-topic" was included
        assertEquals(1, result.size());
        assertEquals("test-topic", result.keySet().iterator().next());
    }
}