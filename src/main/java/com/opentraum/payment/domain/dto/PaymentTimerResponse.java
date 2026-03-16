package com.opentraum.payment.domain.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentTimerResponse {

    private Long reservationId;
    private Long remainingSeconds;
    private boolean expired;
}
