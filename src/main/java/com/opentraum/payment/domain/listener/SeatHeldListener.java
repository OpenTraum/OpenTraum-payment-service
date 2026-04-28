package com.opentraum.payment.domain.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentraum.payment.domain.outbox.service.IdempotencyService;
import com.opentraum.payment.domain.service.PaymentSagaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * event-service Outbox 토픽({@code opentraum.event})의 {@code SeatHeld} 이벤트를 소비한다.
 *
 * <p>멱등성: {@link IdempotencyService}로 중복 처리를 차단한다.
 * <p>수신 -> 결제 SAGA 단계({@link PaymentSagaService#process})를 호출하고 처리 후
 * {@code markProcessed}를 기록한 뒤 Kafka ack를 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatHeldListener {

    private static final String CONSUMER_GROUP = "payment-saga-group";
    private static final String TARGET_EVENT_TYPE = "SeatHeld";

    private final PaymentSagaService paymentSagaService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "opentraum.event",
            groupId = CONSUMER_GROUP
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventId = headerString(record, "event_id");
        String eventType = headerString(record, "event_type");
        String sagaId = headerString(record, "saga_id");

        if (eventType == null) {
            log.debug("[SeatHeldListener] event_type 헤더 없음, skip: offset={}", record.offset());
            ack.acknowledge();
            return;
        }
        if (!TARGET_EVENT_TYPE.equals(eventType)) {
            log.debug("[SeatHeldListener] {} 이벤트 무시: offset={}", eventType, record.offset());
            ack.acknowledge();
            return;
        }
        if (eventId == null) {
            log.warn("[SeatHeldListener] event_id 헤더 누락: offset={}", record.offset());
            ack.acknowledge();
            return;
        }

        String value = record.value();
        log.info("[SeatHeldListener] 수신: eventId={}, sagaId={}, offset={}",
                eventId, sagaId, record.offset());

        idempotencyService.isProcessed(eventId, CONSUMER_GROUP)
                .flatMap(processed -> {
                    if (Boolean.TRUE.equals(processed)) {
                        log.info("[SeatHeldListener] 중복 이벤트 skip: eventId={}", eventId);
                        return Mono.empty();
                    }
                    return handle(value, sagaId, eventId);
                })
                .doOnError(e -> log.error("[SeatHeldListener] 처리 실패: eventId={}, err={}",
                        eventId, e.getMessage(), e))
                .doFinally(sig -> ack.acknowledge())
                .subscribe();
    }

    private Mono<Void> handle(String payload, String sagaId, String eventId) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.error("[SeatHeldListener] payload 파싱 실패: {}", payload, e);
            return Mono.empty();
        }

        String effectiveSagaId = sagaId != null ? sagaId : textOrNull(node, "saga_id");
        Long reservationId = longOrNull(node, "reservation_id");
        Long scheduleId = longOrNull(node, "schedule_id");

        if (reservationId == null) {
            log.warn("[SeatHeldListener] reservation_id 누락, skip: eventId={}", eventId);
            return Mono.empty();
        }

        // SeatHeld payload에는 payment 관련 필드가 없을 수 있다.
        // 필요한 값은 기본치로 채워 SAGA 단계를 진행한다(정확한 값은 Wave 3에서 통합).
        Long userId = longOrNull(node, "user_id");
        Integer amount = intOrNull(node, "amount");
        String trackType = textOrDefault(node, "track_type", "LIVE");
        String impUid = textOrDefault(node, "imp_uid", "saga-" + reservationId);
        // event-service의 SeatSagaService.publishSeatHeld가 schedule.tenant_id를 자동 머지함
        String tenantId = textOrNull(node, "tenant_id");

        return paymentSagaService.process(
                        reservationId,
                        effectiveSagaId,
                        userId,
                        scheduleId,
                        amount,
                        trackType,
                        impUid,
                        tenantId)
                .then(idempotencyService.markProcessed(eventId, CONSUMER_GROUP));
    }

    private String headerString(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String textOrDefault(JsonNode node, String field, String def) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? def : n.asText();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asLong();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asInt();
    }
}
