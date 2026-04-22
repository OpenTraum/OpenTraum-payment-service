package com.opentraum.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentServiceApplication {

    public static void main(String[] args) {
        // Reactor Context <-> MDC/ThreadLocal 자동 전파 (Micrometer Tracing traceId MDC 포함)
        reactor.core.publisher.Hooks.enableAutomaticContextPropagation();
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
