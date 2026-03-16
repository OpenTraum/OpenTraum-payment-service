package com.opentraum.payment.domain.repository;

import com.opentraum.payment.domain.entity.Refund;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

public interface RefundRepository extends R2dbcRepository<Refund, Long> {

    Flux<Refund> findByPaymentId(Long paymentId);
}
