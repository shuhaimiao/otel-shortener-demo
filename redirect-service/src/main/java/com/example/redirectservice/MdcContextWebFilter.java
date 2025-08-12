package com.example.redirectservice;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * WebFilter to establish MDC context from request headers for centralized logging in reactive applications.
 * Extracts context headers and OpenTelemetry trace information.
 */
@Component
@Order(1)
public class MdcContextWebFilter implements WebFilter {
    
    private static final Logger logger = LoggerFactory.getLogger(MdcContextWebFilter.class);
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Extract headers
        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-ID");
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-ID");
        String userEmail = exchange.getRequest().getHeaders().getFirst("X-User-Email");
        String userGroups = exchange.getRequest().getHeaders().getFirst("X-User-Groups");
        String serviceName = exchange.getRequest().getHeaders().getFirst("X-Service-Name");
        String transactionName = exchange.getRequest().getHeaders().getFirst("X-Transaction-Name");
        String correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
        String traceParent = exchange.getRequest().getHeaders().getFirst("traceparent");
        
        String traceId = extractTraceId(traceParent);
        
        // If no trace ID from header, try to get from current span
        if (traceId == null) {
            Span currentSpan = Span.current();
            if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
                traceId = currentSpan.getSpanContext().getTraceId();
            }
        }
        
        // Create context map
        java.util.Map<String, String> contextMap = new java.util.HashMap<>();
        if (tenantId != null) contextMap.put("tenantId", tenantId);
        if (userId != null) contextMap.put("userId", userId);
        if (userEmail != null) contextMap.put("userEmail", userEmail);
        if (userGroups != null) contextMap.put("userGroups", userGroups);
        if (serviceName != null) contextMap.put("originService", serviceName);
        if (transactionName != null) contextMap.put("transaction", transactionName);
        if (correlationId != null) contextMap.put("correlationId", correlationId);
        if (traceId != null) contextMap.put("traceId", traceId);
        
        // Add request-specific context
        contextMap.put("requestMethod", exchange.getRequest().getMethod().toString());
        contextMap.put("requestPath", exchange.getRequest().getPath().value());
        contextMap.put("service", "redirect-service");
        
        // Log with MDC context
        return chain.filter(exchange)
            .contextWrite(Context.of("mdcContext", contextMap))
            .doOnSubscribe(subscription -> {
                // Set MDC for the subscription thread
                contextMap.forEach(MDC::put);
                logger.info("MDC context established - User: {}, Tenant: {}, Transaction: {}, TraceId: {}", 
                           userId != null ? userId : "anonymous", 
                           tenantId != null ? tenantId : "default",
                           transactionName != null ? transactionName : exchange.getRequest().getMethod() + " " + exchange.getRequest().getPath().value(),
                           traceId);
            })
            .doFinally(signalType -> {
                // Clear MDC after processing
                MDC.clear();
            });
    }
    
    /**
     * Extract trace ID from W3C traceparent header.
     * Format: version-traceId-spanId-flags
     * Example: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
     */
    private String extractTraceId(String traceParent) {
        if (traceParent == null || traceParent.isEmpty()) {
            return null;
        }
        
        String[] parts = traceParent.split("-");
        if (parts.length >= 3) {
            return parts[1]; // The trace ID is the second part
        }
        
        return null;
    }
}