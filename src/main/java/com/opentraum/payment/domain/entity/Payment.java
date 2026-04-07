package com.opentraum.payment.domain.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    private Long id;

    private Long reservationId;

    private Long userId;

    private String merchantUid;

    private String impUid;

    private String pgTid;

    private Integer amount;

    private String method;

    private String status;

    private LocalDateTime expiresAt;

    private LocalDateTime paidAt;

    private Long tenantId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
