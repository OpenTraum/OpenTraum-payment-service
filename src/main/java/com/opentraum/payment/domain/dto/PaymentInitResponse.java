package com.opentraum.payment.domain.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentInitResponse {
    private String merchantUid;
    private Integer amount;
    private String itemName;
    private Long paymentId;
    private Integer timeoutSeconds; // 결제 제한 시간
}
