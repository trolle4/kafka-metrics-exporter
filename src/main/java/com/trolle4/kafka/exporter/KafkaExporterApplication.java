package com.trolle4.kafka.exporter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KafkaExporterApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaExporterApplication.class, args);
    }

}
