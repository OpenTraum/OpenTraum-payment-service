package com.opentraum.payment.domain.service;

import com.opentraum.payment.config.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * refund 토픽 Consumer
 * 실제 환불 처리 로직 연결은 추후 이슈에서 진행
 */
@Slf4j
@Component
public class RefundConsumer {

    @KafkaListener(
            topics = KafkaTopics.REFUND,
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handleRefund(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("[Kafka] refund 수신: topic={}, offset={}, message={}",
                topic, offset, message);

        // TODO: 실제 환불 처리 로직 연결 (추후 이슈)
    }
}
