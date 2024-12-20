package com.trolle4.kafka.exporter.collect;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalNotification;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.google.common.cache.RemovalCause.EXPIRED;

@Component
@RequiredArgsConstructor
@Slf4j
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

    // Tags
    public static final String TAG_TOPIC = "topic";
    public static final String TAG_PARTITION = "partition";
    public static final String TAG_CONSUMER_GROUP = "consumergroup";

    private final Map<String, Cache<Tags, AtomicLong>> metrics = new HashMap<>();

    private final CollectorConf collectorConf;
    private final AdminClientService adminClientService;
    private final PrometheusService prometheusService;


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
        var topics = adminClientService.listTopics()
                .stream()
                .filter(topic -> topic.matches(collectorConf.getTopicWhiteListRegex()))
                .filter(topic -> !topic.matches(collectorConf.getTopicBlackListRegex()))
                .collect(Collectors.toSet());

        return adminClientService.describeTopics(topics);
    }


    @Scheduled(fixedRateString = "${collector.conf.minTimeBetweenUpdatesMillis}")
    public synchronized void updateMetrics() {
        var topicDescriptions = getTopics();
        var latestOffsets = collectTopicMetrics(topicDescriptions);
        collectConsumerGroupMetrics(latestOffsets);
    }


    private Cache<Tags, AtomicLong> createMetricCache(String metricName) {
        return CacheBuilder.newBuilder()
                .expireAfterWrite(collectorConf.minTimeBetweenUpdatesMillis * 5L, TimeUnit.MILLISECONDS)
                .removalListener((RemovalNotification<Tags, AtomicLong> notification) -> {
                    if (notification.getCause() == EXPIRED) {
                        // Unregister the metric when it expires
                        var id = new Meter.Id(metricName, notification.getKey(), null, null, Meter.Type.GAUGE);
                        log.info("Removing metric: {}", id);
                        meterRegistry().remove(id);
                    }
                })
                .build();
    }

    private void updateMetrics(String metricName, Tags tags, Number value) {
        var valueCache = metrics.computeIfAbsent(metricName, k -> createMetricCache(metricName));
        var existingValue = valueCache.getIfPresent(tags);

        if (existingValue == null) {
            //Register new gauge
            existingValue = new AtomicLong(value.longValue());
            meterRegistry().gauge(metricName, tags, existingValue);
        }

        //Touch it to prevent it from being removed
        valueCache.put(tags, existingValue);
        existingValue.set(value.longValue());
    }

    @SneakyThrows
    Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> collectTopicMetrics(Map<String, TopicDescription> topicDescriptions) {

        var topicPartitions = topicDescriptions.entrySet().stream()
                .flatMap(entry -> {
                    String topic = entry.getKey();
                    TopicDescription desc = entry.getValue();

                    return desc.partitions().stream()
                            .map(partition -> new TopicPartition(topic, partition.partition()));
                }).toList();

        var earliestOffsets = adminClientService.getOffsets(topicPartitions, OffsetSpec.earliest());

        var latestOffsets = adminClientService.getOffsets(topicPartitions, OffsetSpec.latest());

        for (Map.Entry<String, TopicDescription> entry : topicDescriptions.entrySet()) {
            String topic = entry.getKey();
            TopicDescription desc = entry.getValue();

            updateMetrics(KAFKA_TOPIC_PARTITIONS,
                    Tags.of(TAG_TOPIC, topic),
                    desc.partitions().size());

            for (TopicPartitionInfo partition : desc.partitions()) {
                int partitionNumber = partition.partition();
                Tags tags = Tags.of(
                        TAG_TOPIC, topic,
                        TAG_PARTITION, String.valueOf(partitionNumber)
                );

                TopicPartition tp = new TopicPartition(topic, partitionNumber);
                var earliest = earliestOffsets.get(tp);
                var latest = latestOffsets.get(tp);

                updatePartitionMetrics(partition, tags, earliest, latest);
            }
        }

        return latestOffsets;
    }


    void updatePartitionMetrics(TopicPartitionInfo partition, Tags tags, ListOffsetsResult.ListOffsetsResultInfo earliest, ListOffsetsResult.ListOffsetsResultInfo latest) {
        updateMetrics(KAFKA_TOPIC_PARTITION_CURRENT_OFFSET, tags,
                latest != null ? latest.offset() : -1);
        updateMetrics(KAFKA_TOPIC_PARTITION_OLDEST_OFFSET, tags,
                earliest != null ? earliest.offset() : -1);

        updateMetrics(KAFKA_TOPIC_PARTITION_IN_SYNC_REPLICA, tags,
                partition.isr().size());
        updateMetrics(KAFKA_TOPIC_PARTITION_LEADER,
                tags, partition.leader() != null ? partition.leader().id() : -1);
        updateMetrics(KAFKA_TOPIC_PARTITION_LEADER_IS_PREFERRED, tags,
                partition.leader() != null && partition.leader().id() == partition.replicas().getFirst().id() ? 1 : 0);
        updateMetrics(KAFKA_TOPIC_PARTITION_REPLICAS, tags,
                partition.replicas().size());
        updateMetrics(KAFKA_TOPIC_PARTITION_UNDER_REPLICATED_PARTITION, tags,
                partition.isr().size() < partition.replicas().size() ? 1 : 0);
    }


    @SneakyThrows
    void collectConsumerGroupMetrics(Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets) {

        var consumerGroups = adminClientService.getConsumerGroups();

        var consumerGroupOffsets = adminClientService.getConsumerGroupOffsets(consumerGroups);


        consumerGroupOffsets.forEach((groupId, offsets) -> {
            offsets.forEach((tp, offset) -> {
                Tags tags = Tags.of(
                        TAG_CONSUMER_GROUP, groupId,
                        TAG_TOPIC, tp.topic(),
                        TAG_PARTITION, String.valueOf(tp.partition())
                );
                long currentOffset = offset.offset();
                updateMetrics(KAFKA_CONSUMERGROUP_CURRENT_OFFSET, tags, currentOffset);

                var latestOffset = latestOffsets.get(tp).offset();
                long lag = Math.max(0, latestOffset - currentOffset);
                updateMetrics(KAFKA_CONSUMERGROUP_LAG, tags, lag);
            });
        });
    }

    private PrometheusMeterRegistry meterRegistry() {
        return prometheusService.getMeterRegistry();
    }

}