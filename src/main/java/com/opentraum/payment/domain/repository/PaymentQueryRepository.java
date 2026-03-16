package com.opentraum.payment.domain.repository;

import com.opentraum.payment.domain.entity.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

/**
 * R2DBC는 JOIN을 자동 지원하지 않으므로 DatabaseClient로 직접 쿼리
 * Payment -> Reservation (1:1) -> User
 */
@Repository
@RequiredArgsConstructor
public class PaymentQueryRepository {

    private final DatabaseClient databaseClient;

    // userId 기준 결제 목록 조회 (Payment JOIN Reservation)
    public Flux<Payment> findByUserId(Long userId) {
        return databaseClient.sql("""
                SELECT p.*
                FROM payments p
                JOIN reservations r ON p.reservation_id = r.id
                WHERE r.user_id = :userId
                ORDER BY p.created_at DESC
                """)
                .bind("userId", userId)
                .map((row, metadata) -> Payment.builder()
                        .id(row.get("id", Long.class))
                        .reservationId(row.get("reservation_id", Long.class))
                        .merchantUid(row.get("merchant_uid", String.class))
                        .impUid(row.get("imp_uid", String.class))
                        .amount(row.get("amount", Integer.class))
                        .status(row.get("status", String.class))
                        .tenantId(row.get("tenant_id", Long.class))
                        .paidAt(row.get("paid_at", LocalDateTime.class))
                        .createdAt(row.get("created_at", LocalDateTime.class))
                        .updatedAt(row.get("updated_at", LocalDateTime.class))
                        .build())
                .all();
    }
}
