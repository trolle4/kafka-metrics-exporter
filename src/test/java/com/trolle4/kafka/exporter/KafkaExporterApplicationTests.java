package com.trolle4.kafka.exporter;

import com.trolle4.kafka.exporter.collect.KafkaMetricsCollector;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.kafka.KafkaContainer;

import java.util.Collections;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Slf4j
@ContextConfiguration(initializers = KafkaExporterApplicationTests.KafkaTestContainerConfig.KafkaInitializer.class)
class KafkaExporterApplicationTests {

    private static final String TOPIC_NAME = "test-topic";

    static KafkaContainer kafka;

    @Autowired
    AdminClient adminClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    KafkaMetricsCollector kafkaMetricsCollector;

    @AfterAll
    static void stopKafka() {
        kafka.stop();
    }

    @TestConfiguration
    static class KafkaTestContainerConfig {
        static class KafkaInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
            @Override
            public void initialize(ConfigurableApplicationContext context) {
                kafka = new KafkaContainer("apache/kafka-native:3.8.0");
                kafka.start();

                // Set the bootstrap servers property before Spring creates Kafka beans
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                        context,
                        "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers()
                );
            }
        }
    }


    @SneakyThrows
    @Test
    void contextLoads() {

        adminClient.createTopics(Collections.singletonList(new NewTopic(TOPIC_NAME, 1, (short) 1)))
                .all().get();


        kafkaMetricsCollector.updateMetrics();

        //Verify that the metrics are updated
        ResponseEntity<String> response = restTemplate.getForEntity("/metrics", String.class);

        // Assert status code
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        // Get response body
        String metricsOutput = response.getBody();
        assertThat(metricsOutput).isNotNull();

        // Expected metrics content
        String[] expectedMetrics = {
                "# HELP kafka_topic_partition_current_offset",
                "# TYPE kafka_topic_partition_current_offset gauge",
                "kafka_topic_partition_current_offset{partition=\"0\",topic=\"test-topic\"} 0.0",
                "# HELP kafka_topic_partition_in_sync_replica",
                "# TYPE kafka_topic_partition_in_sync_replica gauge",
                "kafka_topic_partition_in_sync_replica{partition=\"0\",topic=\"test-topic\"} 1.0",
                "# HELP kafka_topic_partition_leader",
                "# TYPE kafka_topic_partition_leader gauge",
                "kafka_topic_partition_leader{partition=\"0\",topic=\"test-topic\"} 1.0",
                "# HELP kafka_topic_partition_leader_is_preferred",
                "# TYPE kafka_topic_partition_leader_is_preferred gauge",
                "kafka_topic_partition_leader_is_preferred{partition=\"0\",topic=\"test-topic\"} 1.0",
                "# HELP kafka_topic_partition_oldest_offset",
                "# TYPE kafka_topic_partition_oldest_offset gauge",
                "kafka_topic_partition_oldest_offset{partition=\"0\",topic=\"test-topic\"} 0.0",
                "# HELP kafka_topic_partition_replicas",
                "# TYPE kafka_topic_partition_replicas gauge",
                "kafka_topic_partition_replicas{partition=\"0\",topic=\"test-topic\"} 1.0",
                "# HELP kafka_topic_partition_under_replicated_partition",
                "# TYPE kafka_topic_partition_under_replicated_partition gauge",
                "kafka_topic_partition_under_replicated_partition{partition=\"0\",topic=\"test-topic\"} 0.0",
                "# HELP kafka_topic_partitions",
                "# TYPE kafka_topic_partitions gauge",
                "kafka_topic_partitions{topic=\"test-topic\"} 1.0"
        };

        // Normalize line endings and split response into lines
        String[] actualLines = metricsOutput.replaceAll("\r\n", "\n").split("\n");

        // Assert each expected line exists in the response
        for (String expectedLine : expectedMetrics) {
            assertThat(actualLines).anyMatch(line ->
                    line.trim().equals(expectedLine.trim())
            );
        }
    }

}
