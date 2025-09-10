package com.example.analyticsapi;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;

@Component
public class KafkaListeners {
    
    private static final Logger logger = LoggerFactory.getLogger(KafkaListeners.class);
    
    private static final String LINK_EVENTS_TOPIC = "link-events";
    private static final String ANALYTICS_EVENTS_TOPIC = "analytics-events";
    private static final String URL_CLICKS_TOPIC = "url-clicks";
    private static final String GROUP_ID = "analytics-group";
    
    @Autowired(required = false)
    private OpenTelemetry openTelemetry;
    
    private Tracer tracer;
    private TextMapPropagator propagator;
    
    @Autowired
    public void init() {
        if (openTelemetry != null) {
            this.tracer = openTelemetry.getTracer("kafka-consumer");
            this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
        }
    }

    @KafkaListener(topics = LINK_EVENTS_TOPIC, groupId = GROUP_ID)
    void listenLinkEvents(ConsumerRecord<String, String> record) {
        Context extractedContext = extractTraceContext(record.headers());
        
        Span span = null;
        Scope scope = null;
        
        try {
            if (tracer != null) {
                // Check if we have a valid extracted context
                boolean hasExtractedTrace = extractedContext != Context.current() && 
                    Span.fromContext(extractedContext).getSpanContext().isValid();
                
                if (hasExtractedTrace) {
                    // Continue the trace from Kafka headers
                    span = tracer.spanBuilder("kafka.consume.link-events")
                        .setParent(extractedContext)
                        .setSpanKind(SpanKind.CONSUMER)
                        .setAttribute("service.name", "analytics-api")
                        .setAttribute("messaging.system", "kafka")
                        .setAttribute("messaging.destination", LINK_EVENTS_TOPIC)
                        .setAttribute("messaging.operation", "consume")
                        .setAttribute("messaging.message.id", record.key())
                        .startSpan();
                } else {
                    // Start a new trace if no context found
                    span = tracer.spanBuilder("kafka.consume.link-events")
                        .setSpanKind(SpanKind.CONSUMER)
                        .setAttribute("service.name", "analytics-api")
                        .setAttribute("messaging.system", "kafka")
                        .setAttribute("messaging.destination", LINK_EVENTS_TOPIC)
                        .setAttribute("messaging.operation", "consume")
                        .setAttribute("messaging.message.id", record.key())
                        .setAttribute("messaging.orphaned", true)
                        .startSpan();
                }
                scope = span.makeCurrent();
                
                // Set MDC with trace ID
                if (span.getSpanContext().isValid()) {
                    MDC.put("traceId", span.getSpanContext().getTraceId());
                }
            }
            
            MDC.put("service", "analytics-api");
            MDC.put("topic", LINK_EVENTS_TOPIC);
            
            logger.info("Received link event - Key: {}, Value: {}", record.key(), record.value());
            
            // Process the message
            processLinkEvent(record.value());
            
        } catch (Exception e) {
            logger.error("Error processing link event", e);
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

    @KafkaListener(topics = ANALYTICS_EVENTS_TOPIC, groupId = GROUP_ID)
    void listenAnalyticsEvents(ConsumerRecord<String, String> record) {
        Context extractedContext = extractTraceContext(record.headers());
        
        Span span = null;
        Scope scope = null;
        
        try {
            if (tracer != null) {
                // Check if we have a valid extracted context
                boolean hasExtractedTrace = extractedContext != Context.current() && 
                    Span.fromContext(extractedContext).getSpanContext().isValid();
                
                if (hasExtractedTrace) {
                    // Continue the trace from Kafka headers
                    span = tracer.spanBuilder("kafka.consume.analytics-events")
                        .setParent(extractedContext)
                        .setSpanKind(SpanKind.CONSUMER)
                        .setAttribute("service.name", "analytics-api")
                        .setAttribute("messaging.system", "kafka")
                        .setAttribute("messaging.destination", ANALYTICS_EVENTS_TOPIC)
                        .setAttribute("messaging.operation", "consume")
                        .setAttribute("messaging.message.id", record.key())
                        .startSpan();
                } else {
                    // Start a new trace if no context found
                    span = tracer.spanBuilder("kafka.consume.analytics-events")
                        .setSpanKind(SpanKind.CONSUMER)
                        .setAttribute("service.name", "analytics-api")
                        .setAttribute("messaging.system", "kafka")
                        .setAttribute("messaging.destination", ANALYTICS_EVENTS_TOPIC)
                        .setAttribute("messaging.operation", "consume")
                        .setAttribute("messaging.message.id", record.key())
                        .setAttribute("messaging.orphaned", true)
                        .startSpan();
                }
                scope = span.makeCurrent();
                
                // Set MDC with trace ID
                if (span.getSpanContext().isValid()) {
                    MDC.put("traceId", span.getSpanContext().getTraceId());
                }
            }
            
            MDC.put("service", "analytics-api");
            MDC.put("topic", ANALYTICS_EVENTS_TOPIC);
            
            logger.info("Received analytics event - Key: {}, Value: {}", record.key(), record.value());
            
            // Process the analytics report
            processAnalyticsEvent(record.value());
            
        } catch (Exception e) {
            logger.error("Error processing analytics event", e);
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

    @KafkaListener(topics = URL_CLICKS_TOPIC, groupId = GROUP_ID)
    void listenUrlClicks(ConsumerRecord<String, String> record) {
        Context extractedContext = extractTraceContext(record.headers());
        
        Span span = null;
        Scope scope = null;
        
        try {
            if (tracer != null) {
                span = tracer.spanBuilder("kafka.consume.url-clicks")
                    .setParent(extractedContext)
                    .setSpanKind(SpanKind.CONSUMER)
                    .setAttribute("messaging.system", "kafka")
                    .setAttribute("messaging.destination", URL_CLICKS_TOPIC)
                    .setAttribute("messaging.operation", "consume")
                    .setAttribute("messaging.message.id", record.key())
                    .startSpan();
                scope = span.makeCurrent();
                
                // Set MDC with trace ID
                if (span.getSpanContext().isValid()) {
                    MDC.put("traceId", span.getSpanContext().getTraceId());
                }
            }
            
            MDC.put("service", "analytics-api");
            MDC.put("topic", URL_CLICKS_TOPIC);
            
            logger.info("Received click event - Key: {}, Value: {}", record.key(), record.value());
            
            // Process the click event
            processClickEvent(record.value());
            
        } catch (Exception e) {
            logger.error("Error processing click event", e);
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
    
    private Context extractTraceContext(Headers headers) {
        if (headers == null) {
            logger.debug("No headers available for trace extraction");
            return Context.current();
        }
        
        // First, try to construct W3C traceparent from individual headers (from Debezium)
        Header traceIdHeader = headers.lastHeader("trace_id");
        Header spanIdHeader = headers.lastHeader("parent_span_id");
        Header traceFlagsHeader = headers.lastHeader("trace_flags");
        
        if (traceIdHeader != null && spanIdHeader != null) {
            String traceId = new String(traceIdHeader.value(), StandardCharsets.UTF_8);
            String spanId = new String(spanIdHeader.value(), StandardCharsets.UTF_8);
            String traceFlags = traceFlagsHeader != null ? 
                new String(traceFlagsHeader.value(), StandardCharsets.UTF_8) : "01";
            
            // Construct W3C traceparent header
            String traceparent = String.format("00-%s-%s-%s", traceId, spanId, traceFlags);
            
            // Add the constructed traceparent to headers for extraction
            headers.add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            
            logger.debug("Constructed traceparent from individual headers: {}", traceparent);
        }
        
        // Now extract using the standard propagator
        if (propagator == null) {
            logger.debug("No propagator available for trace extraction");
            return Context.current();
        }
        
        Context extracted = propagator.extract(Context.current(), headers, new KafkaHeadersGetter());
        
        // Log the extracted trace context for debugging
        Span extractedSpan = Span.fromContext(extracted);
        if (extractedSpan.getSpanContext().isValid()) {
            logger.debug("Extracted valid trace context - TraceId: {}, SpanId: {}", 
                extractedSpan.getSpanContext().getTraceId(),
                extractedSpan.getSpanContext().getSpanId());
        } else {
            logger.debug("No valid trace context extracted from Kafka headers");
        }
        
        return extracted;
    }
    
    private void processLinkEvent(String message) {
        // Process expired link events
        logger.debug("Processing link event: {}", message);
        // In a real application, you might:
        // - Parse JSON
        // - Update database
        // - Send notifications
        // - Update metrics
    }
    
    private void processAnalyticsEvent(String message) {
        // Process analytics reports
        logger.debug("Processing analytics report: {}", message);
        // In a real application, you might:
        // - Parse JSON
        // - Store in time-series database
        // - Update dashboards
        // - Generate alerts if thresholds exceeded
    }
    
    private void processClickEvent(String message) {
        // Process click events
        logger.debug("Processing click event: {}", message);
        // In a real application, you might:
        // - Parse JSON
        // - Update click count in database
        // - Update real-time analytics
        // - Track user behavior
    }
    
    /**
     * Helper class to extract trace context from Kafka headers.
     */
    private static class KafkaHeadersGetter implements TextMapGetter<Headers> {
        @Override
        public Iterable<String> keys(Headers carrier) {
            return () -> new Iterator<String>() {
                private final Iterator<Header> it = carrier.iterator();
                
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }
                
                @Override
                public String next() {
                    return it.next().key();
                }
            };
        }
        
        @Override
        public String get(Headers carrier, String key) {
            if (carrier == null) {
                return null;
            }
            Header header = carrier.lastHeader(key);
            if (header != null && header.value() != null) {
                return new String(header.value(), StandardCharsets.UTF_8);
            }
            return null;
        }
    }
}
