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
            // Extract context headers set by BFF
            String tenantId = httpRequest.getHeader("X-Tenant-ID");
            String userId = httpRequest.getHeader("X-User-ID");
            String userEmail = httpRequest.getHeader("X-User-Email");
            String userGroups = httpRequest.getHeader("X-User-Groups");
            String serviceName = httpRequest.getHeader("X-Service-Name");
            String transactionName = httpRequest.getHeader("X-Transaction-Name");
            String correlationId = httpRequest.getHeader("X-Correlation-ID");
            
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
            
            // Populate MDC
            if (tenantId != null) MDC.put("tenantId", tenantId);
            if (userId != null) MDC.put("userId", userId);
            if (userEmail != null) MDC.put("userEmail", userEmail);
            if (userGroups != null) MDC.put("userGroups", userGroups);
            if (serviceName != null) MDC.put("originService", serviceName);
            if (transactionName != null) MDC.put("transaction", transactionName);
            if (correlationId != null) MDC.put("correlationId", correlationId);
            if (traceId != null) MDC.put("traceId", traceId);
            
            // Add request-specific context
            MDC.put("requestMethod", httpRequest.getMethod());
            MDC.put("requestPath", httpRequest.getRequestURI());
            MDC.put("service", "url-api");
            
            logger.info("MDC context established - User: {}, Tenant: {}, Transaction: {}, TraceId: {}", 
                       userId != null ? userId : "anonymous", 
                       tenantId != null ? tenantId : "default",
                       transactionName != null ? transactionName : httpRequest.getMethod() + " " + httpRequest.getRequestURI(),
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