package com.trolle4.kafka.exporter.conf;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaConf {

    @Bean
    public AdminClient kafkaAdminClient(KafkaProperties kafkaProperties, SslBundles sslBundles) {
        return AdminClient.create(kafkaProperties.buildAdminProperties(sslBundles));
    }
}