package com.opentraum.payment.domain.outbox.service;

import com.opentraum.payment.domain.outbox.entity.ProcessedEvent;
import com.opentraum.payment.domain.outbox.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Consumer 멱등성 게이트.
 *
 * <p>각 Consumer Group은 처리 전 {@link #isProcessed}로 중복 여부를 확인하고,
 * 처리 성공 시 {@link #markProcessed}로 기록한다. {@code event_id} PK 제약으로
 * 중복 insert는 DB 레벨에서도 차단된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final ProcessedEventRepository processedEventRepository;

    /**
     * 이미 처리된 이벤트인지 확인한다.
     *
     * @param eventId       Kafka 헤더/Outbox의 event_id
     * @param consumerGroup 로그용 consumer_group (저장된 레코드와 그룹 불일치는 별도 경고)
     */
    public Mono<Boolean> isProcessed(String eventId, String consumerGroup) {
        return processedEventRepository.existsByEventId(eventId)
                .doOnNext(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.debug("멱등성 HIT: eventId={}, group={}", eventId, consumerGroup);
                    }
                });
    }

    /**
     * 이벤트 처리 완료를 기록한다. PK 충돌(이미 처리됨)은 무시한다.
     */
    public Mono<Void> markProcessed(String eventId, String consumerGroup) {
        ProcessedEvent record = ProcessedEvent.builder()
                .eventId(eventId)
                .consumerGroup(consumerGroup)
                .processedAt(LocalDateTime.now())
                .build();

        return processedEventRepository.save(record)
                .doOnSuccess(saved -> log.debug(
                        "processed_events 기록: eventId={}, group={}", eventId, consumerGroup
                ))
                .onErrorResume(e -> {
                    log.warn("processed_events 기록 실패(중복 가능): eventId={}, group={}, msg={}",
                            eventId, consumerGroup, e.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
