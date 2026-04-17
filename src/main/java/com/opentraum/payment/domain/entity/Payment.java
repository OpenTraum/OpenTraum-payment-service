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

    // 주문(reservation) 데이터 복제 (CDC 대비)
    private String reservationStatus;

    private Integer reservationQuantity;

    private String reservationGrade;

    // 이벤트(product) 데이터 복제 (CDC 대비)
    private String eventTitle;

    private String eventArtist;

    private String eventVenue;

    // 처리 시간 추적
    private Long elapsedMs;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
