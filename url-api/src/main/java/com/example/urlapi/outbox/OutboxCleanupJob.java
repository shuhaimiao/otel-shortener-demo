package com.example.urlapi.outbox;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Scheduled job to clean up processed outbox events.
 * Prevents unbounded table growth by removing old processed events.
 */
@Component
public class OutboxCleanupJob {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxCleanupJob.class);
    
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    
    @Autowired(required = false)
    private Tracer tracer;
    
    @Value("${outbox.cleanup.retention-days:7}")
    private int retentionDays;
    
    @Value("${outbox.cleanup.enabled:true}")
    private boolean cleanupEnabled;
    
    /**
     * Run cleanup daily at 2 AM.
     * For demo purposes, you might want to reduce this to run more frequently.
     */
    @Scheduled(cron = "${outbox.cleanup.cron:0 0 2 * * *}")
    @Transactional
    public void cleanupProcessedEvents() {
        if (!cleanupEnabled) {
            logger.debug("Outbox cleanup is disabled");
            return;
        }
        
        Span span = null;
        Scope scope = null;
        
        try {
            // Start a span for monitoring
            if (tracer != null) {
                span = tracer.spanBuilder("outbox.cleanup")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("job.name", "outbox-cleanup")
                    .setAttribute("retention.days", retentionDays)
                    .startSpan();
                scope = span.makeCurrent();
            }
            
            // Set MDC for logging
            MDC.put("jobName", "outbox-cleanup");
            if (span != null) {
                MDC.put("traceId", span.getSpanContext().getTraceId());
            }
            
            // Calculate cutoff time
            Instant cutoffTime = Instant.now().minus(Duration.ofDays(retentionDays));
            logger.info("Starting outbox cleanup - removing events processed before {}", cutoffTime);
            
            // Delete old processed events
            int deletedCount = outboxEventRepository.deleteProcessedEventsBefore(cutoffTime);
            
            if (span != null) {
                span.setAttribute("events.deleted", deletedCount);
            }
            
            logger.info("Completed outbox cleanup - deleted {} processed events", deletedCount);
            
            // Log current statistics
            OutboxEventService.OutboxStatistics stats = getStatistics();
            logger.info("Outbox statistics after cleanup - Pending: {}, Processed: {}, Failed: {}, Total: {}",
                stats.pending(), stats.processed(), stats.failed(), stats.total());
            
            if (span != null) {
                span.setAttribute("events.pending", stats.pending());
                span.setAttribute("events.processed", stats.processed());
                span.setAttribute("events.failed", stats.failed());
            }
            
        } catch (Exception e) {
            logger.error("Error during outbox cleanup", e);
            if (span != null) {
                span.recordException(e);
            }
            throw e; // Rethrow to trigger transaction rollback
            
        } finally {
            MDC.clear();
            if (scope != null) {
                scope.close();
            }
            if (span != null) {
                span.end();
            }
        }
    }
    
    /**
     * Additional cleanup for demo purposes - run every hour.
     * This can be enabled for demos to show cleanup working.
     */
    @Scheduled(fixedDelay = 3600000, initialDelay = 300000) // Every hour, starting 5 minutes after startup
    @Transactional
    public void cleanupForDemo() {
        if (!cleanupEnabled) {
            return;
        }
        
        // Only run in demo mode (check via property or environment)
        String demoMode = System.getProperty("demo.mode", "false");
        if (!"true".equals(demoMode)) {
            return;
        }
        
        try {
            // For demo, clean up events older than 1 hour
            Instant cutoffTime = Instant.now().minus(Duration.ofHours(1));
            int deletedCount = outboxEventRepository.deleteProcessedEventsBefore(cutoffTime);
            
            if (deletedCount > 0) {
                logger.info("Demo cleanup - deleted {} processed events older than 1 hour", deletedCount);
            }
            
        } catch (Exception e) {
            logger.error("Error during demo cleanup", e);
            // Don't rethrow - this is non-critical
        }
    }
    
    private OutboxEventService.OutboxStatistics getStatistics() {
        long pending = outboxEventRepository.countByStatus(OutboxEventStatus.PENDING);
        long processed = outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED);
        long failed = outboxEventRepository.countByStatus(OutboxEventStatus.FAILED);
        
        return new OutboxEventService.OutboxStatistics(pending, processed, failed);
    }
}