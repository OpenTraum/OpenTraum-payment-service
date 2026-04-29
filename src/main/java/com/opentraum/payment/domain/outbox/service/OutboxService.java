package com.opentraum.payment.domain.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentraum.payment.domain.outbox.entity.OutboxEvent;
import com.opentraum.payment.domain.outbox.repository.OutboxRepository;
import com.opentraum.payment.global.exception.BusinessException;
import com.opentraum.payment.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional Outbox 발행 서비스.
 *
 * <p>호출하는 쪽의 {@code @Transactional} 경계에 참여하여 비즈니스 데이터 변경과
 * Outbox 기록을 atomic 하게 수행한다. 실제 Kafka 발행은 Debezium Connector가 담당.
 *
 * <p>payload에 공통 필드(saga_id, reservation_id=aggregateId, occurred_at)를
 * 자동 합성해 이벤트 스키마 합의와 일치시킨다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Outbox 레코드를 저장한다.
     *
     * @param aggregateId    비즈니스 엔티티 ID (payment/reservation 등). reservation_id로도 사용.
     * @param aggregateType  Debezium EventRouter 라우팅 키 ("payment", "reservation", "event")
     * @param eventType      이벤트 타입 이름 ("PaymentCompleted" 등)
     * @param sagaId         SAGA 상관관계 UUID
     * @param payload        이벤트 본문. saga_id/reservation_id/occurred_at은 자동 주입.
     * @return 저장된 {@link OutboxEvent}
     */
    public Mono<OutboxEvent> publish(
            Long aggregateId,
            String aggregateType,
            String eventType,
            String sagaId,
            Map<String, Object> payload
    ) {
        LocalDateTime occurredAt = LocalDateTime.now();
        String eventId = UUID.randomUUID().toString();

        Map<String, Object> enriched = new LinkedHashMap<>();
        enriched.put("saga_id", sagaId);
        enriched.put("reservation_id", aggregateId);
        enriched.put("occurred_at", occurredAt.atOffset(ZoneOffset.UTC).format(ISO_MILLIS));
        if (payload != null) {
            payload.forEach(enriched::putIfAbsent);
        }

        final String json;
        try {
            json = objectMapper.writeValueAsString(enriched);
        } catch (JsonProcessingException e) {
            log.error("Outbox payload 직렬화 실패: eventType={}, sagaId={}", eventType, sagaId, e);
            return Mono.error(new BusinessException(ErrorCode.INTERNAL_ERROR, "Outbox payload 직렬화 실패"));
        }

        OutboxEvent event = OutboxEvent.builder()
                .eventId(eventId)
                .aggregateId(aggregateId)
                .aggregateType(aggregateType)
                .eventType(eventType)
                .sagaId(sagaId)
                .payload(json)
                .occurredAt(occurredAt)
                .build();

        return outboxRepository.save(event)
                .doOnSuccess(saved -> log.info(
                        "Outbox 기록: eventId={}, type={}, aggregate={}:{}, sagaId={}",
                        saved.getEventId(), saved.getEventType(),
                        saved.getAggregateType(), saved.getAggregateId(), saved.getSagaId()
                ));
    }
}
