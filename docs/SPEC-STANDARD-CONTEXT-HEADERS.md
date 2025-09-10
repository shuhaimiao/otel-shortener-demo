# Specification: Standard Context Headers Propagation

## 1. Executive Summary

This specification defines a standardized approach for propagating business context headers throughout the distributed system, complementing the existing OpenTelemetry traceparent implementation. The goal is to ensure consistent availability of user, tenant, service, and request context across all service boundaries and in all logs.

## 2. Motivation

### 2.1 Current Challenges
- **Debugging Complexity**: Traces show the technical flow but lack business context
- **Multi-tenancy**: No consistent tenant isolation or identification
- **Audit Requirements**: User actions not consistently tracked across services
- **Support Burden**: Hard to correlate customer complaints with system behavior
- **Log Analysis**: Logs lack structured context for filtering and aggregation

### 2.2 Benefits
- **Enhanced Observability**: Every log entry includes full business context
- **Faster MTTR**: Support can quickly filter logs by user/tenant/transaction
- **Compliance**: Audit trail of user actions across all services
- **Multi-tenant Operations**: Easy filtering and isolation by tenant
- **Performance Analysis**: Correlate performance issues with specific users/tenants

## 3. Design Principles

1. **Single Source of Truth**: Context is established once at the BFF layer
2. **Immutable Context**: Headers are read-only after establishment
3. **Consistent Naming**: Standard `X-*` header format across all services
4. **Selective Propagation**: Different boundaries propagate different subsets
5. **Performance Conscious**: Minimize overhead, especially through Kafka
6. **Security First**: Validate at boundaries, sanitize sensitive data
7. **Backward Compatible**: Enhance without breaking existing traceparent flow

## 4. Standard Context Headers

### 4.1 Core Headers (Always Propagated)

| Header | Example | Description | Log Field |
|--------|---------|-------------|-----------|
| `X-Request-ID` | `req-550e8400-e29b` | Unique request identifier | `requestId` |
| `X-User-ID` | `user-123` | User identifier | `userId` |
| `X-Tenant-ID` | `tenant-acme` | Tenant identifier | `tenantId` |
| `X-Service-Name` | `bff` | Originating service | `serviceName` |
| `X-Transaction-Type` | `create-link` | Business operation | `transactionType` |

### 4.2 Extended Headers (HTTP Only)

| Header | Example | Description | Log Field |
|--------|---------|-------------|-----------|
| `X-User-Email` | `john@acme.com` | User email | `userEmail` |
| `X-User-Roles` | `admin,user` | User roles (comma-separated) | `userRoles` |
| `X-User-Groups` | `eng,ops` | User groups | `userGroups` |
| `X-Tenant-Name` | `ACME Corp` | Tenant display name | `tenantName` |
| `X-Tenant-Tier` | `premium` | Service tier | `tenantTier` |
| `X-Session-ID` | `sess-abc123` | Session identifier | `sessionId` |
| `X-Correlation-ID` | `corr-xyz789` | Business correlation | `correlationId` |

### 4.3 Environment Headers (Optional)

| Header | Example | Description | Log Field |
|--------|---------|-------------|-----------|
| `X-Environment` | `production` | Environment name | `environment` |
| `X-Region` | `us-east-1` | Geographic region | `region` |
| `X-Feature-Flags` | `new-ui=true` | Active feature flags | `featureFlags` |
| `X-Client-IP` | `192.168.1.1` | Original client IP | `clientIp` |

## 5. Propagation Boundaries

### 5.1 HTTP Boundaries

**Frontend → BFF**
- BFF establishes all context from JWT/session
- Validates and sanitizes input
- Sets all standard headers

**BFF → Backend Services**
- Forward all Core + Extended headers
- Add `X-Service-Name: bff` to identify origin
- Preserve traceparent for correlation

**Service → Service**
- Forward all received context headers
- Don't modify existing headers
- May add service-specific headers

### 5.2 Kafka/CDC Boundary

**Outbox Table Schema Enhancement**
```sql
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS user_id VARCHAR(255);
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255);
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS request_id VARCHAR(255);
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS transaction_type VARCHAR(255);
-- Already have: trace_id, parent_span_id
```

**Debezium Transformation**
```json
{
  "transforms": "AddHeaders",
  "transforms.AddHeaders.type": "org.apache.kafka.connect.transforms.HeaderFrom$Value",
  "transforms.AddHeaders.fields": "user_id,tenant_id,request_id,transaction_type,trace_id,parent_span_id",
  "transforms.AddHeaders.headers": "X-User-ID,X-Tenant-ID,X-Request-ID,X-Transaction-Type,trace_id,parent_span_id"
}
```

