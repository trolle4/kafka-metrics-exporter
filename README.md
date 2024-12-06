# Kafka Metrics Exporter

A Spring Boot application that collects and exposes Kafka metrics through Prometheus endpoints. 
This exporter provides detailed metrics about Kafka topics, partitions, and consumer groups, 
making it easier to monitor your Kafka cluster.

The prometheus format is based on https://github.com/danielqsj/kafka_exporter, but since that project does not support 
sasl.mechanism of OAUTHBEARER, I decided to create my own exporter.

## Features

- Collects Kafka metrics including:
    - Topic metrics (partitions, offsets, replicas)
    - Partition metrics (current offset, oldest offset, in-sync replicas)
    - Consumer group metrics (current offset, lag)
- Exposes metrics through two endpoints:
    - `:8081/actuator/kafkametrics` - Spring Boot Actuator endpoint
    - `:8080/metrics` - Standard Prometheus metrics endpoint
- Configurable topic filtering using whitelist and blacklist regex patterns
- Customizable metrics collection interval

## Prerequisites

- Java 21 or higher
- Apache Kafka cluster
- Docker (for container deployment)

## Available Metrics

### Topic Metrics
- `kafka_topic_partitions`: Number of partitions per topic
- `kafka_topic_partition_current_offset`: Current offset for each partition
- `kafka_topic_partition_oldest_offset`: Oldest offset for each partition
- `kafka_topic_partition_in_sync_replica`: Number of in-sync replicas
- `kafka_topic_partition_leader`: Leader broker ID
- `kafka_topic_partition_leader_is_preferred`: Whether the leader is preferred
- `kafka_topic_partition_replicas`: Number of replicas
- `kafka_topic_partition_under_replicated_partition`: Under-replication status

### Consumer Group Metrics
- `kafka_consumergroup_current_offset`: Current offset for consumer groups
- `kafka_consumergroup_lag`: Lag for consumer groups

## Prometheus Metrics Format

The metrics are exposed in the standard Prometheus format. Here's an example of what the metrics look like:

```
# HELP kafka_topic_partitions Number of partitions for the topic
# TYPE kafka_topic_partitions gauge
kafka_topic_partitions{topic="my-topic"} 6.0

# HELP kafka_topic_partition_current_offset Current offset of the partition
# TYPE kafka_topic_partition_current_offset gauge
kafka_topic_partition_current_offset{topic="my-topic",partition="0"} 1000.0
kafka_topic_partition_current_offset{topic="my-topic",partition="1"} 950.0

# HELP kafka_consumergroup_lag Lag for the consumer group
# TYPE kafka_consumergroup_lag gauge
kafka_consumergroup_lag{consumergroup="my-consumer-group",topic="my-topic",partition="0"} 100.0
kafka_consumergroup_lag{consumergroup="my-consumer-group",topic="my-topic",partition="1"} 50.0

# HELP kafka_topic_partition_in_sync_replica Number of in-sync replicas for the partition
# TYPE kafka_topic_partition_in_sync_replica gauge
kafka_topic_partition_in_sync_replica{topic="my-topic",partition="0"} 3.0
```

## Building

### Local Build
```bash
./mvnw clean install
```

### Docker Image Build
```bash
./mvnw spring-boot:build-image -DskipTests
```

## Deployment

### Docker Environment Variables

The following environment variables can be used to configure the application when running in Docker:

```bash
SPRING_KAFKA_BOOTSTRAP_SERVERS      # Kafka bootstrap servers (required)
COLLECTOR_CONF_TOPICWHITELISTREGEX # Topic whitelist regex pattern (optional, default: ".*")
COLLECTOR_CONF_TOPICBLACKLISTREGEX # Topic blacklist regex pattern (optional, default: "^$")
COLLECTOR_CONF_MINTIMEBETWEENUPDATESMILLIS # Metrics update interval (optional, default: 60000)
SERVER_PORT                        # Application port (optional, default: 8080)
```

### Docker Run Command

Basic usage:
```bash
docker run -d \
  -p 8080:8080 \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka1:9092,kafka2:9092 \
  trolle4/kafka-metrics-exporter
```

Advanced configuration:
```bash
docker run -d \
  -p 8080:8080 \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka1:9092,kafka2:9092 \
  -e COLLECTOR_CONF_TOPICWHITELISTREGEX="^(prod|stage).*" \
  -e COLLECTOR_CONF_MINTIMEBETWEENUPDATESMILLIS=30000 \
  -e SERVER_PORT=8080 \
  trolle4/kafka-metrics-exporter
```

### Docker Compose Example

```yaml
version: '3'
services:
  kafka-metrics-exporter:
    image: trolle4/kafka-metrics-exporter
    ports:
      - "8080:8080"
    environment:
      - SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka1:9092,kafka2:9092
      - COLLECTOR_CONF_TOPICWHITELISTREGEX=^(prod|stage).*
      - COLLECTOR_CONF_MINTIMEBETWEENUPDATESMILLIS=30000
    restart: unless-stopped
```

## Running

### Using Docker

The image is available on Docker Hub:
```bash
docker pull trolle4/kafka-metrics-exporter
```

Run the container:
```bash
docker run -p 8080:8080 trolle4/kafka-metrics-exporter
```

### Using Java
```bash
java -jar target/kafka-metrics-exporter.jar
```

## Accessing Metrics

- Actuator metrics: `http://localhost:8080/actuator/kafkametrics`
- Prometheus metrics: `http://localhost:8080/metrics`

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the LICENSE file for details.