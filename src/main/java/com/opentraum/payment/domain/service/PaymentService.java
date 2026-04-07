package com.opentraum.payment.domain.service;

import com.opentraum.payment.domain.constants.PaymentConstants;
import com.opentraum.payment.domain.dto.PaymentInitResponse;
import com.opentraum.payment.domain.dto.WebhookRequest;
import com.opentraum.payment.domain.entity.Payment;
import com.opentraum.payment.domain.entity.PaymentStatus;
import com.opentraum.payment.domain.repository.PaymentRepository;
import com.opentraum.payment.domain.repository.PaymentQueryRepository;
import com.opentraum.payment.global.exception.BusinessException;
import com.opentraum.payment.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentQueryRepository paymentQueryRepository;
    private final PortOneClient portOneClient;
    private final PaymentTimerService timerService;

    // 결제 준비 (결제창 호출 전)
    public Mono<PaymentInitResponse> initiatePayment(Long reservationId, Integer amount,
                                                      String itemName, Long tenantId, Long userId) {
        String merchantUid = generateMerchantUid();

        Payment payment = Payment.builder()
                .reservationId(reservationId)
                .merchantUid(merchantUid)
                .amount(amount)
                .tenantId(tenantId)
                .userId(userId)
                .status(PaymentStatus.PENDING.name())
                .createdAt(LocalDateTime.now())
                .build();

        return paymentRepository.save(payment)
                .flatMap(saved -> timerService.startPaymentTimer(reservationId)
                        .thenReturn(saved))
                .map(saved -> PaymentInitResponse.builder()
                        .paymentId(saved.getId())
                        .merchantUid(saved.getMerchantUid())
                        .amount(saved.getAmount())
                        .itemName(itemName)
                        .timeoutSeconds(PaymentConstants.PAYMENT_DEADLINE_MINUTES * 60)
                        .build());
    }

    // 결제 완료 처리 - PortOne Webhook 수신
    public Mono<Payment> completePayment(WebhookRequest request) {
        return paymentRepository.findByMerchantUid(request.getMerchantUid())
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND)))
                // 1. 이미 완료된 결제 중복 처리 방지
                .flatMap(payment -> {
                    if (PaymentStatus.COMPLETED.name().equals(payment.getStatus())) {
                        return Mono.error(new BusinessException(ErrorCode.PAYMENT_ALREADY_COMPLETED));
                    }
                    return Mono.just(payment);
                })
                // 2. PG사 금액 검증 (V2: merchantUid = PortOne paymentId)
                .flatMap(payment -> portOneClient.verifyPayment(request.getMerchantUid())
                        .flatMap(verification -> {
                            if (!payment.getAmount().equals(verification.getAmount())) {
                                log.error("결제 금액 불일치: expected={}, actual={}",
                                        payment.getAmount(), verification.getAmount());
                                return Mono.error(new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH));
                            }
                            payment.setImpUid(request.getImpUid());
                            payment.setStatus(PaymentStatus.COMPLETED.name());
                            payment.setPaidAt(LocalDateTime.now());
                            payment.setUpdatedAt(LocalDateTime.now());
                            return paymentRepository.save(payment);
                        }))
                // 3. 타이머 취소
                .flatMap(payment -> timerService.cancelPaymentTimer(payment.getReservationId())
                        .thenReturn(payment))
                .doOnSuccess(payment -> log.info("결제 완료: paymentId={}, merchantUid={}",
                        payment.getId(), payment.getMerchantUid()));
    }

    // 환불 처리
    public Mono<PortOneClient.RefundResult> refundPayment(Long paymentId, String reason) {
        return paymentRepository.findById(paymentId)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND)))
                .flatMap(payment -> portOneClient.cancelPayment(
                        payment.getMerchantUid(),
                        payment.getAmount(),
                        reason
                ).flatMap(result -> {
                    if (result.isSuccess()) {
                        payment.setStatus(PaymentStatus.REFUNDED.name());
                        payment.setUpdatedAt(LocalDateTime.now());
                        return paymentRepository.save(payment).thenReturn(result);
                    }
                    return Mono.just(result);
                }));
    }

    // 결제 단건 조회
    public Mono<Payment> getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND)));
    }

    // 예약 기준 결제 조회
    public Mono<Payment> getPaymentByReservationId(Long reservationId) {
        return paymentRepository.findByReservationId(reservationId)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND)));
    }

    // 내 결제 목록 조회
    public Flux<Payment> getMyPayments(Long userId) {
        return paymentQueryRepository.findByUserId(userId);
    }

    private String generateMerchantUid() {
        return "OT_" + System.currentTimeMillis() + "_" +
                UUID.randomUUID().toString().substring(0, 8);
    }
}
