package com.example.urlapi;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filter to establish MDC context from request headers for centralized logging.
 * Extracts context headers set by BFF and OpenTelemetry trace information.
 */
@Component
@Order(1)
public class MdcContextFilter implements Filter {
    
    private static final Logger logger = LoggerFactory.getLogger(MdcContextFilter.class);
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        
        try {
            // Extract CORE context headers (5 headers as per desktop review)
            String requestId = httpRequest.getHeader("X-Request-ID");
            String userId = httpRequest.getHeader("X-User-ID");
            String tenantId = httpRequest.getHeader("X-Tenant-ID");
            String serviceName = httpRequest.getHeader("X-Service-Name");
            String transactionType = httpRequest.getHeader("X-Transaction-Type");
            
            // Extract trace context
            String traceParent = httpRequest.getHeader("traceparent");
            String traceId = extractTraceId(traceParent);
            
            // If no trace ID from header, try to get from current span
            if (traceId == null) {
                Span currentSpan = Span.current();
                if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
                    traceId = currentSpan.getSpanContext().getTraceId();
                }
            }
            
            // Populate MDC with CORE context (selective population as per desktop review)
            if (requestId != null) MDC.put("requestId", requestId);
            if (userId != null) MDC.put("userId", userId);
            if (tenantId != null) MDC.put("tenantId", tenantId);
            if (serviceName != null) MDC.put("originService", serviceName);
            if (transactionType != null) MDC.put("transactionType", transactionType);
            if (traceId != null) MDC.put("traceId", traceId);
            
            // Add current service context
            MDC.put("service", "url-api");
            
            logger.info("Context received - RequestID: {}, User: {}, Tenant: {}, Transaction: {}, TraceId: {}", 
                       requestId != null ? requestId : "none",
                       userId != null ? userId : "anonymous", 
                       tenantId != null ? tenantId : "default",
                       transactionType != null ? transactionType : "unknown",
                       traceId);
            
            // Continue with the request
            chain.doFilter(request, response);
            
        } finally {
            // Clear MDC to prevent leakage
            MDC.clear();
        }
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