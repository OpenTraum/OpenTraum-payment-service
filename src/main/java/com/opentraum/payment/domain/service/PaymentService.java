package com.opentraum.payment.domain.service;

import com.opentraum.payment.domain.dto.PaymentRequest;
import com.opentraum.payment.domain.dto.PaymentResponse;
import com.opentraum.payment.domain.dto.RefundRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PaymentService {

    Mono<PaymentResponse> processPayment(PaymentRequest request);

    Mono<PaymentResponse> getPaymentById(Long id);

    Mono<PaymentResponse> getPaymentByReservationId(Long reservationId);

    Flux<PaymentResponse> getPaymentsByUserId(Long userId);

    Mono<PaymentResponse> refund(RefundRequest request);
}
