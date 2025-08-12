# Design Specification: E2E Traceability Enhancements (Revised)

## 1. Executive Summary

This document presents the simplified technical design for enhancing the otel-shortener-demo with end-to-end distributed tracing capabilities, based on desktop review decisions. The design focuses on essential components for demonstrating trace context propagation without unnecessary complexity.

## 2. Simplified Architecture Overview

### 2.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         Internet                             │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                 SPA (React/Next.js)                          │
│           • Initiates traceparent header                     │
│           • OpenTelemetry JS SDK                             │
└──────────────────────────┬──────────────────────────────────┘
                           │ traceparent
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    NGINX (Edge Layer)                        │
│  • HTTP routing  • Trace initiation if missing               │
│  • OpenTelemetry Module  • Basic rate limiting               │
└──────────────────────────┬──────────────────────────────────┘
                           │ traceparent
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                      BFF (Node.js)                           │
│  • Token validation (placeholder)  • Context establishment   │
│  • Redis caching  • Header creation  • API aggregation       │
└─────────┬───────────────────────────────────┬───────────────┘
          │ + context headers                  │
          ▼                                   ▼
┌──────────────────┐                ┌──────────────────┐
│   URL API (Java) │                │ Redirect Service │
│   Spring Boot    │                │   Spring WebFlux │
└────────┬─────────┘                └────────┬─────────┘
         │                                    │
         ▼                                    ▼
┌─────────────────────────────────────────────────────────────┐
│                     PostgreSQL                               │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│                      Kafka                                   │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                 Analytics API (Java)                         │
│           • Kafka consumer  • @Scheduled job                 │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│                    Redis (Cache)                             │
│              • Token claims  • Popular links                 │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│              OpenTelemetry Collector                         │
│  • Receive  • Process  • Export to Jaeger                    │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Trace Flow Architecture

```yaml
Trace Initiation:
  Primary: SPA generates traceparent
  Fallback: NGINX generates if missing

Context Establishment:
  Location: BFF after token validation
  Headers Created:
    - X-Tenant-ID
    - X-User-ID
    - X-Service-Name
    - X-Transaction-Name

Propagation Points:
  - HTTP: Headers with traceparent
  - Kafka: Message headers
  - Database: SQL comments
  - Redis: Span attributes
  - Scheduled Jobs: New root spans
```

## 3. Component Design

### 3.1 SPA Trace Initiation

#### 3.1.1 OpenTelemetry Web SDK Setup

```javascript
// frontend/src/tracing.js
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { W3CTraceContextPropagator } from '@opentelemetry/core';
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace-base';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';

// Initialize provider
const provider = new WebTracerProvider({
  resource: new Resource({
    'service.name': 'frontend-spa',
    'service.version': '1.0.0',
  }),
});

// Configure exporter
const exporter = new OTLPTraceExporter({
  url: 'http://localhost:4318/v1/traces', // Collector HTTP endpoint
});

provider.addSpanProcessor(new BatchSpanProcessor(exporter));

// Register instrumentations
registerInstrumentations({
  instrumentations: [
    new FetchInstrumentation({
      propagateTraceHeaderCorsUrls: [
        'http://localhost',  // Include all backend URLs
      ],
    }),
  ],
});

provider.register({
  propagator: new W3CTraceContextPropagator(),
});
```

### 3.2 NGINX with OpenTelemetry (Simplified)

#### 3.2.1 Minimal Configuration

```nginx
# nginx/nginx.conf
load_module modules/ngx_http_opentelemetry_module.so;

events {
    worker_connections 1024;
}

http {
    opentelemetry_config /etc/nginx/otel-nginx.toml;
    
    # Simple rate limiting
    limit_req_zone $binary_remote_addr zone=api_limit:10m rate=10r/s;
    
    server {
        listen 80;
        server_name localhost;
        
        # OpenTelemetry context propagation
        opentelemetry_operation_name "nginx.$request_method.$uri";
        opentelemetry_propagate;
        
        # Static files
        location /static/ {
            root /usr/share/nginx/html;
            expires 1h;
        }
        
        # API routes to BFF
        location /api/ {
            proxy_pass http://bff:3001;
            proxy_http_version 1.1;
            
            # Preserve trace headers
            proxy_set_header traceparent $http_traceparent;
            proxy_set_header tracestate $http_tracestate;
            proxy_set_header Host $host;
            
            # Rate limiting
            limit_req zone=api_limit burst=5 nodelay;
            
            # Add trace attributes
            opentelemetry_attribute "http.target" $uri;
            opentelemetry_attribute "http.method" $request_method;
        }
        
        # Health check
        location /health {
            access_log off;
            return 200 "healthy\n";
        }
    }
}
```

