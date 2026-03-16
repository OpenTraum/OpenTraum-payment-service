package com.opentraum.payment.domain.service;

import com.opentraum.payment.domain.constants.PaymentConstants;
import com.opentraum.payment.domain.entity.PaymentStatus;
import com.opentraum.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 결제 준비(prepare) 후 결제창 미진입(impUid=null)으로 5분 경과한 Payment를 CANCELLED 처리.
 * PaymentVerificationScheduler는 impUid가 있는 결제만 PortOne 검증하므로,
 * impUid=null인 결제는 이 스케줄러가 정리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentExpiryScheduler {

    private final PaymentRepository paymentRepository;

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void cancelExpiredUnpaidPayments() {
        try {
            LocalDateTime threshold = LocalDateTime.now()
                    .minusMinutes(PaymentConstants.PAYMENT_DEADLINE_MINUTES);

            paymentRepository.findByStatus(PaymentStatus.PENDING.name())
                    .filter(payment -> payment.getCreatedAt() != null
                            && payment.getCreatedAt().isBefore(threshold))
                    .filter(payment -> payment.getImpUid() == null)
                    .flatMap(payment -> {
                        payment.setStatus(PaymentStatus.CANCELLED.name());
                        payment.setUpdatedAt(LocalDateTime.now());
                        return paymentRepository.save(payment)
                                .doOnSuccess(v -> log.info("미결제 자동 취소: paymentId={}, reservationId={}",
                                        payment.getId(), payment.getReservationId()))
                                .thenReturn(payment);
                    })
                    .count()
                    .doOnSuccess(count -> {
                        if (count > 0) {
                            log.info("미결제 만료 처리: {}건", count);
                        }
                    })
                    .block(Duration.ofSeconds(30));

        } catch (Exception e) {
            log.error("미결제 만료 스케줄러 오류", e);
        }
    }
}
