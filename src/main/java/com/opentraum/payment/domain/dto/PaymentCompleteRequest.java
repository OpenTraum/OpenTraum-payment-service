package com.opentraum.payment.domain.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PaymentCompleteRequest {
    private String impUid;      // PortOne 결제 고유번호
    private String merchantUid; // 우리 시스템 주문번호
}