#### 3.2.2 Docker Configuration

```dockerfile
# nginx/Dockerfile
FROM nginx:1.25-alpine

# Use pre-built OpenTelemetry module or build minimal version
COPY --from=otel/nginx-module:latest /usr/lib/nginx/modules/ngx_http_opentelemetry_module.so \
     /usr/lib/nginx/modules/

COPY nginx.conf /etc/nginx/nginx.conf
COPY otel-nginx.toml /etc/nginx/otel-nginx.toml
```

### 3.3 BFF Context Establishment

#### 3.3.1 Token Processing and Context Creation

```javascript
// bff/middleware/context.js
const { trace } = require('@opentelemetry/api');

// Placeholder token validation (OAuth would go here)
const validateAndExtractClaims = (token) => {
  // In production: validate with Keycloak
  // For demo: return mock claims
  return {
    sub: 'user-123',
    tenant_id: 'tenant-456',
    email: 'user@example.com',
    groups: ['users', 'admins'],
    exp: Date.now() + (15 * 60 * 1000) // 15 minutes
  };
};

// Context establishment middleware
const establishContext = async (req, res, next) => {
  const span = trace.getActiveSpan();
  
  // Extract token (placeholder for demo)
  const token = req.headers.authorization?.replace('Bearer ', '');
  
  if (token) {
    // Check cache first
    const cacheKey = `token:${token.substring(0, 10)}`; // Use token prefix as key
    let claims = await redisClient.get(cacheKey);
    
    if (!claims) {
      // Validate and extract claims
      claims = validateAndExtractClaims(token);
      
      // Cache with TTL matching token expiration
      const ttl = Math.floor((claims.exp - Date.now()) / 1000);
      await redisClient.setex(cacheKey, ttl, JSON.stringify(claims));
      
      span?.setAttribute('cache.hit', false);
    } else {
      claims = JSON.parse(claims);
      span?.setAttribute('cache.hit', true);
    }
    
    // Establish standard headers
    req.headers['X-Tenant-ID'] = claims.tenant_id || 'default';
    req.headers['X-User-ID'] = claims.sub;
    req.headers['X-Service-Name'] = 'bff';
    req.headers['X-Transaction-Name'] = `${req.method} ${req.path}`;
    
    // Add to trace span
    span?.setAttributes({
      'user.id': claims.sub,
      'user.tenant_id': claims.tenant_id,
      'user.email': claims.email,
      'service.name': 'bff',
      'transaction.name': `${req.method} ${req.path}`
    });
    
    // Store in request for downstream use
    req.userContext = claims;
  }
  
  next();
};

module.exports = { establishContext };
```

### 3.4 Redis Caching (Simplified)

#### 3.4.1 Docker Setup

```yaml
# docker-compose addition for Redis
redis:
  image: redis:7-alpine
  container_name: otel-redis
  command: redis-server --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru
  ports:
    - "6379:6379"
  volumes:
    - redis-data:/data
  networks:
    - otel-network
```

#### 3.4.2 Traced Redis Operations

```javascript
// bff/redis-client.js
const redis = require('redis');
const { trace } = require('@opentelemetry/api');

class TracedRedisClient {
  constructor() {
    this.client = redis.createClient({
      url: 'redis://redis:6379'
    });
    this.tracer = trace.getTracer('redis-cache');
  }
  
  async get(key) {
    const span = this.tracer.startSpan('cache.get', {
      attributes: {
        'cache.operation': 'get',
        'cache.key': key
      }
    });
    
    try {
      const value = await this.client.get(key);
      span.setAttribute('cache.hit', value !== null);
      return value;
    } finally {
      span.end();
    }
  }
  
  async setex(key, ttl, value) {
    const span = this.tracer.startSpan('cache.set', {
      attributes: {
        'cache.operation': 'set',
        'cache.key': key,
        'cache.ttl': ttl
      }
    });
    
    try {
      return await this.client.setex(key, ttl, value);
    } finally {
      span.end();
    }
  }
}

module.exports = new TracedRedisClient();
```

### 3.5 Scheduled Job with Trace Propagation

#### 3.5.1 Simple @Scheduled Implementation

