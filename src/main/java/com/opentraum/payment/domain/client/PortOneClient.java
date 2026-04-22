package com.opentraum.payment.domain.client;

import com.opentraum.payment.domain.entity.PaymentStatus;
import reactor.core.publisher.Mono;

/**
 * PortOne 결제 게이트웨이 호환 클라이언트.
 *
 * <p>Production(실제 PortOne HTTP 호출)과 Mock(프로필 기반) 구현을 분리하기 위해
 * 인터페이스로 추상화한다. Payload/Result DTO는 인터페이스 내부 static class로
 * 노출하여 호출측이 구현 패키지를 알 필요 없도록 한다.
 */
public interface PortOneClient {

    /**
     * 결제 검증 (V2).
     *
     * @param paymentId PortOne paymentId = 우리 merchantUid
     */
    Mono<PaymentVerificationResult> verifyPayment(String paymentId);

    /**
     * 결제 검증 (amount 힌트 포함, webhook 경로용).
     * Mock 구현은 이 amount 를 그대로 echo 하여 PAYMENT_AMOUNT_MISMATCH 를 피함.
     * 실구현은 amount 를 무시하고 PG 에서 실제 금액을 조회.
     */
    default Mono<PaymentVerificationResult> verifyPayment(String paymentId, Integer expectedAmount) {
        return verifyPayment(paymentId);
    }

    /**
     * 결제 취소/환불 (V2).
     */
    Mono<RefundResult> cancelPayment(String paymentId, int amount, String reason);

    @lombok.Builder
    @lombok.Getter
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    class PaymentVerificationResult {
        private String paymentId;
        private String transactionId;
        private Integer amount;
        private PaymentStatus status;
    }

    @lombok.Builder
    @lombok.Getter
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    class RefundResult {
        private boolean success;
        private String message;
    }
}
