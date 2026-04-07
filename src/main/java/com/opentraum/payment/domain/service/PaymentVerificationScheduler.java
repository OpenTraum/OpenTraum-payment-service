package com.opentraum.payment.domain.service;

import com.opentraum.payment.domain.entity.PaymentStatus;
import com.opentraum.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentVerificationScheduler {

    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;

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
                                        log.info("결제 불일치 복구 - 완료 처리: paymentId={}",
                                                payment.getId());
                                        payment.setStatus(PaymentStatus.COMPLETED.name());
                                        payment.setPaidAt(LocalDateTime.now());
                                        payment.setUpdatedAt(LocalDateTime.now());
                                        return paymentRepository.save(payment);
                                    }
                                    case FAILED -> {
                                        log.warn("결제 불일치 복구 - 실패 처리: paymentId={}",
                                                payment.getId());
                                        payment.setStatus(PaymentStatus.FAILED.name());
                                        payment.setUpdatedAt(LocalDateTime.now());
                                        return paymentRepository.save(payment);
                                    }
                                    default -> {
                                        log.debug("결제 검증 대기 중: paymentId={}, status={}",
                                                payment.getId(), verification.getStatus());
                                        return reactor.core.publisher.Mono.just(payment);
                                    }
                                }
                            })
                            .onErrorResume(e -> {
                                log.warn("결제 검증 API 호출 실패: paymentId={}, error={}",
                                        payment.getId(), e.getMessage());
                                return reactor.core.publisher.Mono.just(payment);
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
}
