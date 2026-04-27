package com.opentraum.payment.domain.outbox.repository;

import com.opentraum.payment.domain.outbox.entity.OutboxEvent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface OutboxRepository extends ReactiveCrudRepository<OutboxEvent, Long> {
}
