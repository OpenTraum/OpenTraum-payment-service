-- 결제 테이블 (FairTicket 마이그레이션 + tenantId 추가)
CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT NOT NULL,
    user_id BIGINT,
    merchant_uid VARCHAR(100) UNIQUE NOT NULL,
    imp_uid VARCHAR(100) UNIQUE,
    pg_tid VARCHAR(100),
    amount INT NOT NULL,
    method VARCHAR(50),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMP,
    paid_at TIMESTAMP,
    tenant_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_reservation_completed
    ON payments(reservation_id) WHERE status = 'COMPLETED';

CREATE INDEX IF NOT EXISTS idx_payments_reservation_id ON payments(reservation_id);
CREATE INDEX IF NOT EXISTS idx_payments_tenant_id ON payments(tenant_id);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments(status);
CREATE INDEX IF NOT EXISTS idx_payments_user_id ON payments(user_id);
CREATE INDEX IF NOT EXISTS idx_payments_merchant_uid ON payments(merchant_uid);
CREATE INDEX IF NOT EXISTS idx_payments_imp_uid ON payments(imp_uid);
