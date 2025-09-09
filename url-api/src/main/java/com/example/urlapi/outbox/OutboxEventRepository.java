package com.example.urlapi.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for outbox events supporting CDC and fallback polling patterns.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    
    /**
     * Find pending events for fallback polling (if CDC fails).
     * Orders by creation time to maintain event ordering.
     */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status);
    
    /**
     * Find pending events with limit for batch processing.
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status ORDER BY e.createdAt ASC")
    List<OutboxEvent> findPendingEvents(@Param("status") OutboxEventStatus status, 
                                        org.springframework.data.domain.Pageable pageable);
    
    /**
     * Find events by aggregate for debugging/monitoring.
     */
    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
        String aggregateType, String aggregateId);
    
    /**
     * Find events by trace ID for debugging distributed traces.
     */
    List<OutboxEvent> findByTraceIdOrderByCreatedAtAsc(String traceId);
    
    /**
     * Delete processed events older than specified time.
     * Used by cleanup job to prevent unbounded table growth.
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = 'PROCESSED' AND e.processedAt < :cutoffTime")
    int deleteProcessedEventsBefore(@Param("cutoffTime") Instant cutoffTime);
    
    /**
     * Count events by status for monitoring.
     */
    long countByStatus(OutboxEventStatus status);
    
    /**
     * Find failed events that should be retried.
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'FAILED' AND e.retryCount < :maxRetries")
    List<OutboxEvent> findRetryableEvents(@Param("maxRetries") int maxRetries);
    
    /**
     * Update events to processed status in batch (for testing/admin).
     */
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = 'PROCESSED', e.processedAt = :processedAt " +
           "WHERE e.id IN :ids")
    int markAsProcessed(@Param("ids") List<UUID> ids, @Param("processedAt") Instant processedAt);
    
    /**
     * Get statistics for monitoring dashboard.
     */
    @Query("SELECT e.status, COUNT(e) FROM OutboxEvent e GROUP BY e.status")
    List<Object[]> getEventStatistics();
    
    /**
     * Find the oldest pending event for lag monitoring.
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'PENDING' ORDER BY e.createdAt ASC")
    List<OutboxEvent> findOldestPendingEvent(org.springframework.data.domain.Pageable pageable);
}