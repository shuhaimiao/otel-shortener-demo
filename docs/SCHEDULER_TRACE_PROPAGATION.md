# Scheduler-Originated Trace Context Propagation

This document outlines how to ensure that system-initiated processes, such as scheduled tasks, can correctly create and propagate W3C trace contexts using OpenTelemetry. This is essential for maintaining end-to-end observability in distributed systems where not all operations are initiated by user requests.

## The Goal

The primary objective is to enable a scheduled task to start a new trace, create a root span, and propagate its context to any downstream services it calls. This ensures that operations triggered by the scheduler are fully integrated into the distributed tracing system.

## The Challenge: Scheduler Initialization Order

A significant challenge arises from the Spring Boot application lifecycle. By default, scheduled tasks (`@Scheduled`) can be initialized and executed before the OpenTelemetry SDK is fully configured and ready. When this happens, any attempt to obtain a `Tracer` from `GlobalOpenTelemetry.get()` may result in a no-op `Tracer`.

Consequently, spans created by this no-op tracer will have an invalid `SpanContext`, characterized by zero-filled `traceId` and `spanId` values. As a result, the trace context is not propagated, and the logs lack the necessary correlation IDs (MDC is null or empty).

## The Solution: Explicitly Depending on OpenTelemetry

To solve this, we must enforce the correct bean initialization order, ensuring that our `Tracer` bean is only created after the OpenTelemetry SDK is ready.

### 1. `TracingConfig.java`: Explicit Dependency Injection

We define a `TracingConfig` configuration class to provide a `Tracer` bean. The key is to inject the `OpenTelemetry` object directly into our bean-provider method. This tells Spring that the `Tracer` bean has a dependency on the `OpenTelemetry` bean, which is auto-configured by the OpenTelemetry Spring Boot starter.

By doing this, Spring guarantees that the `OpenTelemetry` bean (and thus the underlying SDK) will be fully initialized before our `tracer` method is called.

```java
package com.example.opentelemetry.product.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfig {

    /**
     * Provides the OpenTelemetry Tracer bean for manual instrumentation.
     * Uses the OpenTelemetry instance configured by the Spring Boot Starter.
     * 
     * @param openTelemetry the auto-configured OpenTelemetry instance
     * @return Tracer instance for the product-service
     */
    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("product-service", "1.0.0");
    }
}
```

### 2. Manual Span Creation in the Scheduler

Within the scheduled task, we inject and use this `Tracer` to manually create a new span. This span will serve as the root for the trace originating from the scheduler.

```java
@Autowired
private Tracer tracer;

@Scheduled(fixedRate = 300000, initialDelay = 0)
public void monitorPrices() {
    // Create new trace span for scheduled operation
    Span schedulerSpan = tracer.spanBuilder("price-monitor-scheduler")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
            
    try (Scope scope = schedulerSpan.makeCurrent()) {
        // ... business logic ...
    } finally {
        schedulerSpan.end();
    }
}
```

### 3. Populating MDC for Logs

To ensure the trace context appears in application logs, we manually populate the SLF4J Mapped Diagnostic Context (MDC). This step is crucial for correlating logs with traces.

```java
private void populateMDCWithTraceContext(Span span) {
    if (span != null && span.getSpanContext().isValid()) {
        String traceId = span.getSpanContext().getTraceId();
        String spanId = span.getSpanContext().getSpanId();
        String traceFlags = span.getSpanContext().getTraceFlags().asHex();
        
        // Create W3C traceparent header format: 00-{traceId}-{spanId}-{flags}
        String traceparent = String.format("00-%s-%s-%s", traceId, spanId, traceFlags);
        
        MDC.put("traceparent", traceparent);
        MDC.put("trace.id", traceId);
        MDC.put("trace.span.id", spanId);
    }
}
```

This method is called within the scope of the new span, making the `traceparent` and other IDs available to the logging framework. With this implementation, scheduler-originated traces are correctly created and propagated, providing complete visibility into system-initiated workflows. 