**Kafka Headers**
- Only Core headers + trace context
- Minimize message size
- Headers are more efficient than message body

### 5.3 Database Boundary

**Audit Columns**
```sql
-- Add to all tables for audit trail
ALTER TABLE links ADD COLUMN created_by VARCHAR(255); -- user_id
ALTER TABLE links ADD COLUMN tenant_id VARCHAR(255);
ALTER TABLE clicks ADD COLUMN user_id VARCHAR(255);
ALTER TABLE clicks ADD COLUMN tenant_id VARCHAR(255);
```

## 6. Service Implementation

### 6.1 BFF (Node.js/Express)

**Context Establishment**
```javascript
// middleware/context.js
const establishContext = (req, res, next) => {
  const jwt = extractJWT(req);
  const claims = validateJWT(jwt);
  
  // Core headers (always set)
  req.headers['x-request-id'] = req.headers['x-request-id'] || generateRequestId();
  req.headers['x-user-id'] = claims.sub || 'anonymous';
  req.headers['x-tenant-id'] = claims.tenant_id || 'default';
  req.headers['x-service-name'] = 'bff';
  req.headers['x-transaction-type'] = deriveTransactionType(req);
  
  // Extended headers
  req.headers['x-user-email'] = claims.email;
  req.headers['x-user-roles'] = claims.roles?.join(',');
  req.headers['x-tenant-name'] = claims.tenant_name;
  req.headers['x-session-id'] = req.session?.id;
  
  // Log with context
  logger.info('Request received', {
    requestId: req.headers['x-request-id'],
    userId: req.headers['x-user-id'],
    tenantId: req.headers['x-tenant-id'],
    transactionType: req.headers['x-transaction-type'],
    path: req.path,
    method: req.method
  });
  
  next();
};
```

**Header Forwarding**
```javascript
// Forward context headers to backend services
const forwardHeaders = (req) => {
  const contextHeaders = {};
  Object.keys(req.headers).forEach(key => {
    if (key.startsWith('x-') || key === 'traceparent') {
      contextHeaders[key] = req.headers[key];
    }
  });
  return contextHeaders;
};

// Use in API calls
const response = await axios.get(backendUrl, {
  headers: forwardHeaders(req)
});
```

### 6.2 Java Services (Spring Boot)

**MDC Context Filter Enhancement**
```java
@Component
public class StandardContextFilter implements Filter {
    
    private static final Set<String> CONTEXT_HEADERS = Set.of(
        "X-Request-ID", "X-User-ID", "X-Tenant-ID", 
        "X-Service-Name", "X-Transaction-Type",
        "X-User-Email", "X-User-Roles", "X-Tenant-Name",
        "X-Session-ID", "X-Correlation-ID"
    );
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, 
                        FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        
        try {
            // Extract all context headers to MDC
            CONTEXT_HEADERS.forEach(header -> {
                String value = httpRequest.getHeader(header);
                if (value != null) {
                    String mdcKey = headerToMdcKey(header);
                    MDC.put(mdcKey, value);
                }
            });
            
            // Extract trace context
            String traceparent = httpRequest.getHeader("traceparent");
            if (traceparent != null) {
                extractTraceContext(traceparent);
            }
            
            logger.info("Request received: {} {}", 
                httpRequest.getMethod(), 
                httpRequest.getRequestURI());
            
            chain.doFilter(request, response);
            
        } finally {
            MDC.clear();
        }
    }
    
    private String headerToMdcKey(String header) {
        // X-User-ID -> userId, X-Tenant-ID -> tenantId
        return header.substring(2)
            .replace("-", "")
            .substring(0, 1).toLowerCase() + 
            header.substring(3).replace("-", "");
    }
}
```

**RestTemplate Interceptor**
```java
@Component
public class ContextPropagationInterceptor implements ClientHttpRequestInterceptor {
    
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                       ClientHttpRequestExecution execution) throws IOException {
        HttpHeaders headers = request.getHeaders();
        
        // Forward all context from MDC
        MDC.getCopyOfContextMap().forEach((key, value) -> {
            String header = mdcKeyToHeader(key);
            if (header.startsWith("X-")) {
                headers.add(header, value);
            }
        });
        
        // Ensure traceparent is forwarded (handled by OpenTelemetry)
        
        return execution.execute(request, body);
    }
    
    private String mdcKeyToHeader(String mdcKey) {
        // userId -> X-User-ID, tenantId -> X-Tenant-ID
        return "X-" + mdcKey.replaceAll("([A-Z])", "-$1").toUpperCase();
    }
}
```

