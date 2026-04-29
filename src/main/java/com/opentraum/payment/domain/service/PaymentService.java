package com.opentraum.payment.domain.service;

import com.opentraum.payment.domain.client.PortOneClient;
import com.opentraum.payment.domain.constants.PaymentConstants;
import com.opentraum.payment.domain.dto.PaymentInitResponse;
import com.opentraum.payment.domain.dto.WebhookRequest;
import com.opentraum.payment.domain.entity.Payment;
import com.opentraum.payment.domain.entity.PaymentStatus;
import com.opentraum.payment.domain.outbox.service.OutboxService;
import com.opentraum.payment.domain.repository.PaymentRepository;
import com.opentraum.payment.domain.repository.PaymentQueryRepository;
import com.opentraum.payment.global.exception.BusinessException;
import com.opentraum.payment.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class PaymentService {

    private static final String AGGREGATE_TYPE = "payment";
    private static final String EVENT_PAYMENT_COMPLETED = "PaymentCompleted";
    private static final String EVENT_PAYMENT_FAILED = "PaymentFailed";
    private static final String EVENT_REFUND_COMPLETED = "RefundCompleted";
    private static final String EVENT_REFUND_FAILED = "RefundFailed";

    private final PaymentRepository paymentRepository;
    private final PaymentQueryRepository paymentQueryRepository;
    private final PortOneClient portOneClient;
    private final PaymentTimerService timerService;
    private final WebClient reservationWebClient;
    private final WebClient eventWebClient;
    private final OutboxService outboxService;
    private final TransactionalOperator transactionalOperator;
    private final long paymentDelayMs;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentQueryRepository paymentQueryRepository,
                          PortOneClient portOneClient,
                          PaymentTimerService timerService,
                          @Qualifier("reservationWebClient") WebClient reservationWebClient,
                          @Qualifier("eventWebClient") WebClient eventWebClient,
                          OutboxService outboxService,
                          TransactionalOperator transactionalOperator,
                          @Value("${opentraum.payment.delay-ms:2000}") long paymentDelayMs) {
        this.paymentRepository = paymentRepository;
        this.paymentQueryRepository = paymentQueryRepository;
        this.portOneClient = portOneClient;
        this.timerService = timerService;
        this.reservationWebClient = reservationWebClient;
        this.eventWebClient = eventWebClient;
        this.outboxService = outboxService;
        this.transactionalOperator = transactionalOperator;
        this.paymentDelayMs = paymentDelayMs;
    }

    // 결제 준비 (결제창 호출 전) — 주문/이벤트 데이터 복제 + 지연
    public Mono<PaymentInitResponse> initiatePayment(Long reservationId, Integer amount,
                                                      String itemName, String tenantId, Long userId) {
        String merchantUid = generateMerchantUid();
        long startTime = System.currentTimeMillis();

        Payment payment = Payment.builder()
                .reservationId(reservationId)
                .merchantUid(merchantUid)
                .amount(amount)
                .tenantId(tenantId)
                .userId(userId)
                .status(PaymentStatus.PENDING.name())
                .createdAt(LocalDateTime.now())
                .build();

        // 주문/이벤트 데이터 복제 조회 (best-effort)
        Mono<Payment> enriched = enrichWithReservationData(payment, reservationId)
                .onErrorResume(e -> {
                    log.warn("주문 데이터 복제 실패 (무시): {}", e.getMessage());
                    return Mono.just(payment);
                });

        return enriched
                // 결제 처리 지연 (HPA Scale Out 유도)
                .delayElement(Duration.ofMillis(paymentDelayMs))
                .flatMap(p -> {
                    p.setElapsedMs(System.currentTimeMillis() - startTime);
                    return paymentRepository.save(p);
                })
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

    // 주문(reservation) 데이터를 payment에 복제
    private Mono<Payment> enrichWithReservationData(Payment payment, Long reservationId) {
        return reservationWebClient.get()
                .uri("/api/v1/reservations/{id}", reservationId)
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(reservation -> {
                    payment.setReservationStatus((String) reservation.get("status"));
                    payment.setReservationQuantity((Integer) reservation.get("quantity"));
                    payment.setReservationGrade((String) reservation.get("grade"));

                    // 이벤트(스케줄→콘서트) 데이터도 복제 시도
                    Object scheduleId = reservation.get("scheduleId");
                    if (scheduleId != null) {
                        return enrichWithEventData(payment, Long.valueOf(scheduleId.toString()));
                    }
                    return Mono.just(payment);
                })
                .defaultIfEmpty(payment);
    }

    // 이벤트(product) 데이터를 payment에 복제
    private Mono<Payment> enrichWithEventData(Payment payment, Long scheduleId) {
        return eventWebClient.get()
                .uri("/api/v1/schedules/{id}", scheduleId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(schedule -> {
                    payment.setEventTitle((String) schedule.get("concertTitle"));
                    payment.setEventArtist((String) schedule.get("artist"));
                    payment.setEventVenue((String) schedule.get("venue"));
                    return payment;
                })
                .defaultIfEmpty(payment)
                .onErrorResume(e -> {
                    log.warn("이벤트 데이터 복제 실패 (무시): {}", e.getMessage());
                    return Mono.just(payment);
                });
    }

    // 결제 완료 처리 - PortOne Webhook 수신
    public Mono<Payment> completePayment(WebhookRequest request) {
        return paymentRepository.findByMerchantUid(request.getMerchantUid())
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND)))
                .flatMap(payment -> {
                    if (PaymentStatus.COMPLETED.name().equals(payment.getStatus())) {
                        return Mono.error(new BusinessException(ErrorCode.PAYMENT_ALREADY_COMPLETED));
                    }
                    return portOneClient.verifyPayment(request.getMerchantUid(), payment.getAmount())
                            .doOnError(err -> log.warn("결제 검증 API 호출 실패: merchantUid={}, err={}",
                                    request.getMerchantUid(), err.getMessage()))
                            .flatMap(verification -> {
                                if (!payment.getAmount().equals(verification.getAmount())) {
                                    log.error("결제 금액 불일치: expected={}, actual={}",
                                            payment.getAmount(), verification.getAmount());
                                    BusinessException error = new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
                                    return persistFailedWithOutbox(payment, "PAYMENT_AMOUNT_MISMATCH")
                                            .then(Mono.<Payment>error(error));
                                }
                                if (PaymentStatus.FAILED.equals(verification.getStatus())) {
                                    BusinessException error = new BusinessException(
                                            ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                                            "PortOne 결제 상태가 실패입니다.");
                                    return persistFailedWithOutbox(payment, "PORTONE_PAYMENT_FAILED")
                                            .then(Mono.<Payment>error(error));
                                }
                                if (!PaymentStatus.COMPLETED.equals(verification.getStatus())) {
                                    return Mono.error(new BusinessException(
                                            ErrorCode.INTERNAL_ERROR,
                                            "PortOne 결제가 아직 완료되지 않았습니다."));
                                }
                                payment.setImpUid(request.getImpUid());
                                payment.setStatus(PaymentStatus.COMPLETED.name());
                                payment.setPaidAt(LocalDateTime.now());
                                payment.setUpdatedAt(LocalDateTime.now());
                                return persistCompletedWithOutbox(payment);
                            });
                })
                .flatMap(payment -> timerService.cancelPaymentTimer(payment.getReservationId())
                        .thenReturn(payment))
                .doOnSuccess(payment -> log.info("결제 완료: paymentId={}, merchantUid={}",
                        payment.getId(), payment.getMerchantUid()));
    }

    // 환불 처리
    public Mono<PortOneClient.RefundResult> refundPayment(Long paymentId, String reason) {
        return paymentRepository.findById(paymentId)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND)))
                .flatMap(payment -> {
                    if (PaymentStatus.REFUNDED.name().equals(payment.getStatus())) {
                        return Mono.just(PortOneClient.RefundResult.builder()
                                .success(true)
                                .message("ALREADY_REFUNDED")
                                .build());
                    }
                    return portOneClient.cancelPayment(
                                    payment.getMerchantUid(),
                                    payment.getAmount(),
                                    reason
                            )
                            .flatMap(result -> {
                                if (result.isSuccess()) {
                                    payment.setStatus(PaymentStatus.REFUNDED.name());
                                    payment.setUpdatedAt(LocalDateTime.now());
                                    return persistRefundCompletedWithOutbox(payment, reason)
                                            .thenReturn(result);
                                }
                                return publishRefundFailed(payment, result.getMessage())
                                        .thenReturn(result);
                            })
                            .onErrorResume(err -> publishRefundFailed(payment, err.getMessage())
                                    .then(Mono.<PortOneClient.RefundResult>error(err)));
                });
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

    private Mono<Payment> persistCompletedWithOutbox(Payment payment) {
        String sagaId = UUID.randomUUID().toString();
        Mono<Payment> tx = paymentRepository.save(payment)
                .flatMap(saved -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("payment_id", saved.getId());
                    payload.put("amount", saved.getAmount());
                    return outboxService.publish(
                                    saved.getReservationId(),
                                    AGGREGATE_TYPE,
                                    EVENT_PAYMENT_COMPLETED,
                                    sagaId,
                                    payload)
                            .thenReturn(saved);
                });
        return transactionalOperator.transactional(tx);
    }

    private Mono<Payment> persistFailedWithOutbox(Payment payment, String reason) {
        String sagaId = UUID.randomUUID().toString();
        payment.setStatus(PaymentStatus.FAILED.name());
        payment.setUpdatedAt(LocalDateTime.now());
        Mono<Payment> tx = paymentRepository.save(payment)
                .flatMap(saved -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("payment_id", saved.getId());
                    payload.put("amount", saved.getAmount());
                    payload.put("reason", reason == null ? "PAYMENT_FAILED" : reason);
                    return outboxService.publish(
                                    saved.getReservationId(),
                                    AGGREGATE_TYPE,
                                    EVENT_PAYMENT_FAILED,
                                    sagaId,
                                    payload)
                            .thenReturn(saved);
                });
        return transactionalOperator.transactional(tx);
    }

    private Mono<Payment> persistRefundCompletedWithOutbox(Payment payment, String reason) {
        String sagaId = UUID.randomUUID().toString();
        Mono<Payment> tx = paymentRepository.save(payment)
                .flatMap(saved -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("payment_id", saved.getId());
                    payload.put("amount", saved.getAmount());
                    payload.put("reason", reason);
                    return outboxService.publish(
                                    saved.getReservationId(),
                                    AGGREGATE_TYPE,
                                    EVENT_REFUND_COMPLETED,
                                    sagaId,
                                    payload)
                            .thenReturn(saved);
                });
        return transactionalOperator.transactional(tx);
    }

    private Mono<Void> publishRefundFailed(Payment payment, String reason) {
        String sagaId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_id", payment.getId());
        payload.put("amount", payment.getAmount());
        payload.put("reason", reason == null ? "REFUND_FAILED" : reason);
        return outboxService.publish(
                        payment.getReservationId(),
                        AGGREGATE_TYPE,
                        EVENT_REFUND_FAILED,
                        sagaId,
                        payload)
                .then();
    }
}
