package com.opentraum.payment.domain.outbox.repository;

import com.opentraum.payment.domain.outbox.entity.ProcessedEvent;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ProcessedEventRepository extends ReactiveCrudRepository<ProcessedEvent, String> {

    @Query("SELECT COUNT(*) FROM processed_events WHERE event_id = :eventId")
    Mono<Long> countByEventId(String eventId);

    default Mono<Boolean> existsByEventId(String eventId) {
        return countByEventId(eventId).map(c -> c > 0);
    }
}
