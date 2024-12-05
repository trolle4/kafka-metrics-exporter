package com.trolle4.kafka.exporter.collect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class KafkaMetricsCollector {

    // Topic metrics
    public static final String KAFKA_TOPIC_PARTITIONS = "kafka_topic_partitions";
    public static final String KAFKA_TOPIC_PARTITION_CURRENT_OFFSET = "kafka_topic_partition_current_offset";
    public static final String KAFKA_TOPIC_PARTITION_OLDEST_OFFSET = "kafka_topic_partition_oldest_offset";
    public static final String KAFKA_TOPIC_PARTITION_IN_SYNC_REPLICA = "kafka_topic_partition_in_sync_replica";
    public static final String KAFKA_TOPIC_PARTITION_LEADER = "kafka_topic_partition_leader";
    public static final String KAFKA_TOPIC_PARTITION_LEADER_IS_PREFERRED = "kafka_topic_partition_leader_is_preferred";
    public static final String KAFKA_TOPIC_PARTITION_REPLICAS = "kafka_topic_partition_replicas";
    public static final String KAFKA_TOPIC_PARTITION_UNDER_REPLICATED_PARTITION = "kafka_topic_partition_under_replicated_partition";

    // Consumer group metrics
    public static final String KAFKA_CONSUMERGROUP_CURRENT_OFFSET = "kafka_consumergroup_current_offset";
    public static final String KAFKA_CONSUMERGROUP_LAG = "kafka_consumergroup_lag";

    private final AdminClient adminClient;
    private final MeterRegistry meterRegistry;
    private final CollectorConf collectorConf;

    @Configuration
    @ConfigurationProperties(prefix = "collector.conf")
    @Data
    public static class CollectorConf {
        private String topicWhiteListRegex;
        private String topicBlackListRegex;
        private int minTimeBetweenUpdatesMillis;
    }

    @SneakyThrows
    public Map<String, TopicDescription> getTopics() {
        var topics = adminClient.listTopics().names().get(10, TimeUnit.SECONDS)
                .stream()
                .filter(topic -> topic.matches(collectorConf.getTopicWhiteListRegex()))
                .filter(topic -> !topic.matches(collectorConf.getTopicBlackListRegex()))
                .toList();

        return adminClient.describeTopics(topics).allTopicNames().get(10, TimeUnit.SECONDS);
    }

    @Scheduled(fixedRateString = "${collector.conf.minTimeBetweenUpdatesMillis}")
    public synchronized void updateMetrics() {
            var latestOffsets = collectTopicMetrics();
            collectConsumerGroupMetrics(latestOffsets);
    }

    @SneakyThrows
    private Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> collectTopicMetrics()  {
        var topicDescriptions = getTopics();

        var topicPartitions = topicDescriptions.entrySet().stream()
                .flatMap(entry -> {
                    String topic = entry.getKey();
                    TopicDescription desc = entry.getValue();

                    return desc.partitions().stream()
                            .map(partition -> new TopicPartition(topic, partition.partition()));
                }).toList();

        var earliestOffsets = getOffsets(topicPartitions, OffsetSpec.earliest());

        var latestOffsets = getOffsets(topicPartitions, OffsetSpec.latest());

        for (Map.Entry<String, TopicDescription> entry : topicDescriptions.entrySet()) {
            String topic = entry.getKey();
            TopicDescription desc = entry.getValue();

            meterRegistry.gauge(KAFKA_TOPIC_PARTITIONS,
                    Tags.of("topic", topic),
                    desc.partitions().size());

            for (TopicPartitionInfo partition : desc.partitions()) {
                int partitionNumber = partition.partition();
                Tags tags = Tags.of(
                        "topic", topic,
                        "partition", String.valueOf(partitionNumber)
                );

                TopicPartition tp = new TopicPartition(topic, partitionNumber);
                var earliest = earliestOffsets.get(tp);
                var latest = latestOffsets.get(tp);

                updatePartitionMetrics(partition, tags, earliest, latest);
            }
        }

        return latestOffsets;
    }



    private void updatePartitionMetrics(TopicPartitionInfo partition, Tags tags, ListOffsetsResult.ListOffsetsResultInfo earliest, ListOffsetsResult.ListOffsetsResultInfo latest) {
        meterRegistry.gauge(KAFKA_TOPIC_PARTITION_CURRENT_OFFSET, tags,
                latest != null ? latest.offset() : -1);
        meterRegistry.gauge(KAFKA_TOPIC_PARTITION_OLDEST_OFFSET, tags,
                earliest != null ? earliest.offset() : -1);

        meterRegistry.gauge(KAFKA_TOPIC_PARTITION_IN_SYNC_REPLICA, tags,
                partition.isr().size());
        meterRegistry.gauge(KAFKA_TOPIC_PARTITION_LEADER,
                tags, partition.leader() != null ? partition.leader().id() : -1);
        meterRegistry.gauge(KAFKA_TOPIC_PARTITION_LEADER_IS_PREFERRED, tags,
                partition.leader() != null && partition.leader().id() == partition.replicas().getFirst().id() ? 1 : 0);
        meterRegistry.gauge(KAFKA_TOPIC_PARTITION_REPLICAS, tags,
                partition.replicas().size());
        meterRegistry.gauge(KAFKA_TOPIC_PARTITION_UNDER_REPLICATED_PARTITION, tags,
                partition.isr().size() < partition.replicas().size() ? 1 : 0);
    }


    @SneakyThrows
    private void collectConsumerGroupMetrics(Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets) {

            var consumerGroups = getConsumerGroups();

            var consumerGroupOffsets = getConsumerGroupOffsets(consumerGroups);


            consumerGroupOffsets.forEach((groupId, offsets) -> {
                offsets.forEach((tp, offset) -> {
                    Tags tags = Tags.of(
                            "consumergroup", groupId,
                            "topic", tp.topic(),
                            "partition", String.valueOf(tp.partition())
                    );
                    long currentOffset = offset.offset();
                    meterRegistry.gauge(KAFKA_CONSUMERGROUP_CURRENT_OFFSET, tags, currentOffset);

                    var latestOffset = latestOffsets.get(tp).offset();
                    long lag = Math.max(0, latestOffset - currentOffset);
                    meterRegistry.gauge(KAFKA_CONSUMERGROUP_LAG, tags, lag);
                });
            });
    }

    Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> getOffsets(List<TopicPartition> topicPartitions, OffsetSpec offsetSpec) throws InterruptedException, ExecutionException, TimeoutException {
        return adminClient.listOffsets(
                        topicPartitions.stream().collect(Collectors.toMap(tp -> tp, tp -> offsetSpec)))
                .all().get(10, TimeUnit.SECONDS);
    }

    Collection<ConsumerGroupListing> getConsumerGroups() throws InterruptedException, ExecutionException, TimeoutException {
        return adminClient.listConsumerGroups().all().get(10, TimeUnit.SECONDS);
    }

    Map<String, Map<TopicPartition, OffsetAndMetadata>> getConsumerGroupOffsets(Collection<ConsumerGroupListing> consumerGroups) throws InterruptedException, ExecutionException, TimeoutException {
        return adminClient.listConsumerGroupOffsets(
                consumerGroups.stream().collect(Collectors.toMap(
                        ConsumerGroupListing::groupId,
                        cg -> new ListConsumerGroupOffsetsSpec()
                ))
        ).all().get(10, TimeUnit.SECONDS);
    }


}