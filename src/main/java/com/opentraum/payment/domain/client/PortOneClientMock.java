package com.opentraum.payment.domain.client;

import com.opentraum.payment.domain.entity.PaymentStatus;
import com.opentraum.payment.global.exception.BusinessException;
import com.opentraum.payment.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Mock PortOne 클라이언트.
 *
 * <p>SAGA 통합 테스트/로컬 개발용. {@code spring.profiles.active=mock} 일 때만
 * 활성화되며 {@code @Primary}로 실구현체를 대체한다.
 *
 * <p>입력 imp_uid(혹은 paymentId) 값으로 분기:
 * <ul>
 *   <li>"fail-card" → 카드 거절(CARD_DECLINED) BusinessException</li>
 *   <li>"fail-timeout" → 3초 지연 후 TIMEOUT 에러</li>
 *   <li>그 외 → 성공 응답</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("mock")
@Primary
public class PortOneClientMock implements PortOneClient {

    @Override
    public Mono<PaymentVerificationResult> verifyPayment(String paymentId) {
        return verifyPayment(paymentId, 0);
    }

    @Override
    public Mono<PaymentVerificationResult> verifyPayment(String paymentId, Integer expectedAmount) {
        log.info("[MOCK] verifyPayment: paymentId={}, expectedAmount={}", paymentId, expectedAmount);

        if (paymentId == null) {
            return successResult(paymentId, expectedAmount);
        }

        return switch (paymentId) {
            case "fail-card" -> Mono.defer(() -> Mono.error(
                    new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "CARD_DECLINED")));
            case "fail-timeout" -> Mono.delay(Duration.ofSeconds(3))
                    .flatMap(t -> Mono.error(
                            new BusinessException(ErrorCode.INTERNAL_ERROR, "TIMEOUT")));
            default -> successResult(paymentId, expectedAmount);
        };
    }

    @Override
    public Mono<RefundResult> cancelPayment(String paymentId, int amount, String reason) {
        log.info("[MOCK] cancelPayment: paymentId={}, amount={}, reason={}",
                paymentId, amount, reason);

        if ("fail-refund".equals(paymentId)) {
            return Mono.error(new BusinessException(ErrorCode.REFUND_FAILED, "MOCK_REFUND_DECLINED"));
        }

        return Mono.just(RefundResult.builder()
                .success(true)
                .message(reason)
                .build());
    }

    private Mono<PaymentVerificationResult> successResult(String paymentId, Integer expectedAmount) {
        int echoAmount = expectedAmount != null ? expectedAmount : 0;
        return Mono.just(PaymentVerificationResult.builder()
                .paymentId(paymentId)
                .transactionId("mock-tx-" + paymentId)
                .amount(echoAmount)
                .status(PaymentStatus.COMPLETED)
                .build());
    }
}