```java
// analytics-api/src/main/java/com/demo/analytics/jobs/ScheduledJobs.java
@Component
public class ScheduledJobs {
    private static final Logger logger = LoggerFactory.getLogger(ScheduledJobs.class);
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("scheduler");
    
    @Autowired
    private LinkRepository linkRepository;
    
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    
    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.MINUTES)
    public void cleanupExpiredLinks() {
        // Create new root span for scheduled job
        Span span = tracer.spanBuilder("scheduled.job.cleanup_links")
            .setAttribute("job.type", "scheduled")
            .setAttribute("job.name", "cleanup_expired_links")
            .setAttribute("job.scheduled", true)
            .startSpan();
        
        // Make span current for MDC
        try (Scope scope = span.makeCurrent()) {
            // MDC will be populated from span
            MDC.put("trace_id", span.getSpanContext().getTraceId());
            MDC.put("job_name", "cleanup_expired_links");
            
            logger.info("Starting scheduled link cleanup job");
            
            // Find and process expired links
            List<Link> expiredLinks = linkRepository.findByExpiresAtBefore(LocalDateTime.now());
            
            for (Link link : expiredLinks) {
                // Create event with trace context
                LinkExpiredEvent event = new LinkExpiredEvent(
                    link.getShortCode(),
                    link.getUserId(),
                    link.getCreatedAt()
                );
                
                // Create Kafka record with trace context in headers
                ProducerRecord<String, Object> record = new ProducerRecord<>(
                    "link-events",
                    link.getShortCode(),
                    event
                );
                
                // Add trace context to Kafka headers
                record.headers()
                    .add("traceparent", span.getSpanContext().toString().getBytes())
                    .add("tenant_id", link.getTenantId().getBytes())
                    .add("user_id", link.getUserId().getBytes());
                
                kafkaTemplate.send(record);
            }
            
            span.setAttribute("job.items_processed", expiredLinks.size());
            logger.info("Processed {} expired links", expiredLinks.size());
            
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            logger.error("Error in cleanup job", e);
        } finally {
            span.end();
            MDC.clear();
        }
    }
}
```

### 3.6 MDC Integration for Java Services

#### 3.6.1 Spring Interceptor for MDC

```java
// shared/src/main/java/com/demo/shared/MDCInterceptor.java
@Component
public class MDCInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                           HttpServletResponse response, 
                           Object handler) {
        // Extract context headers and populate MDC
        MDC.put("trace_id", Span.current().getSpanContext().getTraceId());
        MDC.put("tenant_id", request.getHeader("X-Tenant-ID"));
        MDC.put("user_id", request.getHeader("X-User-ID"));
        MDC.put("service_name", request.getHeader("X-Service-Name"));
        MDC.put("transaction_name", request.getHeader("X-Transaction-Name"));
        
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request,
                               HttpServletResponse response, 
                               Object handler, 
                               Exception ex) {
        MDC.clear();
    }
}
```

### 3.7 Centralized Logging Configuration

#### 3.7.1 Shared Volume Setup

```yaml
# docker-compose.yml
volumes:
  shared-logs:
    driver: local

services:
  bff:
    volumes:
      - shared-logs:/var/log/app
    environment:
      - LOG_FILE=/var/log/app/application.log

  url-api:
    volumes:
      - shared-logs:/var/log/app
    environment:
      - LOG_FILE=/var/log/app/application.log
      
  analytics-api:
    volumes:
      - shared-logs:/var/log/app
    environment:
      - LOG_FILE=/var/log/app/application.log
```

#### 3.7.2 Java Logback Configuration

```xml
<!-- logback.xml for Java services -->
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_FILE:-/var/log/app/application.log}</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeBasedRollingPolicy">
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>5</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{ISO8601} [%X{service_name}] [%X{trace_id}] [%X{tenant_id}] [%X{user_id}] %level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="FILE" />
    </root>
</configuration>
```

#### 3.7.3 Node.js Winston Configuration

```javascript
// bff/logger.js
const winston = require('winston');
const { trace } = require('@opentelemetry/api');

const logger = winston.createLogger({
  format: winston.format.combine(
    winston.format.timestamp(),
    winston.format.printf(({ timestamp, level, message, ...meta }) => {
      const span = trace.getActiveSpan();
      const traceId = span?.spanContext().traceId || 'no-trace';
      
      return `${timestamp} [bff] [${traceId}] [${meta.tenant_id}] [${meta.user_id}] ${level}: ${message}`;
    })
  ),
  transports: [
    new winston.transports.File({ 
      filename: process.env.LOG_FILE || '/var/log/app/application.log',
      maxsize: 100000000, // 100MB
      maxFiles: 5
    })
  ]
});

module.exports = logger;
```

## 4. Data Flow Design

### 4.1 Complete Trace Flow

```
1. User clicks "Create Short URL" in SPA
   - SPA generates traceparent header
   - Sends POST /api/links with Bearer token

2. Request hits NGINX
   - Validates traceparent exists (or creates if missing)
   - Adds basic span attributes
   - Forwards to BFF

3. BFF processes request
   - Validates token (placeholder)
   - Checks Redis cache for claims
   - Establishes context headers
   - Forwards to URL API with context

4. URL API creates link
   - MDC populated from headers
   - Database operation traced
   - Sends event to Kafka with trace context

5. Analytics API consumes event
   - Extracts trace context from Kafka headers
   - Continues trace
   - Updates analytics

6. Scheduled job runs (every 30 min)
   - Creates new root span
   - Queries expired links
   - Sends events to Kafka with trace context
```

