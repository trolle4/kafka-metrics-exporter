package com.trolle4.kafka.exporter.actuator;

import com.trolle4.kafka.exporter.collect.PrometheusService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@WebEndpoint(id = "kafkametrics")
@RequiredArgsConstructor
public class KafkaMetricsActuator {

    private final PrometheusService prometheusService;

    @ReadOperation(produces = MediaType.TEXT_PLAIN_VALUE)
    @GetMapping(path = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public String customMetrics() {
        return prometheusService.getMeterRegistry().scrape();
    }

}
