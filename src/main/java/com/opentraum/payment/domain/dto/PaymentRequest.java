package com.opentraum.payment.domain.dto;

import com.opentraum.payment.domain.entity.Payment;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotNull Long reservationId,
        @NotNull Long userId,
        @NotNull Long tenantId,
        @NotNull @Positive BigDecimal amount,
        String currency,
        @NotNull Payment.PaymentMethod method
) {
    public PaymentRequest {
        if (currency == null || currency.isBlank()) {
            currency = "KRW";
        }
    }
}
