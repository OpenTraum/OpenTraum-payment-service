package com.opentraum.payment.domain.entity;

public enum PaymentStatus {
    PENDING,      // 결제 대기
    COMPLETED,    // 결제 완료
    FAILED,       // PG 결제 실패 (카드 한도, 인증 실패)
    CANCELLED,    // 타임아웃으로 자동 취소
    REFUNDED      // 환불 완료
}
