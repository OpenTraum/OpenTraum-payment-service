package com.opentraum.payment.domain.outbox.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Consumer 멱등성 테이블.
 *
 * <p>Kafka Consumer가 이벤트를 처리하기 전 {@code event_id}로 조회해 중복 처리를 막는다.
 * PK 자체가 {@code event_id}이므로 insert 충돌로 exactly-once 경계를 형성한다.
 *
 * <p>PK가 애플리케이션 할당(String, non-generated)이므로 R2DBC가 save()를 UPDATE로
 * 오인하지 않도록 {@link Persistable}로 새 엔티티 여부를 명시한다.
 */
@Table("processed_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent implements Persistable<String> {

    @Id
    @Column("event_id")
    private String eventId;

    @Column("consumer_group")
    private String consumerGroup;

    @Column("processed_at")
    private LocalDateTime processedAt;

    @org.springframework.data.annotation.Transient
    @lombok.Builder.Default
    private boolean newEntity = true;

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }
}
