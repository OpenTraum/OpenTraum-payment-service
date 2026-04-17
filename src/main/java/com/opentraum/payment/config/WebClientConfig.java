package com.opentraum.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient reservationWebClient(
            @Value("${opentraum.services.reservation-url:http://reservation-service:8084}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient eventWebClient(
            @Value("${opentraum.services.event-url:http://event-service:8083}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }
}
