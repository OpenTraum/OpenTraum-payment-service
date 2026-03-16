package com.opentraum.payment.domain.dto;

import com.opentraum.payment.domain.entity.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponse(
        Long id,
        Long reservationId,
        Long userId,
        Long tenantId,
        BigDecimal amount,
        String currency,
        Payment.PaymentMethod method,
        Payment.PaymentStatus status,
        String pgTransactionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getReservationId(),
                payment.getUserId(),
                payment.getTenantId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getMethod(),
                payment.getStatus(),
                payment.getPgTransactionId(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
