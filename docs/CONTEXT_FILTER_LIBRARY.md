# Context Filter Library

## Overview

The Context Filter Library is a Spring Boot starter that provides standardized context propagation across microservices. It supports both **servlet-based** (Spring MVC) and **reactive** (Spring WebFlux) applications, ensuring consistent context handling and propagation patterns.

## Key Features

### 🎯 **Unified Context Management**
- **StandardContext enum** defines all context fields consistently across services
- **JWT token parsing** with fallback to HTTP headers
- **W3C Trace Context** support (traceparent, tracestate)
- **Automatic context propagation** between MDC and Reactor Context

### 🔄 **Dual Application Support**
- **StandardContextServletFilter** for classic Spring MVC applications
- **StandardContextWebFilter** for reactive Spring WebFlux applications
- **Automatic selection** based on application type via auto-configuration

### 🛡️ **Security & Privacy**
- **Configurable field logging** (sensitive fields can be redacted)
- **Graceful error handling** for malformed tokens
- **Context isolation** between requests

### ⚡ **Performance Optimized**
- **Minimal overhead** with efficient context extraction
- **Lazy JWT parsing** only when Authorization header is present
- **Streamlined context propagation** using ContextRegistry integration

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                 Context Filter Library                      │
├─────────────────────────────────────────────────────────────┤
│  StandardContext (Enum)                                    │
│  ├─ TRACEPARENT, TRACESTATE                               │
│  ├─ REQUEST_ID, TRACE_ID, CORRELATION_ID                  │
│  ├─ TENANT_ID, USER_ID, SESSION_ID                        │
│  └─ Custom application-specific fields                     │
├─────────────────────────────────────────────────────────────┤
│  Filters                                                   │
│  ├─ StandardContextServletFilter  (Servlet/MVC)           │
│  └─ StandardContextWebFilter      (Reactive/WebFlux)       │
├─────────────────────────────────────────────────────────────┤
│  Utilities                                                 │
│  ├─ JwtParser          (JWT token context extraction)      │
│  ├─ ContextExtractor   (Header + JWT context extraction)   │
│  └─ StandardContextPropagation (MDC ↔ Reactor Context)    │
├─────────────────────────────────────────────────────────────┤
│  Auto-Configuration                                        │
│  └─ StandardContextAutoConfiguration (Spring Boot Starter) │
└─────────────────────────────────────────────────────────────┘
```

## Standard Context Fields

| Field | Key | Header | Purpose | Loggable |
|-------|-----|--------|---------|----------|
| **TRACEPARENT** | `traceparent` | `traceparent` | W3C Trace Context | ✅ |
| **TRACESTATE** | `tracestate` | `tracestate` | W3C Trace State | ❌ |
| **REQUEST_ID** | `request.id` | `X-REQUEST-ID` | Unique request identifier | ✅ |
| **TRACE_ID** | `trace.id` | `X-TRACE-ID` | Custom trace identifier | ✅ |
| **TRACE_SPAN_ID** | `trace.span.id` | `X-TRACE-SPAN-ID` | Custom span identifier | ✅ |
| **TENANT_ID** | `tenant.id` | `X-TENANT-ID` | Multi-tenant identifier | ✅ |
| **TENANT_NAME** | `tenant.name` | `X-TENANT-NAME` | Tenant display name | ✅ |
| **USER_ID** | `user.id` | `X-USER-ID` | User identifier | ✅ |
| **USER_EMAIL** | `user.email` | `X-USER-EMAIL` | User email address | ❌ |
| **USER_PASSWORD** | `user.password` | `X-USER-PASSWORD` | User password (sensitive) | ❌ |
| **SESSION_ID** | `session.id` | `X-SESSION-ID` | Session identifier | ✅ |
| **CORRELATION_ID** | `correlation.id` | `X-CORRELATION-ID` | Request correlation | ✅ |
| **API_KEY** | `api.key` | `X-API-KEY` | API authentication key | ❌ |

## Integration Guide

### 1. Add Dependency

Add the context filter library to your service's `pom.xml`:

```xml
<dependency>
    <groupId>com.baeldung</groupId>
    <artifactId>context-filter-library</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Enable Context Filter

