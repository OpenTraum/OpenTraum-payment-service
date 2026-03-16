package com.opentraum.payment.domain.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Table("refunds")
public class Refund {

    @Id
    private Long id;

    @Column("payment_id")
    private Long paymentId;

    private BigDecimal amount;

    private String reason;

    private RefundStatus status;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    public enum RefundStatus {
        PENDING, COMPLETED, REJECTED
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public RefundStatus getStatus() { return status; }
    public void setStatus(RefundStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
