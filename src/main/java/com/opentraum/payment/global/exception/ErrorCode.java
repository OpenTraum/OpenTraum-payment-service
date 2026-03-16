package com.opentraum.payment.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력입니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C002", "서버 오류가 발생했습니다"),

    // Payment
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "결제 정보를 찾을 수 없습니다"),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "P002", "결제 금액이 일치하지 않습니다"),
    PAYMENT_ALREADY_COMPLETED(HttpStatus.CONFLICT, "P003", "이미 완료된 결제입니다"),
    PAYMENT_TIMEOUT(HttpStatus.REQUEST_TIMEOUT, "P004", "결제 시간이 초과되었습니다"),
    REFUND_FAILED(HttpStatus.BAD_REQUEST, "P005", "환불 처리에 실패했습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
