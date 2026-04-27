package com.opentraum.payment.domain.service;

import com.opentraum.payment.domain.client.PortOneClient;
import com.opentraum.payment.domain.entity.Payment;
import com.opentraum.payment.domain.entity.PaymentStatus;
import com.opentraum.payment.domain.outbox.service.OutboxService;
import com.opentraum.payment.domain.repository.PaymentRepository;
import com.opentraum.payment.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 결제 SAGA 스텝 서비스.
 *
 * <p>SeatHeld(LIVE) / LotteryReservationCreated(LOTTERY) 트리거를 받아
 * PortOne 결제를 시도하고 결과를 Outbox로 발행한다. LIVE/LOTTERY 구분 없이
 * 여기서는 결제만 처리한다(좌석 배정은 event-service 책임).
 *
 * <p>트랜잭션 경계:
 * <ul>
 *   <li>PortOne 호출은 트랜잭션 밖 (장시간 I/O)</li>
 *   <li>payments 테이블 update + outbox insert는 {@link TransactionalOperator}로 묶어 atomic 보장</li>
 *   <li>PortOne 실패는 {@code onErrorResume}로 catch하여 PaymentFailed Outbox 기록 보장</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSagaService {

    private static final String AGGREGATE_TYPE = "payment";
    private static final String EVENT_PAYMENT_COMPLETED = "PaymentCompleted";
    private static final String EVENT_PAYMENT_FAILED = "PaymentFailed";

    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;
    private final OutboxService outboxService;
    private final TransactionalOperator transactionalOperator;

    public Mono<Void> process(
            Long reservationId,
            String sagaId,
            Long userId,
            Long scheduleId,
            Integer amount,
            String trackType,
            String impUid
    ) {
        log.info("[SAGA] payment start: reservationId={}, sagaId={}, track={}, amount={}",
                reservationId, sagaId, trackType, amount);

        // 1. PortOne 호출 (트랜잭션 밖)
        return portOneClient.verifyPayment(impUid)
                .flatMap(result -> onSuccess(reservationId, sagaId, userId, amount, trackType, impUid, result))
                .onErrorResume(err -> onFailure(reservationId, sagaId, userId, amount, impUid, err))
                .then();
    }

    private Mono<Void> onSuccess(
            Long reservationId,
            String sagaId,
            Long userId,
            Integer amount,
            String trackType,
            String impUid,
            PortOneClient.PaymentVerificationResult verification
    ) {
        Mono<Void> tx = upsertPayment(reservationId, userId, amount, impUid, PaymentStatus.COMPLETED)
                .flatMap(payment -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("payment_id", payment.getId());
                    payload.put("amount", amount);
                    payload.put("track_type", trackType);
                    return outboxService.publish(
                                    reservationId,
                                    AGGREGATE_TYPE,
                                    EVENT_PAYMENT_COMPLETED,
                                    sagaId,
                                    payload)
                            .thenReturn(payment);
                })
                .then();

        return transactionalOperator.transactional(tx)
                .doOnSuccess(v -> log.info(
                        "[SAGA] PaymentCompleted: reservationId={}, sagaId={}, transactionId={}",
                        reservationId, sagaId, verification.getTransactionId()))
                .onErrorResume(err -> {
                    // 트랜잭션(또는 outbox write) 자체가 실패한 상황 -> Failed 쪽으로도 시도
                    log.error("[SAGA] PaymentCompleted 기록 실패, PaymentFailed로 전환: reservationId={}, err={}",
                            reservationId, err.getMessage());
                    return onFailure(reservationId, sagaId, userId, amount, impUid, err);
                });
    }

    private Mono<Void> onFailure(
            Long reservationId,
            String sagaId,
            Long userId,
            Integer amount,
            String impUid,
            Throwable err
    ) {
        String reason = classifyFailure(err);
        log.warn("[SAGA] PaymentFailed: reservationId={}, sagaId={}, reason={}, err={}",
                reservationId, sagaId, reason, err.getMessage());

        Mono<Void> tx = upsertPayment(reservationId, userId, amount, impUid, PaymentStatus.FAILED)
                .flatMap(payment -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("payment_id", null);
                    payload.put("reason", reason);
                    return outboxService.publish(
                                    reservationId,
                                    AGGREGATE_TYPE,
                                    EVENT_PAYMENT_FAILED,
                                    sagaId,
                                    payload)
                            .thenReturn(payment);
                })
                .then();

        return transactionalOperator.transactional(tx)
                .onErrorResume(e -> {
                    log.error("[SAGA] PaymentFailed 기록 실패: reservationId={}, sagaId={}, err={}",
                            reservationId, sagaId, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /**
     * 예약에 해당하는 payments 레코드를 조회하고 상태를 갱신한다.
     * 없으면 새로 생성한다(SAGA 경로가 initiatePayment 없이 진입하는 경우 대비).
     */
    private Mono<Payment> upsertPayment(
            Long reservationId,
            Long userId,
            Integer amount,
            String impUid,
            PaymentStatus status
    ) {
        LocalDateTime now = LocalDateTime.now();
        return paymentRepository.findByReservationId(reservationId)
                .flatMap(existing -> {
                    existing.setStatus(status.name());
                    existing.setImpUid(impUid);
                    existing.setUpdatedAt(now);
                    if (status == PaymentStatus.COMPLETED) {
                        existing.setPaidAt(now);
                    }
                    return paymentRepository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    Payment fresh = Payment.builder()
                            .reservationId(reservationId)
                            .userId(userId)
                            .amount(amount)
                            .impUid(impUid)
                            .merchantUid(impUid != null ? impUid : "SAGA_" + reservationId)
                            .status(status.name())
                            .tenantId(0L)
                            .paidAt(status == PaymentStatus.COMPLETED ? now : null)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                    return paymentRepository.save(fresh);
                }));
    }

    private String classifyFailure(Throwable err) {
        if (err instanceof BusinessException be) {
            String msg = be.getMessage();
            if (msg != null) {
                if (msg.contains("CARD_DECLINED")) return "CARD_DECLINED";
                if (msg.contains("TIMEOUT")) return "TIMEOUT";
                if (msg.contains("INSUFFICIENT")) return "INSUFFICIENT_BALANCE";
            }
        }
        String cn = err.getClass().getSimpleName();
        if (cn.contains("Timeout")) return "TIMEOUT";
        if (cn.contains("Connect") || cn.contains("Network")) return "NETWORK_ERROR";
        return "NETWORK_ERROR";
    }
}
