package com.example.urlapi;

import com.example.urlapi.outbox.OutboxEventService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled jobs for the URL API service.
 * Demonstrates trace propagation from scheduled jobs to Kafka.
 */
@Component
@EnableScheduling
public class ScheduledJobs {
    
    private static final Logger logger = LoggerFactory.getLogger(ScheduledJobs.class);
    
    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafkaTemplate;
    
    @Autowired(required = false)
    private LinkRepository linkRepository;
    
    @Autowired(required = false)
    private OutboxEventService outboxEventService;
    
    @Autowired(required = false)
    private OpenTelemetry openTelemetry;
    
    private Tracer tracer;
    private TextMapPropagator propagator;
    
    @Autowired
    public void init() {
        if (openTelemetry != null) {
            this.tracer = openTelemetry.getTracer("scheduled-jobs");
            this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
        }
    }
    
    /**
     * Scheduled job to check for expired links.
     * Runs every 30 seconds for demo purposes (in production, might be daily).
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    @Transactional
    public void checkExpiredLinks() {
        // Start a new span for the scheduled job
        Span span = null;
        Scope scope = null;
        
        try {
            if (tracer != null) {
                span = tracer.spanBuilder("scheduled.check-expired-links")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("job.name", "check-expired-links")
                    .setAttribute("job.type", "scheduled")
                    .startSpan();
                scope = span.makeCurrent();
            }
            
            // Set MDC context for the job
            String jobId = UUID.randomUUID().toString();
            MDC.put("jobId", jobId);
            MDC.put("jobName", "check-expired-links");
            MDC.put("service", "url-api");
            if (span != null) {
                MDC.put("traceId", span.getSpanContext().getTraceId());
            }
            
            logger.info("Starting scheduled job: check-expired-links");
            
            // Simulate checking for expired links (30 days old)
            Instant expiryThreshold = Instant.now().minus(30, ChronoUnit.DAYS);
            
            if (linkRepository != null) {
                // In a real implementation, you'd query for expired links
                // For demo, we'll just count all links
                long totalLinks = linkRepository.count();
                logger.info("Found {} total links in database", totalLinks);
                
                if (span != null) {
                    span.setAttribute("links.total", totalLinks);
                    span.setAttribute("links.expiry.threshold", expiryThreshold.toString());
                }
                
                // Simulate finding some expired links
                int expiredCount = (int) (Math.random() * 5); // Random 0-4 for demo
                logger.info("Found {} expired links", expiredCount);
                
                if (span != null) {
                    span.setAttribute("links.expired.count", expiredCount);
                }
                
                // Write expired link events to outbox (will be picked up by CDC)
                // This maintains trace context automatically
                if (outboxEventService != null) {
                    for (int i = 0; i < expiredCount; i++) {
                        String linkId = "expired-link-" + i;
                        outboxEventService.createLinkExpiredEvent(linkId, "30 days old");
                        logger.debug("Created outbox event for expired link: {}", linkId);
                    }
                } else {
                    // Fallback to direct Kafka publishing if outbox not available
                    for (int i = 0; i < expiredCount; i++) {
                        publishExpiredLinkEvent(jobId, "expired-link-" + i, span);
                    }
                }
            } else {
                logger.warn("LinkRepository not available, skipping expired link check");
            }
            
            logger.info("Completed scheduled job: check-expired-links");
            
        } catch (Exception e) {
            logger.error("Error in scheduled job: check-expired-links", e);
            if (span != null) {
                span.recordException(e);
            }
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
     * Scheduled job to generate analytics report.
     * Runs every 60 seconds for demo purposes.
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 20000)
    public void generateAnalyticsReport() {
        Span span = null;
        Scope scope = null;
        
        try {
            if (tracer != null) {
                span = tracer.spanBuilder("scheduled.generate-analytics")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("job.name", "generate-analytics")
                    .setAttribute("job.type", "scheduled")
                    .startSpan();
                scope = span.makeCurrent();
            }
            
            // Set MDC context
            String jobId = UUID.randomUUID().toString();
            MDC.put("jobId", jobId);
            MDC.put("jobName", "generate-analytics");
            MDC.put("service", "url-api");
            if (span != null) {
                MDC.put("traceId", span.getSpanContext().getTraceId());
            }
            
            logger.info("Starting scheduled job: generate-analytics");
            
            // Simulate generating analytics
            Map<String, Object> analytics = new HashMap<>();
            analytics.put("timestamp", Instant.now().toString());
            analytics.put("totalLinks", linkRepository != null ? linkRepository.count() : 0);
            analytics.put("dailyCreated", (int) (Math.random() * 100));
            analytics.put("dailyClicks", (int) (Math.random() * 1000));
            
            logger.info("Generated analytics report: {}", analytics);
            
            if (span != null) {
                final Span finalSpan = span;
                analytics.forEach((key, value) -> 
                    finalSpan.setAttribute("analytics." + key, value.toString()));
            }
            
            // Publish analytics event to Kafka
            publishAnalyticsEvent(jobId, analytics, span);
            
            logger.info("Completed scheduled job: generate-analytics");
            
        } catch (Exception e) {
            logger.error("Error in scheduled job: generate-analytics", e);
            if (span != null) {
                span.recordException(e);
            }
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
     * Publish expired link event to Kafka with trace propagation.
     */
    private void publishExpiredLinkEvent(String jobId, String linkId, Span parentSpan) {
        if (kafkaTemplate == null) {
            logger.debug("KafkaTemplate not available, skipping event publish");
            return;
        }
        
        Span span = null;
        Scope scope = null;
        
        try {
            if (tracer != null && parentSpan != null) {
                span = tracer.spanBuilder("kafka.publish.expired-link")
                    .setParent(Context.current().with(parentSpan))
                    .setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("messaging.system", "kafka")
                    .setAttribute("messaging.destination", "link-events")
                    .setAttribute("messaging.operation", "publish")
                    .startSpan();
                scope = span.makeCurrent();
            }
            
            Map<String, Object> event = new HashMap<>();
            event.put("eventType", "LINK_EXPIRED");
            event.put("linkId", linkId);
            event.put("jobId", jobId);
            event.put("timestamp", Instant.now().toString());
            
            ProducerRecord<String, Object> record = new ProducerRecord<>("link-events", linkId, event);
            
            // Inject trace context into Kafka headers
            if (propagator != null && span != null) {
                propagator.inject(Context.current(), record.headers(), new KafkaHeadersSetter());
            }
            
            kafkaTemplate.send(record);
            logger.debug("Published expired link event for link: {}", linkId);
            
        } catch (Exception e) {
            logger.error("Error publishing expired link event", e);
            if (span != null) {
                span.recordException(e);
            }
        } finally {
            if (scope != null) {
                scope.close();
            }
            if (span != null) {
                span.end();
            }
        }
    }
    
