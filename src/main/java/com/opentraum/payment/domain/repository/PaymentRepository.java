package com.opentraum.payment.domain.repository;

import com.opentraum.payment.domain.entity.Payment;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PaymentRepository extends ReactiveCrudRepository<Payment, Long> {

    Mono<Payment> findByMerchantUid(String merchantUid);

    Mono<Payment> findByReservationId(Long reservationId);

    Mono<Payment> findByImpUid(String impUid);

    Flux<Payment> findByReservationIdAndStatus(Long reservationId, String status);

    Flux<Payment> findByStatus(String status);
}
