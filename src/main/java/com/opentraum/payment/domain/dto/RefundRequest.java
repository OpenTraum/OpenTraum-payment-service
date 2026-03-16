package com.opentraum.payment.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record RefundRequest(
        @NotNull Long paymentId,
        @NotNull @Positive BigDecimal amount,
        String reason
) {
}
