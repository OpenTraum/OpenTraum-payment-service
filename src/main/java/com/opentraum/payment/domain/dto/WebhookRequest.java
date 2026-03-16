package com.opentraum.payment.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WebhookRequest {
    @JsonProperty("imp_uid")
    private String impUid;       // PortOne 결제 고유번호

    @JsonProperty("merchant_uid")
    private String merchantUid;  // 우리 시스템 주문번호
}