**WebClient Configuration**
```java
@Configuration
public class WebClientConfig {
    
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
            .filter((request, next) -> {
                // Add context headers from MDC
                MDC.getCopyOfContextMap().forEach((key, value) -> {
                    String header = mdcKeyToHeader(key);
                    if (header.startsWith("X-")) {
                        request.headers().add(header, value);
                    }
                });
                return next.exchange(request);
            })
            .build();
    }
}
```

### 6.3 URL API - Outbox Integration

```java
@Service
public class LinkService {
    
    @Transactional
    public ShortLink createLink(String longUrl) {
        // Create link with audit fields
        Link link = new Link();
        link.setLongUrl(longUrl);
        link.setShortCode(generateShortCode());
        link.setCreatedBy(MDC.get("userId"));
        link.setTenantId(MDC.get("tenantId"));
        linkRepository.save(link);
        
        // Create outbox event with context
        OutboxEvent event = new OutboxEvent();
        event.setEventType("LINK_CREATED");
        event.setPayload(toJson(link));
        
        // Add context for Kafka propagation
        event.setUserId(MDC.get("userId"));
        event.setTenantId(MDC.get("tenantId"));
        event.setRequestId(MDC.get("requestId"));
        event.setTransactionType(MDC.get("transactionType"));
        event.setTraceId(MDC.get("traceId"));
        event.setParentSpanId(MDC.get("spanId"));
        
        outboxRepository.save(event);
        
        logger.info("Link created: {}", link.getShortCode());
        
        return link;
    }
}
```

### 6.4 Analytics API - Kafka Context Extraction

```java
@Component
public class KafkaContextExtractor {
    
    @KafkaListener(topics = "link-events")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            // Extract context from Kafka headers
            Headers headers = record.headers();
            extractHeader(headers, "X-User-ID", "userId");
            extractHeader(headers, "X-Tenant-ID", "tenantId");
            extractHeader(headers, "X-Request-ID", "requestId");
            extractHeader(headers, "X-Transaction-Type", "transactionType");
            
            // Extract trace context for span creation
            String traceId = extractHeaderValue(headers, "trace_id");
            String parentSpanId = extractHeaderValue(headers, "parent_span_id");
            
            // Process with full context
            logger.info("Processing event from Kafka");
            processEvent(record.value());
            
        } finally {
            MDC.clear();
        }
    }
    
    private void extractHeader(Headers headers, String headerName, String mdcKey) {
        Header header = headers.lastHeader(headerName);
        if (header != null) {
            String value = new String(header.value(), StandardCharsets.UTF_8);
            MDC.put(mdcKey, value);
        }
    }
}
```

## 7. Logging Configuration

### 7.1 Logback Pattern (Java Services)
```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>
                %d{ISO8601} [%thread] %-5level %logger{36} - %msg 
                [userId=%X{userId}] [tenantId=%X{tenantId}] 
                [requestId=%X{requestId}] [traceId=%X{traceId}] 
                [transactionType=%X{transactionType}]%n
            </pattern>
        </encoder>
    </appender>
    
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>userId</includeMdcKeyName>
            <includeMdcKeyName>tenantId</includeMdcKeyName>
            <includeMdcKeyName>requestId</includeMdcKeyName>
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>transactionType</includeMdcKeyName>
            <includeMdcKeyName>serviceName</includeMdcKeyName>
        </encoder>
    </appender>
</configuration>
```

### 7.2 Node.js Logging (BFF)
```javascript
const winston = require('winston');

const logger = winston.createLogger({
  format: winston.format.combine(
    winston.format.timestamp(),
    winston.format.errors({ stack: true }),
    winston.format.json()
  ),
  defaultMeta: { service: 'bff' },
  transports: [
    new winston.transports.Console({
      format: winston.format.printf(info => {
        const { timestamp, level, message, ...context } = info;
        const contextStr = Object.entries(context)
          .map(([k, v]) => `[${k}=${v}]`)
          .join(' ');
        return `${timestamp} ${level}: ${message} ${contextStr}`;
      })
    })
  ]
});
```

## 8. Testing Strategy

### 8.1 Unit Tests
- Test context extraction from headers
- Test MDC population
- Test header forwarding in HTTP clients
- Test Kafka header extraction

