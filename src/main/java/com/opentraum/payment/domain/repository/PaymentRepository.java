package com.opentraum.payment.domain.repository;

import com.opentraum.payment.domain.entity.Payment;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PaymentRepository extends R2dbcRepository<Payment, Long> {

    Mono<Payment> findByReservationId(Long reservationId);

    Flux<Payment> findByUserId(Long userId);

    Flux<Payment> findByTenantId(Long tenantId);

    Flux<Payment> findByStatus(Payment.PaymentStatus status);
}
