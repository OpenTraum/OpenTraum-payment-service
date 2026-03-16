package com.opentraum.payment.domain.service;

import com.opentraum.payment.domain.dto.PaymentRequest;
import com.opentraum.payment.domain.dto.PaymentResponse;
import com.opentraum.payment.domain.dto.RefundRequest;
import com.opentraum.payment.domain.entity.Payment;
import com.opentraum.payment.domain.entity.Refund;
import com.opentraum.payment.domain.repository.PaymentRepository;
import com.opentraum.payment.domain.repository.RefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Transactional
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public PaymentServiceImpl(PaymentRepository paymentRepository, RefundRepository refundRepository) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    @Override
    public Mono<PaymentResponse> processPayment(PaymentRequest request) {
        Payment payment = new Payment();
        payment.setReservationId(request.reservationId());
        payment.setUserId(request.userId());
        payment.setTenantId(request.tenantId());
        payment.setAmount(request.amount());
        payment.setCurrency(request.currency());
        payment.setMethod(request.method());
        payment.setStatus(Payment.PaymentStatus.PENDING);

        return paymentRepository.save(payment)
                .map(PaymentResponse::from);
        // TODO: PG 연동 및 Kafka 이벤트 발행
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<PaymentResponse> getPaymentById(Long id) {
        return paymentRepository.findById(id)
                .map(PaymentResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<PaymentResponse> getPaymentByReservationId(Long reservationId) {
        return paymentRepository.findByReservationId(reservationId)
                .map(PaymentResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<PaymentResponse> getPaymentsByUserId(Long userId) {
        return paymentRepository.findByUserId(userId)
                .map(PaymentResponse::from);
    }

    @Override
    public Mono<PaymentResponse> refund(RefundRequest request) {
        return paymentRepository.findById(request.paymentId())
                .flatMap(payment -> {
                    Refund refund = new Refund();
                    refund.setPaymentId(payment.getId());
                    refund.setAmount(request.amount());
                    refund.setReason(request.reason());
                    refund.setStatus(Refund.RefundStatus.PENDING);

                    payment.setStatus(Payment.PaymentStatus.REFUNDED);

                    return refundRepository.save(refund)
                            .then(paymentRepository.save(payment))
                            .map(PaymentResponse::from);
                    // TODO: PG 환불 연동 및 Kafka 이벤트 발행
                });
    }
}