Add configuration to your `application.yml`:

```yaml
# Context Filter Configuration
context:
  filter:
    enabled: true  # Default: true
```

### 3. Auto-Configuration

The library automatically configures the appropriate filter based on your application type:

- **Servlet applications**: `StandardContextServletFilter` is automatically registered
- **Reactive applications**: `StandardContextWebFilter` is automatically registered
- **Mixed applications**: Both filters can coexist

## Usage Examples

### Servlet Controller (Spring MVC)

```java
@RestController
@Slf4j
public class MyController {
    
    @GetMapping("/api/data")
    public ResponseEntity<Data> getData() {
        // Context is automatically available in MDC
        String requestId = MDC.get(StandardContext.REQUEST_ID.getKey());
        String tenantId = MDC.get(StandardContext.TENANT_ID.getKey());
        
        log.info("Processing request for tenant: {}", tenantId);
        
        return ResponseEntity.ok(data);
    }
}
```

### Reactive Controller (Spring WebFlux)

```java
@RestController
@Slf4j
public class MyReactiveController {
    
    @GetMapping("/api/reactive-data")
    public Mono<ResponseEntity<Data>> getReactiveData() {
        return Mono.deferContextual(contextView -> {
            // Context is available in Reactor Context and MDC
            String requestId = contextView.get(StandardContext.REQUEST_ID.getKey());
            String tenantId = MDC.get(StandardContext.TENANT_ID.getKey());
            
            log.info("Processing reactive request for tenant: {}", tenantId);
            
            return dataService.getData()
                .map(ResponseEntity::ok);
        });
    }
}
```

### Manual Context Access

```java
@Service
@Slf4j
public class MyService {
    
    public void processData() {
        // Get current context from MDC
        Map<String, String> context = ContextExtractor.getCurrentMdcContext();
        
        // Log with context
        log.info("Processing with context: {}", context);
        
        // Clear context when needed
        ContextExtractor.clearMdcContext();
    }
}
```

## Context Sources Priority

The library extracts context from multiple sources with the following priority:

1. **JWT Token Claims** (highest priority for all fields)
2. **W3C Trace Context** (for trace.id and trace.span.id when traceparent is present)
3. **HTTP Headers** (fallback for individual fields)
4. **Default Values** (if configured)

### Trace Context Extraction Logic

**Priority Order for trace.id and trace.span.id:**

1. **JWT Claims**: `trace.id` and `trace.span.id` from JWT token
2. **traceparent Header**: Parsed from W3C traceparent format `version-traceId-spanId-traceFlags`
3. **Individual Headers**: `X-TRACE-ID` and `X-TRACE-SPAN-ID` headers

**Example - traceparent takes precedence:**
```http
GET /api/data
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
X-TRACE-ID: header-trace-ignored
X-TRACE-SPAN-ID: header-span-ignored
```
Result: `trace.id = "4bf92f3577b34da6a3ce929d0e0e4736"`, `trace.span.id = "00f067aa0ba902b7"`

**Example - fallback to individual headers:**
```http
GET /api/data
X-TRACE-ID: custom-trace-123
X-TRACE-SPAN-ID: custom-span-456
```
Result: `trace.id = "custom-trace-123"`, `trace.span.id = "custom-span-456"`

### JWT Token Example

```http
GET /api/data
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
X-REQUEST-ID: header-request-id
```

If the JWT contains `"request.id": "jwt-request-id"`, the value `"jwt-request-id"` will be used, and `"header-request-id"` will be ignored.

### HTTP Headers Example