    /**
     * Publish analytics event to Kafka with trace propagation.
     */
    private void publishAnalyticsEvent(String jobId, Map<String, Object> analytics, Span parentSpan) {
        if (kafkaTemplate == null) {
            logger.debug("KafkaTemplate not available, skipping analytics publish");
            return;
        }
        
        Span span = null;
        Scope scope = null;
        
        try {
            if (tracer != null && parentSpan != null) {
                span = tracer.spanBuilder("kafka.publish.analytics")
                    .setParent(Context.current().with(parentSpan))
                    .setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("messaging.system", "kafka")
                    .setAttribute("messaging.destination", "analytics-events")
                    .setAttribute("messaging.operation", "publish")
                    .startSpan();
                scope = span.makeCurrent();
            }
            
            Map<String, Object> event = new HashMap<>();
            event.put("eventType", "ANALYTICS_REPORT");
            event.put("jobId", jobId);
            event.put("analytics", analytics);
            event.put("timestamp", Instant.now().toString());
            
            ProducerRecord<String, Object> record = new ProducerRecord<>("analytics-events", jobId, event);
            
            // Inject trace context into Kafka headers
            if (propagator != null && span != null) {
                propagator.inject(Context.current(), record.headers(), new KafkaHeadersSetter());
            }
            
            kafkaTemplate.send(record);
            logger.info("Published analytics event");
            
        } catch (Exception e) {
            logger.error("Error publishing analytics event", e);
            if (span != null) {
                span.recordException(e);
            }
        } finally {
            if (scope != null) {
                scope.close();
            }
            if (span != null) {
                span.end();
            }
        }
    }
    
    /**
     * Helper class to inject trace context into Kafka headers.
     */
    private static class KafkaHeadersSetter implements TextMapSetter<Headers> {
        @Override
        public void set(Headers carrier, String key, String value) {
            if (carrier != null && key != null && value != null) {
                carrier.add(key, value.getBytes());
            }
        }
    }
}