### 4.2 Context Headers Flow

```yaml
SPA → NGINX:
  - traceparent: 00-trace-id-span-id-01
  - Authorization: Bearer token

NGINX → BFF:
  - traceparent: (forwarded)
  - Authorization: (forwarded)

BFF → Services:
  - traceparent: (forwarded)
  - X-Tenant-ID: tenant-456
  - X-User-ID: user-123
  - X-Service-Name: bff
  - X-Transaction-Name: POST /api/links

Services → Kafka:
  Headers:
    - traceparent: (current span context)
    - tenant_id: tenant-456
    - user_id: user-123
```

## 5. Deployment Architecture

### 5.1 Docker Compose Structure

```yaml
version: '3.8'

networks:
  otel-network:
    driver: bridge

volumes:
  postgres-data:
  redis-data:
  kafka-data:
  shared-logs:

services:
  # Frontend
  frontend:
    build: ./frontend
    environment:
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
    networks:
      - otel-network

  # Edge Layer
  nginx:
    build: ./nginx
    ports:
      - "80:80"
    depends_on:
      - bff
      - otel-collector
    networks:
      - otel-network

  # BFF with Context Establishment
  bff:
    build: ./bff
    environment:
      - REDIS_URL=redis://redis:6379
      - LOG_FILE=/var/log/app/application.log
    volumes:
      - shared-logs:/var/log/app
    depends_on:
      - redis
    networks:
      - otel-network

  # Redis Cache
  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes
    volumes:
      - redis-data:/data
    networks:
      - otel-network

  # Java Services
  url-api:
    build: ./url-api
    environment:
      - JAVA_TOOL_OPTIONS=-javaagent:/opentelemetry-javaagent.jar
      - OTEL_SERVICE_NAME=url-api
      - LOG_FILE=/var/log/app/application.log
    volumes:
      - shared-logs:/var/log/app
    networks:
      - otel-network

  analytics-api:
    build: ./analytics-api
    environment:
      - JAVA_TOOL_OPTIONS=-javaagent:/opentelemetry-javaagent.jar
      - OTEL_SERVICE_NAME=analytics-api
      - LOG_FILE=/var/log/app/application.log
    volumes:
      - shared-logs:/var/log/app
    networks:
      - otel-network

  # Existing services (postgres, kafka, etc.)
  # ... unchanged ...
```

## 6. Testing Strategy

### 6.1 Trace Validation Tests

```java
@Test
public void testEndToEndTracePropagation() {
    // Generate trace ID
    String traceId = generateTraceId();
    
    // Make request with trace
    Response response = given()
        .header("traceparent", "00-" + traceId + "-0000000000000001-01")
        .header("Authorization", "Bearer mock-token")
        .body("{\"url\": \"https://example.com\"}")
        .post("/api/links");
    
    // Verify trace appears in:
    // 1. NGINX logs
    // 2. BFF spans
    // 3. URL API spans
    // 4. Kafka headers
    // 5. Analytics API spans
    
    assertTraceComplete(traceId);
}
```

## 7. Documentation Patterns (Not Implemented)

### 7.1 External API Calls (Example Pattern)

```java
// How external calls would work (not implemented)
@Component
public class ExternalAPIClient {
    @Autowired
    private RestTemplate restTemplate; // Auto-instrumented by OTel
    
    public void callExternalAPI() {
        // Trace context automatically added to headers
        String result = restTemplate.getForObject(
            "https://external-api.com/data", 
            String.class
        );
        // Span automatically created with external service info
    }
}
```

### 7.2 OAuth 2.0 Client Credentials (Example Pattern)

```javascript
// How real OAuth would work (not implemented)
async function getM2MToken() {
  const span = tracer.startSpan('oauth.token_request');
  
  try {
    const response = await fetch('http://keycloak:8080/token', {
      method: 'POST',
      body: new URLSearchParams({
        grant_type: 'client_credentials',
        client_id: process.env.CLIENT_ID,
        client_secret: process.env.CLIENT_SECRET
      })
    });
    
    const token = await response.json();
    span.setAttribute('oauth.client_id', process.env.CLIENT_ID);
    return token.access_token;
  } finally {
    span.end();
  }
}
```

---

**Document Version**: 2.0 (Revised)  
**Last Updated**: 2024-12-12  
**Status**: Approved for Implementation  
**Changes**: Simplified architecture based on desktop review decisions