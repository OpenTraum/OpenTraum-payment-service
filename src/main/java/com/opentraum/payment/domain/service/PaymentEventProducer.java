package com.opentraum.payment.domain.service;

import com.opentraum.payment.config.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    // 결제 완료 이벤트 발행 -> seat-assignment 토픽
    // key: reservationId (같은 예약의 이벤트 순서 보장)
    public Mono<Void> sendSeatAssignmentEvent(Long reservationId, Long userId,
                                               Long scheduleId, String grade) {
        String message = String.format(
                "{\"reservationId\":%d,\"userId\":%d,\"scheduleId\":%d,\"grade\":\"%s\"}",
                reservationId, userId, scheduleId, grade
        );

        return Mono.fromCallable(() -> kafkaTemplate.send(
                        KafkaTopics.SEAT_ASSIGNMENT,
                        String.valueOf(reservationId),
                        message))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(result -> log.info("[Kafka] seat-assignment 발행: reservationId={}, grade={}",
                        reservationId, grade))
                .doOnError(e -> log.error("[Kafka] seat-assignment 발행 실패: reservationId={}, error={}",
                        reservationId, e.getMessage()))
                .then();
    }

    // 환불 이벤트 발행 -> refund 토픽
    public Mono<Void> sendRefundEvent(Long paymentId, Long reservationId, int amount, String reason) {
        String message = String.format(
                "{\"paymentId\":%d,\"reservationId\":%d,\"amount\":%d,\"reason\":\"%s\"}",
                paymentId, reservationId, amount, reason
        );

        return Mono.fromCallable(() -> kafkaTemplate.send(
                        KafkaTopics.REFUND,
                        String.valueOf(paymentId),
                        message))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(result -> log.info("[Kafka] refund 발행: paymentId={}, amount={}",
                        paymentId, amount))
                .doOnError(e -> log.error("[Kafka] refund 발행 실패: paymentId={}, error={}",
                        paymentId, e.getMessage()))
                .then();
    }
}
