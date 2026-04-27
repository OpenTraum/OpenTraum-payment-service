package com.opentraum.payment.domain.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentraum.payment.domain.client.PortOneClient;
import com.opentraum.payment.domain.entity.Payment;
import com.opentraum.payment.domain.entity.PaymentStatus;
import com.opentraum.payment.domain.outbox.service.IdempotencyService;
import com.opentraum.payment.domain.outbox.service.OutboxService;
import com.opentraum.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * reservation 토픽의 {@code ReservationRefundRequested} 이벤트 소비자.
 *
 * <p>흐름:
 * <ol>
 *   <li>이벤트 타입 필터링 (Refund 요청만)</li>
 *   <li>멱등성 체크</li>
 *   <li>PortOne cancel 호출 (트랜잭션 밖)</li>
 *   <li>성공 → payments = REFUNDED + {@code RefundCompleted} Outbox</li>
 *   <li>실패 → {@code RefundFailed} Outbox (수동 대응용)</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundConsumer {

    private static final String CONSUMER_GROUP = "payment-saga-group";
    private static final String TARGET_EVENT_TYPE = "ReservationRefundRequested";
    private static final String AGGREGATE_TYPE = "payment";
    private static final String EVENT_REFUND_COMPLETED = "RefundCompleted";
    private static final String EVENT_REFUND_FAILED = "RefundFailed";

    private final PortOneClient portOneClient;
    private final PaymentRepository paymentRepository;
    private final OutboxService outboxService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final TransactionalOperator transactionalOperator;

    @KafkaListener(
            topics = {"opentraum.reservation"},
            groupId = CONSUMER_GROUP
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventId = headerString(record, "event_id");
        String eventType = headerString(record, "event_type");
        String sagaId = headerString(record, "saga_id");

        if (!TARGET_EVENT_TYPE.equals(eventType)) {
            ack.acknowledge();
            return;
        }
        if (eventId == null) {
            log.warn("[RefundConsumer] event_id 누락: offset={}", record.offset());
            ack.acknowledge();
            return;
        }

        log.info("[RefundConsumer] 수신: eventId={}, sagaId={}", eventId, sagaId);

        idempotencyService.isProcessed(eventId, CONSUMER_GROUP)
                .flatMap(processed -> {
                    if (Boolean.TRUE.equals(processed)) {
                        log.info("[RefundConsumer] 중복 skip: eventId={}", eventId);
                        return Mono.empty();
                    }
                    return handle(record.value(), sagaId, eventId);
                })
                .doOnError(e -> log.error("[RefundConsumer] 처리 실패: eventId={}, err={}",
                        eventId, e.getMessage(), e))
                .doFinally(sig -> ack.acknowledge())
                .subscribe();
    }

    private Mono<Void> handle(String payload, String sagaIdHeader, String eventId) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.error("[RefundConsumer] payload 파싱 실패: {}", payload, e);
            return Mono.empty();
        }

        String sagaId = sagaIdHeader != null ? sagaIdHeader : textOrNull(node, "saga_id");
        Long reservationId = longOrNull(node, "reservation_id");
        Long paymentId = longOrNull(node, "payment_id");
        if (reservationId == null) {
            log.warn("[RefundConsumer] reservation_id 누락: eventId={}", eventId);
            return Mono.empty();
        }

        return findPayment(paymentId, reservationId)
                .flatMap(payment -> {
                    // 이미 결제 실패(=취소 대상 아님)인 경우 skip
                    if (PaymentStatus.FAILED.name().equals(payment.getStatus())
                            || PaymentStatus.REFUNDED.name().equals(payment.getStatus())) {
                        log.info("[RefundConsumer] payment status={} 이라 환불 skip: reservationId={}",
                                payment.getStatus(), reservationId);
                        return idempotencyService.markProcessed(eventId, CONSUMER_GROUP);
                    }
                    return doRefund(payment, sagaId, eventId);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[RefundConsumer] payment 없음, Failed 발행: reservationId={}", reservationId);
                    return publishFailed(reservationId, paymentId, sagaId, "PAYMENT_NOT_FOUND", eventId);
                }));
    }

    private Mono<Payment> findPayment(Long paymentId, Long reservationId) {
        if (paymentId != null) {
            return paymentRepository.findById(paymentId);
        }
        return paymentRepository.findByReservationId(reservationId);
    }

    private Mono<Void> doRefund(Payment payment, String sagaId, String eventId) {
        String merchantUid = payment.getMerchantUid();
        int amount = payment.getAmount() == null ? 0 : payment.getAmount();

        return portOneClient.cancelPayment(merchantUid, amount, "USER_CANCELLED")
                .flatMap(result -> {
                    if (result.isSuccess()) {
                        return persistCompleted(payment, sagaId, eventId);
                    }
                    return publishFailed(payment.getReservationId(), payment.getId(), sagaId,
                            "REFUND_REJECTED", eventId);
                })
                .onErrorResume(err -> {
                    log.error("[RefundConsumer] PortOne 환불 실패: reservationId={}, err={}",
                            payment.getReservationId(), err.getMessage());
                    return publishFailed(payment.getReservationId(), payment.getId(), sagaId,
                            err.getMessage() == null ? "REFUND_ERROR" : err.getMessage(), eventId);
                });
    }

    private Mono<Void> persistCompleted(Payment payment, String sagaId, String eventId) {
        LocalDateTime now = LocalDateTime.now();
        payment.setStatus(PaymentStatus.REFUNDED.name());
        payment.setUpdatedAt(now);

        // markProcessed 를 트랜잭션 안에 포함해야 outbox + processed_events 가 원자적으로 커밋된다.
        // 분리하면 outbox publish 성공 + markProcessed 실패 시 consumer 재시작에서 같은 이벤트를
        // 또 처리해 RefundCompleted 가 중복 발행된다.
        Mono<Void> tx = paymentRepository.save(payment)
                .flatMap(saved -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("payment_id", saved.getId());
                    payload.put("amount", saved.getAmount());
                    return outboxService.publish(
                            saved.getReservationId(),
                            AGGREGATE_TYPE,
                            EVENT_REFUND_COMPLETED,
                            sagaId,
                            payload);
                })
                .then(idempotencyService.markProcessed(eventId, CONSUMER_GROUP))
                .then();

        return transactionalOperator.transactional(tx);
    }

    private Mono<Void> publishFailed(Long reservationId, Long paymentId, String sagaId, String reason, String eventId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_id", paymentId);
        payload.put("reason", reason);
        // markProcessed 를 동일 트랜잭션에 포함 — 중복 RefundFailed 발행 방지.
        Mono<Void> tx = outboxService.publish(
                        reservationId,
                        AGGREGATE_TYPE,
                        EVENT_REFUND_FAILED,
                        sagaId,
                        payload)
                .then(idempotencyService.markProcessed(eventId, CONSUMER_GROUP))
                .then();
        return transactionalOperator.transactional(tx);
    }

    private String headerString(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asLong();
    }
}
