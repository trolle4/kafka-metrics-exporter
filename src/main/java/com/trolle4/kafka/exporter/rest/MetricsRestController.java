package com.trolle4.kafka.exporter.rest;

import com.trolle4.kafka.exporter.collect.KafkaMetricsCollector;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RestController;

@RestController
@WebEndpoint(id = "kafka-metrics")
@RequiredArgsConstructor
public class MetricsRestController {

    private final KafkaMetricsCollector kafkaMetricsCollector;

    @ReadOperation(produces = MediaType.TEXT_PLAIN_VALUE)
    public String customMetrics() {
        return kafkaMetricsCollector.getMeterRegistry().scrape();
    }

}