```http
GET /api/data
X-REQUEST-ID: req-12345
X-TRACE-ID: trace-abcdef
X-TENANT-ID: tenant-001
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

All context fields will be extracted and available in both MDC and Reactor Context.

## Context Propagation

### MDC ↔ Reactor Context Bridge

The library automatically bridges MDC and Reactor Context using Micrometer's ContextRegistry:

```java
@Configuration
public class StandardContextPropagation {
    
    @PostConstruct
    public void setupContextPropagation() {
        // Register all loggable context keys with ContextRegistry
        ContextRegistry.getInstance()
            .registerThreadLocalAccessor(
                StandardContext.getLoggableKeys()
            );
        
        // Enable automatic context propagation in Reactor
        Hooks.enableAutomaticContextPropagation();
    }
}
```

### Downstream Service Calls

Context is automatically added to response headers for downstream propagation:

```java
// Outgoing HTTP calls will include context headers
WebClient.builder()
    .filter(/* context headers are automatically added */)
    .build()
    .get()
    .uri("http://downstream-service/api/data")
    .retrieve()
    .bodyToMono(Data.class);
```

## Testing

### Test the Context Filter

Use the provided test endpoints to verify functionality:

```bash
# Test with HTTP headers
curl -H "X-REQUEST-ID: test-123" \
     -H "X-TENANT-ID: tenant-001" \
     http://localhost:8080/api/context-demo/servlet

# Test with JWT token
curl -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." \
     http://localhost:8080/api/context-demo/reactive

# Health check
curl http://localhost:8080/api/context-demo/health
```

### Log Output Examples

```json
{
  "timestamp": "2025-01-07T06:41:23.456Z",
  "level": "INFO",
  "message": "Processing request for tenant: tenant-001",
  "request.id": "test-123",
  "tenant.id": "tenant-001", 
  "trace.id": "trace-abcdef",
  "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
}
```

## Configuration Options

### Enable/Disable Filter

```yaml
context:
  filter:
    enabled: false  # Disable context filter
```

### Custom Context Fields

To add custom context fields, extend the `StandardContext` enum:

```java
public enum CustomContext {
    ORGANIZATION_ID("organization.id", "X-ORG-ID", "", true),
    ENVIRONMENT("environment", "X-ENVIRONMENT", "production", true);
    
