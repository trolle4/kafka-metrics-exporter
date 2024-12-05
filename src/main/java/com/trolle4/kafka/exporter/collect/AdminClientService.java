package com.trolle4.kafka.exporter.collect;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminClientService {
    private final AdminClient adminClient;

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

    Set<String> listTopics() throws InterruptedException, ExecutionException, TimeoutException {
        return adminClient.listTopics().names().get(10, TimeUnit.SECONDS);
    }

    Map<String, TopicDescription> describeTopics(Set<String> topics) throws InterruptedException, ExecutionException, TimeoutException {
        return adminClient.describeTopics(topics).allTopicNames().get(10, TimeUnit.SECONDS);
    }

}
