package com.opentraum.payment.domain.service;

import com.opentraum.payment.domain.constants.PaymentConstants;
import com.opentraum.payment.global.exception.BusinessException;
import com.opentraum.payment.global.exception.ErrorCode;
import com.opentraum.payment.global.util.RedisKeyGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Service
public class PaymentTimerService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @Autowired
    public PaymentTimerService(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // 결제 타이머 시작 (공통 5분)
    public Mono<Void> startPaymentTimer(Long reservationId) {
        String timerKey = RedisKeyGenerator.paymentTimerKey(reservationId);
        Duration ttl = Duration.ofMinutes(PaymentConstants.PAYMENT_DEADLINE_MINUTES);

        return redisTemplate.opsForValue()
                .set(timerKey, "PENDING", ttl)
                .doOnSuccess(v -> log.info("결제 타이머 시작: reservationId={}, ttl={}분",
                        reservationId, ttl.toMinutes()))
                .then();
    }

    // 결제 타이머 취소 (결제 완료 시)
    public Mono<Boolean> cancelPaymentTimer(Long reservationId) {
        String timerKey = RedisKeyGenerator.paymentTimerKey(reservationId);
        return redisTemplate.delete(timerKey)
                .map(deleted -> deleted > 0)
                .doOnSuccess(success -> log.info("결제 타이머 취소: reservationId={}", reservationId));
    }

    // 남은 시간 조회 (초 단위)
    // Redis 키가 없으면 -> 타이머 만료 또는 미시작 -> PAYMENT_TIMEOUT 에러
    public Mono<Long> getRemainingTime(Long reservationId) {
        String timerKey = RedisKeyGenerator.paymentTimerKey(reservationId);
        return redisTemplate.getExpire(timerKey)
                .flatMap(duration -> {
                    long seconds = duration.getSeconds();
                    // Redis 키 없음: -1(키 없음) 또는 -2(만료됨) 반환
                    if (seconds < 0) {
                        return Mono.error(new BusinessException(ErrorCode.PAYMENT_TIMEOUT));
                    }
                    return Mono.just(seconds);
                });
    }
}
