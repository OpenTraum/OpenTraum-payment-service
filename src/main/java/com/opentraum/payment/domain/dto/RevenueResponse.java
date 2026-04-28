package com.opentraum.payment.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class RevenueResponse {
    private String tenantId;
    private long totalAmount;
    private long completedCount;
}
