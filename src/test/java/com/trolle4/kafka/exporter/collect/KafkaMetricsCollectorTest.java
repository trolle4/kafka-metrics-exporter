package com.trolle4.kafka.exporter.collect;

import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaMetricsCollectorTest {

    @Mock
    private PrometheusMeterRegistry meterRegistry;

    @Mock
    private PrometheusService prometheusService;

    @Mock
    private AdminClientService adminClientService;

    @Mock
    private KafkaMetricsCollector.CollectorConf collectorConf;

    @InjectMocks
    private KafkaMetricsCollector collector;


    @Test
    void testGetTopics() throws Exception {
        // Prepare test data
        var topicNames = Set.of("topic", "topic1", "topic2");
        var expectedTopicNames = Set.of("topic1");
        var expectedTopicDescriptions = Map.of("topic1", createTopicDescription("topic1"));

        // Mock the internal methods
        doReturn(".+\\d$").when(collectorConf).getTopicWhiteListRegex();
        doReturn("^.*2$").when(collectorConf).getTopicBlackListRegex();
        doReturn(topicNames).when(adminClientService).listTopics();
        doReturn(expectedTopicDescriptions).when(adminClientService).describeTopics(eq(expectedTopicNames));

        // Execute
        Map<String, TopicDescription> result = collector.getTopics();

        // Verify
        assertNotNull(result);
        assertEquals(Set.of("topic1"), result.keySet());
        assertEquals(expectedTopicDescriptions.get("topic1"), result.get("topic1"));
        assertEquals(expectedTopicDescriptions, result);
        verify(adminClientService).listTopics();
        verify(adminClientService).describeTopics(anySet());
    }


    private static TopicDescription createTopicDescription(String topic) {
        var leader = new Node(1, "localhost", 9092);
        var replicas = Collections.singletonList(leader);
        var isr = Collections.singletonList(leader);

        var partitionInfo = new TopicPartitionInfo(0, leader, replicas, isr);
        return new TopicDescription(topic, false, Collections.singletonList(partitionInfo));
    }

    @Test
    void testCollectTopicMetrics() throws Exception {
        // Prepare test data
        var topicDescription = createTopicDescription("topic1");
        var topicDescriptions = Map.of("topic1", topicDescription);
        var tagTopic = Tags.of(KafkaMetricsCollector.TAG_TOPIC, "topic1");
        var tagTopicPartition = Tags.of(KafkaMetricsCollector.TAG_TOPIC, "topic1", KafkaMetricsCollector.TAG_PARTITION, "0");

        var tp = new TopicPartition("topic1", 0);
        var offsetsMapEarliest =
                Map.of(tp, new ListOffsetsResult.ListOffsetsResultInfo(25L, 0L, Optional.empty()));
        var offsetsMapLatest =
                Map.of(tp, new ListOffsetsResult.ListOffsetsResultInfo(100L, 0L, Optional.empty()));

        doReturn(meterRegistry).when(prometheusService).getMeterRegistry();
        doReturn(offsetsMapEarliest).doReturn(offsetsMapLatest)
                .when(adminClientService).getOffsets(eq(List.of(tp)), any());

        // Execute
        var result = collector.collectTopicMetrics(topicDescriptions);

        // Verify
        verify(meterRegistry).gauge(eq(KafkaMetricsCollector.KAFKA_TOPIC_PARTITIONS), eq(tagTopic), eq(1));
        verify(meterRegistry).gauge(eq(KafkaMetricsCollector.KAFKA_TOPIC_PARTITION_CURRENT_OFFSET), eq(tagTopicPartition), eq(100L));
        verify(meterRegistry).gauge(eq(KafkaMetricsCollector.KAFKA_TOPIC_PARTITION_OLDEST_OFFSET), eq(tagTopicPartition), eq(25L));
        verify(meterRegistry).gauge(eq(KafkaMetricsCollector.KAFKA_TOPIC_PARTITION_IN_SYNC_REPLICA), eq(tagTopicPartition), eq(1));
        verify(meterRegistry).gauge(eq(KafkaMetricsCollector.KAFKA_TOPIC_PARTITION_LEADER), eq(tagTopicPartition), eq(1));
        verify(meterRegistry).gauge(eq(KafkaMetricsCollector.KAFKA_TOPIC_PARTITION_LEADER_IS_PREFERRED), eq(tagTopicPartition), eq(1));
        verify(meterRegistry).gauge(eq(KafkaMetricsCollector.KAFKA_TOPIC_PARTITION_REPLICAS), eq(tagTopicPartition), eq(1));
        verify(meterRegistry).gauge(eq(KafkaMetricsCollector.KAFKA_TOPIC_PARTITION_UNDER_REPLICATED_PARTITION), eq(tagTopicPartition), eq(0));

    }

    @Test
    void testCollectConsumerGroupMetrics() throws Exception {
        // Prepare test data
        var tp = new TopicPartition("topic1", 0);
        var offsetsMapLatest =
                Map.of(tp, new ListOffsetsResult.ListOffsetsResultInfo(50L, 0L, Optional.empty()));

        var consumerGroups = Collections.singletonList(
                new ConsumerGroupListing("group1", false, Optional.empty())
        );

        var topicGroupOffsets = Map.of(tp, new OffsetAndMetadata(33L));
        var consumerGroupOffsets = Map.of("group1", topicGroupOffsets);

        // Mock internal methods
        doReturn(meterRegistry).when(prometheusService).getMeterRegistry();
        doReturn(consumerGroups).when(adminClientService).getConsumerGroups();
        doReturn(consumerGroupOffsets).when(adminClientService).getConsumerGroupOffsets(consumerGroups);

        // Execute
        collector.collectConsumerGroupMetrics(offsetsMapLatest);

        // Verify
        var expectedTags = Tags.of(
                KafkaMetricsCollector.TAG_CONSUMER_GROUP, "group1",
                KafkaMetricsCollector.TAG_TOPIC, "topic1",
                KafkaMetricsCollector.TAG_PARTITION, "0"
        );

        verify(meterRegistry).gauge(eq(KafkaMetricsCollector.KAFKA_CONSUMERGROUP_CURRENT_OFFSET), eq(expectedTags), eq(33L));
        verify(meterRegistry).gauge(eq(KafkaMetricsCollector.KAFKA_CONSUMERGROUP_LAG), eq(expectedTags), eq(17L));

    }
}