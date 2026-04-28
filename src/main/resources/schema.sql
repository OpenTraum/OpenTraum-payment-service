-- 결제 테이블 (FairTicket 마이그레이션 + tenantId 추가 + CDC 중복 데이터)
CREATE TABLE IF NOT EXISTS payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    -- 주문(reservation) 데이터 복제 (CDC 대비)
    reservation_status VARCHAR(20),
    reservation_quantity INT,
    reservation_grade VARCHAR(50),
    -- 이벤트(product) 데이터 복제 (CDC 대비)
    event_title VARCHAR(255),
    event_artist VARCHAR(255),
    event_venue VARCHAR(255),
    -- 처리 시간 추적
    elapsed_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payments_reservation_completed
    ON payments(reservation_id);

CREATE INDEX IF NOT EXISTS idx_payments_reservation_id ON payments(reservation_id);
CREATE INDEX IF NOT EXISTS idx_payments_tenant_id ON payments(tenant_id);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments(status);
CREATE INDEX IF NOT EXISTS idx_payments_user_id ON payments(user_id);
CREATE INDEX IF NOT EXISTS idx_payments_merchant_uid ON payments(merchant_uid);
CREATE INDEX IF NOT EXISTS idx_payments_imp_uid ON payments(imp_uid);

-- outbox_events: Debezium이 감시해 Kafka로 발행
CREATE TABLE IF NOT EXISTS outbox_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) NOT NULL UNIQUE,
    aggregate_id BIGINT NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    saga_id CHAR(36) NOT NULL,
    trace_id CHAR(32),
    payload JSON NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id),
    INDEX idx_outbox_saga (saga_id),
    INDEX idx_outbox_occurred (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- processed_events: Consumer 멱등성
CREATE TABLE IF NOT EXISTS processed_events (
    event_id CHAR(36) PRIMARY KEY,
    consumer_group VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
