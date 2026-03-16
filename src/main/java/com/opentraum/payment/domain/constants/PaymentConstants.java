package com.opentraum.payment.domain.constants;

/**
 * 결제 관련 공통 상수
 */
public final class PaymentConstants {

    private PaymentConstants() {
        throw new UnsupportedOperationException("상수 클래스는 인스턴스화할 수 없습니다.");
    }

    // 결제 유효 시간 (분). 미결제 시 자동 취소
    public static final int PAYMENT_DEADLINE_MINUTES = 5;
}