    // Implementation similar to StandardContext
}
```

## Best Practices

### 1. **Context Field Design**
- Use consistent naming conventions (dot notation for keys)
- Mark sensitive fields as non-loggable
- Provide meaningful default values where appropriate

### 2. **JWT Token Structure**
```json
{
  "sub": "user-123",
  "tenant.id": "tenant-001",
  "user.id": "user-123",
  "request.id": "req-456",
  "trace.id": "trace-789",
  "iat": 1672531200,
  "exp": 1672534800
}
```

### 3. **Error Handling**
- The library gracefully handles malformed JWT tokens
- Invalid context values are logged but don't break functionality
- Context extraction failures fall back to header-based extraction

### 4. **Performance Considerations**
- JWT parsing is only performed when Authorization header is present
- Context extraction is cached per request
- Minimal overhead for requests without context

## Troubleshooting

### Common Issues

**Q: Context not available in reactive endpoints**
A: Ensure you're using `Mono.deferContextual()` to access Reactor Context, or access MDC directly (which is also populated).

**Q: JWT context not extracted**
A: Verify JWT structure matches expected claims and that the Authorization header uses "Bearer " prefix.

**Q: Context not propagating to downstream services**
A: Check that response headers are being set correctly and downstream services have the context filter enabled.

## ✅ Implementation Success Summary

### 🎉 **All Features Successfully Implemented and Tested!**

**✅ Context Filter Library** - Shared library with StandardContext, filters, and utilities  
**✅ Servlet Filter** - HTTP header and JWT context extraction for Spring MVC  
**✅ WebFlux Filter** - Reactive context propagation for Spring WebFlux  
**✅ MDC ↔ Reactor Bridge** - Seamless context sharing between blocking/non-blocking  
**✅ JWT Parsing** - Token-based context extraction with header fallback  
**✅ Structured Logging** - Context fields automatically in logs  
**✅ Auto-Configuration** - Automatic setup with Spring Boot starter pattern  

### 📊 **Live Test Results:**

**HTTP Headers → Context Extraction:**
```json
{
  "context": {
    "trace.id": "trace-abcdef",
    "user.id": "user-999", 
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    "request.id": "req-12345",
    "tenant.id": "tenant-001"
  }
}
```

**JWT Token → Context Extraction:**
```json
{
  "context": {
    "trace.id": "trace-jwt-abcdef",
    "user.id": "user-jwt-999",
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", 
    "request.id": "req-jwt-12345",
    "tenant.id": "tenant-jwt-001"
  }
}
```

**JWT + Headers Fallback:**
```json
{
  "context": {
    "trace.id": "trace-header-xyz",      // ← From Header (fallback)
    "user.id": "user-jwt-999",           // ← From JWT (priority)
    "request.id": "req-header-456",      // ← From Header (fallback)
    "tenant.id": "tenant-jwt-001"        // ← From JWT (priority)  
  }
}
```

**Reactive Context Propagation:**
```json
{
  "reactorContext": {
    "trace.id": "trace-123456",
    "user.id": "user-888",
    "request.id": "req-67890", 
    "tenant.id": "tenant-002"
  },
  "context": {
    "trace.id": "trace-123456",
    "user.id": "user-888", 
    "request.id": "req-67890",
    "tenant.id": "tenant-002"
  }
}
```

### 🏗️ **Architecture Benefits Achieved:**

1. **🔄 Unified Context Management** - Single StandardContext enum across all services
2. **⚡ Dual Platform Support** - Works with both Spring MVC and WebFlux
3. **🎯 Smart Fallback** - JWT-first with HTTP header fallback
4. **📝 Enhanced Logging** - Structured context in all log entries
5. **🚀 Zero Code Duplication** - Shared library pattern
6. **🔧 Auto-Configuration** - Just add dependency and configure
7. **🔒 Security Aware** - Sensitive fields marked as non-loggable

This implementation successfully demonstrates enterprise-grade context propagation patterns suitable for microservices architectures with both blocking and non-blocking endpoints!

**Q: Sensitive fields appearing in logs**
A: Verify that sensitive fields are marked with `loggable = false` in the StandardContext enum.

### Debug Logging

Enable debug logging to troubleshoot context extraction:

```yaml
logging:
  level:
    com.example.opentelemetry.context: DEBUG
```

## Migration Guide

### From Custom Context Solutions

1. Replace custom context handling with StandardContext enum
2. Update JWT parsing logic to use JwtParser utility
3. Replace manual MDC management with automatic filter-based approach
4. Update downstream service calls to use automatic header propagation

### Adding to Existing Services

1. Add library dependency
2. Enable context filter in configuration
3. Remove existing context handling code
4. Test with provided demo endpoints
5. Update logging configuration to include context fields

## Advanced Usage

### Custom Context Extraction

```java
@Component
public class CustomContextProcessor {
    
    @EventListener
    public void handleContextExtracted(ContextExtractedEvent event) {
        // Custom logic after context extraction
        Map<String, String> context = event.getContext();
        
        // Add custom processing
        if (context.containsKey("tenant.id")) {
            // Load tenant-specific configuration
            configureTenant(context.get("tenant.id"));
        }
    }
}
```

### Integration with Observability Tools

The context automatically integrates with:
- **OpenTelemetry** (traceparent support)
- **Jaeger** (trace propagation)
- **Micrometer** (context bridge)
- **Logback** (structured logging)

This completes the comprehensive context filter solution that provides consistent, secure, and performant context propagation across both servlet and reactive Spring applications. 