package com.opentraum.payment.domain.controller;

import com.opentraum.payment.domain.dto.PaymentRequest;
import com.opentraum.payment.domain.dto.PaymentResponse;
import com.opentraum.payment.domain.dto.RefundRequest;
import com.opentraum.payment.domain.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PaymentResponse> processPayment(@Valid @RequestBody PaymentRequest request) {
        return paymentService.processPayment(request);
    }

    @GetMapping("/{id}")
    public Mono<PaymentResponse> getPayment(@PathVariable Long id) {
        return paymentService.getPaymentById(id);
    }

    @GetMapping("/reservation/{reservationId}")
    public Mono<PaymentResponse> getPaymentByReservation(@PathVariable Long reservationId) {
        return paymentService.getPaymentByReservationId(reservationId);
    }

    @GetMapping("/user/{userId}")
    public Flux<PaymentResponse> getPaymentsByUser(@PathVariable Long userId) {
        return paymentService.getPaymentsByUserId(userId);
    }

    @PostMapping("/refund")
    public Mono<PaymentResponse> refund(@Valid @RequestBody RefundRequest request) {
        return paymentService.refund(request);
    }
}