### 8.2 Integration Tests
```bash
# Test full context flow
curl -X POST http://localhost:3001/api/links \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "X-Request-ID: test-req-123" \
  -d '{"url": "https://example.com"}'

# Verify in logs:
# - BFF logs show context establishment
# - URL API logs show received context
# - Analytics API logs show context from Kafka
```

### 8.3 Verification Checklist
- [ ] Context headers present in all service logs
- [ ] Headers forwarded in service-to-service calls
- [ ] Context preserved through Kafka/CDC
- [ ] MDC cleared after request completion
- [ ] Sensitive data not logged
- [ ] Performance impact < 5ms per request

## 9. Security Considerations

### 9.1 Input Validation
- Validate header values for injection attacks
- Limit header value length (max 255 chars)
- Sanitize special characters in log output

### 9.2 Sensitive Data
- Don't log `X-User-Email` in production
- Consider hashing user IDs for privacy
- Rotate session IDs regularly

### 9.3 Access Control
- Headers are informational only
- Services must validate authorization separately
- Don't trust client-provided context headers

## 10. Migration Plan

### Phase 1: Foundation (Week 1)
1. Update BFF context middleware
2. Enhance Java MDC filters
3. Standardize logging configuration
4. Deploy and verify basic flow

### Phase 2: HTTP Propagation (Week 2)
1. Add RestTemplate interceptor
2. Configure WebClient filter
3. Test service-to-service calls
4. Verify context in all logs

### Phase 3: Kafka Propagation (Week 3)
1. Update outbox table schema
2. Configure Debezium transformations
3. Update analytics-api consumer
4. Test end-to-end flow

### Phase 4: Monitoring (Week 4)
1. Create Grafana dashboards by tenant/user
2. Set up alerts for context loss
3. Performance testing
4. Documentation and training

## 11. Success Metrics

- **Context Coverage**: 100% of requests have full context
- **Log Correlation**: Can trace any request across all services
- **Performance**: < 5ms overhead per request
- **Kafka Overhead**: < 1KB additional headers per message
- **Developer Satisfaction**: Reduced debugging time by 50%

## 12. Future Enhancements

1. **Dynamic Context**: Add runtime feature flags to context
2. **Context Sampling**: Reduce context for high-volume endpoints
3. **External Services**: Propagate context to third-party APIs
4. **Metrics Integration**: Add context labels to Prometheus metrics
5. **Distributed Cache**: Cache user/tenant context in Redis

## Appendix A: Header Reference

```yaml
# Complete header inventory
core:
  - X-Request-ID        # Always required
  - X-User-ID          # Always required
  - X-Tenant-ID        # Always required
  - X-Service-Name     # Always required
  - X-Transaction-Type # Always required

extended:
  - X-User-Email       # PII - handle carefully
  - X-User-Roles       # Comma-separated list
  - X-User-Groups      # Comma-separated list
  - X-Tenant-Name      # Human-readable
  - X-Tenant-Tier      # free|standard|premium|enterprise
  - X-Session-ID       # User session
  - X-Correlation-ID   # Business correlation

environment:
  - X-Environment      # dev|staging|prod
  - X-Region          # us-east-1|eu-west-1|ap-south-1
  - X-Feature-Flags   # key=value pairs
  - X-Client-IP       # Original client IP

technical:
  - traceparent       # W3C Trace Context (existing)
  - tracestate        # W3C Trace State (optional)
```

## Appendix B: Example Logs

**BFF Log**
```json
{
  "timestamp": "2025-01-10T10:30:45.123Z",
  "level": "info",
  "message": "Request received",
  "service": "bff",
  "requestId": "req-550e8400-e29b",
  "userId": "user-123",
  "tenantId": "tenant-acme",
  "transactionType": "create-link",
  "method": "POST",
  "path": "/api/links"
}
```

**URL API Log**
```
2025-01-10 10:30:45.234 [http-nio-8080-exec-1] INFO  c.e.u.LinkController - Creating short link 
[userId=user-123] [tenantId=tenant-acme] [requestId=req-550e8400-e29b] 
[traceId=abc123def456] [transactionType=create-link]
```

**Analytics API Log (from Kafka)**
```
2025-01-10 10:30:46.345 [kafka-consumer-1] INFO  c.e.a.EventProcessor - Processing LINK_CREATED event 
[userId=user-123] [tenantId=tenant-acme] [requestId=req-550e8400-e29b] 
[traceId=abc123def456] [transactionType=create-link]
```