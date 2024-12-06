package com.trolle4.kafka.exporter.collect;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.Getter;
import org.springframework.stereotype.Service;

@Service
public class PrometheusService {

    @Getter
    private PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
}
