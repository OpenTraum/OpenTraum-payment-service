package com.opentraum.payment.domain.service;

import com.opentraum.payment.domain.client.PortOneClient;
import com.opentraum.payment.domain.entity.Payment;
import com.opentraum.payment.domain.entity.PaymentStatus;
import com.opentraum.payment.domain.outbox.service.OutboxService;
import com.opentraum.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * PENDING 상태로 5분 초과된 결제를 PortOne 에 재검증해 상태 불일치를 복구한다.
 *
 * <p>복구 시 DB 상태만 바꾸면 reservation-service 가 결제 완료/실패를 알 수 없어
 * SAGA 흐름이 끊긴다. 따라서 상태 전이와 함께 outbox {@code PaymentCompleted} /
 * {@code PaymentFailed} 를 발행해 하위 SAGA 로 전파한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentVerificationScheduler {

    private static final String AGGREGATE_TYPE = "payment";
    private static final String EVENT_PAYMENT_COMPLETED = "PaymentCompleted";
    private static final String EVENT_PAYMENT_FAILED = "PaymentFailed";

    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;
    private final OutboxService outboxService;
    private final TransactionalOperator transactionalOperator;

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void verifyPendingPayments() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);

            paymentRepository.findByStatus(PaymentStatus.PENDING.name())
                    .filter(payment -> payment.getCreatedAt() != null
                            && payment.getCreatedAt().isBefore(threshold))
                    .filter(payment -> payment.getMerchantUid() != null)
                    .flatMap(payment -> portOneClient.verifyPayment(payment.getMerchantUid())
                            .flatMap(verification -> {
                                switch (verification.getStatus()) {
                                    case COMPLETED -> {
                                        log.info("결제 불일치 복구 - 완료 처리: paymentId={}", payment.getId());
                                        return persistCompletedWithOutbox(payment);
                                    }
                                    case FAILED -> {
                                        log.warn("결제 불일치 복구 - 실패 처리: paymentId={}", payment.getId());
                                        return persistFailedWithOutbox(payment, "PORTONE_VERIFY_FAILED");
                                    }
                                    default -> {
                                        log.debug("결제 검증 대기 중: paymentId={}, status={}",
                                                payment.getId(), verification.getStatus());
                                        return Mono.just(payment);
                                    }
                                }
                            })
                            .onErrorResume(e -> {
                                log.warn("결제 검증 API 호출 실패: paymentId={}, error={}",
                                        payment.getId(), e.getMessage());
                                return Mono.just(payment);
                            }))
                    .count()
                    .doOnSuccess(count -> {
                        if (count > 0) {
                            log.info("결제 불일치 복구 처리: {}건", count);
                        }
                    })
                    .block(Duration.ofSeconds(30));

        } catch (Exception e) {
            log.error("결제 불일치 복구 스케줄러 오류", e);
        }
    }

    private Mono<Payment> persistCompletedWithOutbox(Payment payment) {
        LocalDateTime now = LocalDateTime.now();
        payment.setStatus(PaymentStatus.COMPLETED.name());
        payment.setPaidAt(now);
        payment.setUpdatedAt(now);
        // payments 테이블에 saga_id 컬럼이 없어 원 SAGA 상관키는 추적 불가. 복구 이벤트는 신규
        // sagaId 로 발행되고, 하위 소비자는 reservation_id 기준으로 매핑한다.
        String sagaId = UUID.randomUUID().toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_id", payment.getId());
        payload.put("reservation_id", payment.getReservationId());
        payload.put("amount", payment.getAmount());

        Mono<Payment> tx = paymentRepository.save(payment)
                .flatMap(saved -> outboxService.publish(
                                saved.getReservationId(), AGGREGATE_TYPE,
                                EVENT_PAYMENT_COMPLETED, sagaId, payload)
                        .thenReturn(saved));
        return transactionalOperator.transactional(tx);
    }

    private Mono<Payment> persistFailedWithOutbox(Payment payment, String reason) {
        LocalDateTime now = LocalDateTime.now();
        payment.setStatus(PaymentStatus.FAILED.name());
        payment.setUpdatedAt(now);
        // payments 테이블에 saga_id 컬럼이 없어 원 SAGA 상관키는 추적 불가. 복구 이벤트는 신규
        // sagaId 로 발행되고, 하위 소비자는 reservation_id 기준으로 매핑한다.
        String sagaId = UUID.randomUUID().toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_id", payment.getId());
        payload.put("reservation_id", payment.getReservationId());
        payload.put("reason", reason);

        Mono<Payment> tx = paymentRepository.save(payment)
                .flatMap(saved -> outboxService.publish(
                                saved.getReservationId(), AGGREGATE_TYPE,
                                EVENT_PAYMENT_FAILED, sagaId, payload)
                        .thenReturn(saved));
        return transactionalOperator.transactional(tx);
    }
}
