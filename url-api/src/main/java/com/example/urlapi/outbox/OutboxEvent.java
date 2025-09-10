package com.example.urlapi.outbox;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing an event in the transactional outbox pattern.
 * Events are written atomically with business operations and then
 * captured by Debezium CDC for publishing to Kafka.
 */
@Entity
@Table(name = "outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "payload")
public class OutboxEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;
    
    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;
    
    @Column(name = "event_type", nullable = false)
    private String eventType;
    
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String payload;
    
    // W3C Trace Context fields for distributed tracing
    @Column(name = "trace_id", length = 32)
    private String traceId;
    
    @Column(name = "parent_span_id", length = 16)
    private String parentSpanId;
    
    @Column(name = "trace_flags", length = 2)
    private String traceFlags;
    
    @Column(name = "trace_state", columnDefinition = "TEXT")
    private String traceState;
    
    // Metadata
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;
    
    @Column(name = "created_by")
    private String createdBy;
    
    @Column(name = "tenant_id")
    private String tenantId;
    
    // Standard context as JSON (as per desktop review decision)
    @Column(name = "context", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String context;
    
    // Processing status (for fallback polling if CDC fails)
    @Column(name = "processed_at")
    private Instant processedAt;
    
    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OutboxEventStatus status = OutboxEventStatus.PENDING;
    
    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    /**
     * Pre-persist validation
     */
    @PrePersist
    public void prePersist() {
        if (this.status == null) {
            this.status = OutboxEventStatus.PENDING;
        }
        if (this.retryCount == null) {
            this.retryCount = 0;
        }
        // Ensure trace flags default to sampled if trace ID exists
        if (this.traceId != null && this.traceFlags == null) {
            this.traceFlags = "01"; // Sampled
        }
    }
    
    /**
     * Mark event as processed
     */
    public void markAsProcessed() {
        this.status = OutboxEventStatus.PROCESSED;
        this.processedAt = Instant.now();
    }
    
    /**
     * Mark event as failed
     */
    public void markAsFailed(String errorMessage) {
        this.status = OutboxEventStatus.FAILED;
        this.errorMessage = errorMessage;
        this.retryCount++;
    }
    
    /**
     * Check if event should be retried
     */
    public boolean shouldRetry(int maxRetries) {
        return this.status == OutboxEventStatus.FAILED && this.retryCount < maxRetries;
    }
}