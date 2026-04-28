package com.opentraum.payment.domain.repository;

import com.opentraum.payment.domain.entity.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

@Repository
@RequiredArgsConstructor
public class PaymentQueryRepository {

    private final DatabaseClient databaseClient;

    /**
     * ORGANIZER 매출 합산 (status=COMPLETED 인 payments의 amount 총합).
     * 환불(REFUNDED)은 제외한다.
     */
    public reactor.core.publisher.Mono<Long> sumCompletedAmountByTenantId(String tenantId) {
        return databaseClient.sql("""
                SELECT COALESCE(SUM(amount), 0) AS total
                FROM payments
                WHERE tenant_id = :tenantId AND status = 'COMPLETED'
                """)
                .bind("tenantId", tenantId)
                .map((row, metadata) -> row.get("total", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    /**
     * ORGANIZER 매출 건수 (status=COMPLETED).
     */
    public reactor.core.publisher.Mono<Long> countCompletedByTenantId(String tenantId) {
        return databaseClient.sql("""
                SELECT COUNT(*) AS cnt
                FROM payments
                WHERE tenant_id = :tenantId AND status = 'COMPLETED'
                """)
                .bind("tenantId", tenantId)
                .map((row, metadata) -> row.get("cnt", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    public Flux<Payment> findByUserId(Long userId) {
        return databaseClient.sql("""
                SELECT * FROM payments
                WHERE user_id = :userId
                ORDER BY created_at DESC
                """)
                .bind("userId", userId)
                .map((row, metadata) -> Payment.builder()
                        .id(row.get("id", Long.class))
                        .reservationId(row.get("reservation_id", Long.class))
                        .userId(row.get("user_id", Long.class))
                        .merchantUid(row.get("merchant_uid", String.class))
                        .impUid(row.get("imp_uid", String.class))
                        .amount(row.get("amount", Integer.class))
                        .status(row.get("status", String.class))
                        .tenantId(row.get("tenant_id", String.class))
                        .paidAt(row.get("paid_at", LocalDateTime.class))
                        .createdAt(row.get("created_at", LocalDateTime.class))
                        .updatedAt(row.get("updated_at", LocalDateTime.class))
                        .build())
                .all();
    }
}
