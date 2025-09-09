package com.example.urlapi.outbox;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for creating outbox events with automatic trace context capture.
 */
@Service
public class OutboxEventService {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxEventService.class);
    
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    
    /**
     * Create an outbox event for a domain aggregate.
     * Automatically captures current trace context for propagation.
     */
    @Transactional
    public OutboxEvent createEvent(String aggregateId, 
                                   String aggregateType,
                                   String eventType,
                                   Object payload) {
        
        OutboxEvent.OutboxEventBuilder builder = OutboxEvent.builder()
            .aggregateId(aggregateId)
            .aggregateType(aggregateType)
            .eventType(eventType)
            .payload(payload);
        
        // Capture current trace context if available
        Span currentSpan = Span.current();
        if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
            SpanContext spanContext = currentSpan.getSpanContext();
            builder.traceId(spanContext.getTraceId())
                   .parentSpanId(spanContext.getSpanId())
                   .traceFlags(spanContext.getTraceFlags().asHex());
            
            logger.debug("Captured trace context - TraceId: {}, SpanId: {}", 
                spanContext.getTraceId(), spanContext.getSpanId());
        }
        
        // Capture MDC context
        String userId = MDC.get("userId");
        if (userId != null) {
            builder.createdBy(userId);
        }
        
        String tenantId = MDC.get("tenantId");
        if (tenantId != null) {
            builder.tenantId(tenantId);
        }
        
        OutboxEvent event = builder.build();
        OutboxEvent savedEvent = outboxEventRepository.save(event);
        
        logger.info("Created outbox event - Type: {}, AggregateId: {}, EventId: {}", 
            eventType, aggregateId, savedEvent.getId());
        
        return savedEvent;
    }
    
    /**
     * Create a link creation event.
     */
    @Transactional
    public OutboxEvent createLinkCreatedEvent(String shortCode, String originalUrl, String createdBy) {
        Map<String, Object> payload = Map.of(
            "shortCode", shortCode,
            "originalUrl", originalUrl,
            "createdBy", createdBy != null ? createdBy : "anonymous",
            "timestamp", System.currentTimeMillis()
        );
        
        return createEvent(shortCode, "Link", "LINK_CREATED", payload);
    }
    
    /**
     * Create a link expiration event.
     */
    @Transactional
    public OutboxEvent createLinkExpiredEvent(String shortCode, String reason) {
        Map<String, Object> payload = Map.of(
            "shortCode", shortCode,
            "reason", reason,
            "timestamp", System.currentTimeMillis()
        );
        
        return createEvent(shortCode, "Link", "LINK_EXPIRED", payload);
    }
    
    /**
     * Create a link click event (might bypass outbox for performance).
     */
    @Transactional
    public OutboxEvent createLinkClickedEvent(String shortCode, String userAgent, String ipAddress) {
        Map<String, Object> payload = Map.of(
            "shortCode", shortCode,
            "userAgent", userAgent != null ? userAgent : "unknown",
            "ipAddress", ipAddress != null ? ipAddress : "unknown",
            "timestamp", System.currentTimeMillis()
        );
        
        return createEvent(shortCode, "Link", "LINK_CLICKED", payload);
    }
    
    /**
     * Get outbox statistics for monitoring.
     */
    public OutboxStatistics getStatistics() {
        long pending = outboxEventRepository.countByStatus(OutboxEventStatus.PENDING);
        long processed = outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED);
        long failed = outboxEventRepository.countByStatus(OutboxEventStatus.FAILED);
        
        return new OutboxStatistics(pending, processed, failed);
    }
    
    /**
     * Simple statistics DTO.
     */
    public record OutboxStatistics(long pending, long processed, long failed) {
        public long total() {
            return pending + processed + failed;
        }
    }
}