package com.trolle4.kafka.exporter.rest;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MetricsRestController {

//    private final PrometheusMeterRegistry prometheusMeterRegistry;
//    private final KafkaMetricsCollector kafkaMetricsCollector;
//
//    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
//    public String metrics() {
//        kafkaMetricsCollector.updateMetrics();
//        return prometheusMeterRegistry.scrape();
//    }

}
