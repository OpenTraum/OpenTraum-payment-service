package com.opentraum.payment.global.util;

/**
 * Redis 키 생성을 위한 유틸리티 클래스 (결제 서비스 전용)
 */
public class RedisKeyGenerator {

    private RedisKeyGenerator() {
        throw new UnsupportedOperationException("유틸리티 클래스는 인스턴스화할 수 없습니다.");
    }

    // 미결제 자동 취소 타이머 키 (String+TTL) - payment-timer:{reservationId}
    public static String paymentTimerKey(Long reservationId) {
        return String.format("payment-timer:%d", reservationId);
    }

    // 등급별 잔여 재고 카운터 키 (String, atomic INCR/DECR 전용)
    // stock:{scheduleId}:{grade}
    public static String stockKey(Long scheduleId, String grade) {
        return String.format("stock:%d:%s", scheduleId, grade);
    }
}
