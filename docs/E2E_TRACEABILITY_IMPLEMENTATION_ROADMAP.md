# End-to-End Traceability Implementation Roadmap

## Executive Summary

Based on the Architecture Evolution Guide and Observability Adoption Guide, this roadmap outlines the implementation plan to evolve the otel-shortener-demo from its current **Stage 2 (Growth)** architecture to a comprehensive **Stage 3 (Scale)** demonstration platform with enterprise-grade observability.

## Current State Assessment

### Architecture Alignment (Stage 2 - Growth)
✅ **What We Have:**
- BFF pattern implemented (Node.js)
- Separate API services (Java Spring Boot)
- PostgreSQL database
- Kafka messaging
- Keycloak authentication
- Docker Compose orchestration
- Basic service separation

❌ **What's Missing for Stage 3:**
- **NGINX** edge layer (SSL termination, load balancing)
- **API Gateway** (Kong/Traefik for dynamic routing)
- **CDN** simulation (static asset caching)
- **Redis** caching layer
- **Service Mesh** concepts (circuit breaking, retries)
- **Multiple BFFs** for different clients
- **Advanced deployment patterns** (canary, blue-green)

### Observability Alignment
✅ **What We Have:**
- W3C Trace Context (traceparent headers)
- OpenTelemetry Collector
- Jaeger for trace visualization
- Auto-instrumentation for Java services
- Kafka trace propagation
- Database query tracing

❌ **What's Missing:**
- **Edge tracing** (NGINX with OTel module)
- **API Gateway tracing** (rate limiting, auth traces)
- **Cache operation tracing** (Redis spans)
- **Background job tracing**
- **WebSocket tracing** (real-time connections)
- **External API simulation** (third-party traces)
- **Webhook endpoints** (inbound event tracing)
- **Advanced sampling strategies**
- **Multi-tenant trace isolation**

## Implementation Roadmap

### Phase 1: Edge Layer & Load Balancing (Week 1)
**Goal:** Add NGINX as the edge layer with full OpenTelemetry instrumentation

#### 1.1 NGINX with OpenTelemetry Module

```yaml
# docker-compose.additions.yml
services:
  nginx:
    build:
      context: ./nginx
      dockerfile: Dockerfile.otel
    container_name: otel-nginx
    ports:
      - "80:80"
      - "443:443"
    environment:
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
      - OTEL_SERVICE_NAME=nginx-edge
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf
      - ./nginx/certs:/etc/nginx/certs
      - ./frontend/dist:/usr/share/nginx/html
    depends_on:
      - bff
      - otel-collector
    networks:
      - otel-network
```

```dockerfile
# nginx/Dockerfile.otel
FROM nginx:1.25-alpine

# Install OpenTelemetry module
RUN apk add --no-cache \
    wget \
    build-base \
    pcre-dev \
    zlib-dev \
    openssl-dev \
    linux-headers

# Download and compile nginx with OpenTelemetry module
RUN cd /tmp && \
    wget https://nginx.org/download/nginx-1.25.3.tar.gz && \
    tar -xzvf nginx-1.25.3.tar.gz && \
    git clone --shallow-submodules --depth 1 --recurse-submodules \
      --branch v0.1.0 \
      https://github.com/open-telemetry/opentelemetry-cpp-contrib.git && \
    cd nginx-1.25.3 && \
    ./configure --with-compat --add-dynamic-module=/tmp/opentelemetry-cpp-contrib/instrumentation/nginx && \
    make modules && \
    cp objs/ngx_http_opentelemetry_module.so /usr/lib/nginx/modules/

COPY nginx.conf /etc/nginx/nginx.conf
COPY otel_module.conf /etc/nginx/modules/otel.conf
```

```nginx
# nginx/nginx.conf
load_module modules/ngx_http_opentelemetry_module.so;

events {
    worker_connections 1024;
}

http {
    opentelemetry_config /etc/nginx/modules/otel.conf;
    
    # Rate limiting zones
    limit_req_zone $binary_remote_addr zone=api_limit:10m rate=10r/s;
    limit_req_zone $binary_remote_addr zone=static_limit:10m rate=50r/s;
    
    # Upstream configuration with load balancing
    upstream bff_backend {
        least_conn;
        server bff:3001 max_fails=3 fail_timeout=30s;
        # Future: Add more BFF instances for load balancing demo
        # server bff-2:3001 max_fails=3 fail_timeout=30s;
    }
    
    # Cache configuration
    proxy_cache_path /var/cache/nginx levels=1:2 keys_zone=static_cache:10m max_size=100m inactive=60m;
    
    server {
        listen 80;
        listen 443 ssl http2;
        server_name localhost;
        
        # SSL configuration
        ssl_certificate /etc/nginx/certs/server.crt;
        ssl_certificate_key /etc/nginx/certs/server.key;
        
        # Security headers
        add_header X-Frame-Options "SAMEORIGIN" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header X-XSS-Protection "1; mode=block" always;
        
        # OpenTelemetry context propagation
        opentelemetry_operation_name "nginx.$request_method.$uri";
        opentelemetry_propagate;
        
        # Static files with caching (CDN simulation)
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2)$ {
            root /usr/share/nginx/html;
            expires 1y;
            add_header Cache-Control "public, immutable";
            
            # Add trace attributes
            opentelemetry_attribute "http.target" $uri;
            opentelemetry_attribute "cache.status" $upstream_cache_status;
            
            # Enable caching
            proxy_cache static_cache;
            proxy_cache_valid 200 1y;
            proxy_cache_use_stale error timeout updating;
            
            # Rate limiting for static assets
            limit_req zone=static_limit burst=20 nodelay;
        }
        
        # API routes with load balancing
        location /api/ {
            proxy_pass http://bff_backend;
            proxy_http_version 1.1;
            
            # Preserve trace headers
            proxy_set_header traceparent $http_traceparent;
            proxy_set_header tracestate $http_tracestate;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header Host $host;
            
            # Rate limiting for API
            limit_req zone=api_limit burst=5 nodelay;
            limit_req_status 429;
            
            # Add trace attributes
            opentelemetry_attribute "upstream.address" $upstream_addr;
            opentelemetry_attribute "upstream.status" $upstream_status;
            opentelemetry_attribute "upstream.response_time" $upstream_response_time;
        }
        
        # Health check endpoint
        location /health {
            access_log off;
            add_header Content-Type text/plain;
            return 200 "healthy\n";
        }
        
        # Metrics endpoint for monitoring
        location /metrics {
            stub_status;
            access_log off;
        }
    }
}
```

### Phase 2: API Gateway Layer (Week 1-2)
**Goal:** Add Kong API Gateway for advanced routing and API management

#### 2.1 Kong API Gateway Setup

```yaml
# docker-compose.additions.yml (continued)
services:
  kong-database:
    image: postgres:13-alpine
    container_name: kong-db
    environment:
      POSTGRES_DB: kong
      POSTGRES_USER: kong
      POSTGRES_PASSWORD: kongpass
    volumes:
      - kong-db-data:/var/lib/postgresql/data
    networks:
      - otel-network

  kong-migration:
    image: kong:3.5
    container_name: kong-migration
    command: kong migrations bootstrap
    environment:
      KONG_DATABASE: postgres
      KONG_PG_HOST: kong-database
      KONG_PG_USER: kong
      KONG_PG_PASSWORD: kongpass
    depends_on:
      - kong-database
    networks:
      - otel-network

  kong:
    image: kong:3.5
    container_name: otel-kong
    ports:
      - "8000:8000"  # Proxy port
      - "8443:8443"  # Proxy SSL port
      - "8001:8001"  # Admin API port
    environment:
      KONG_DATABASE: postgres
      KONG_PG_HOST: kong-database
      KONG_PG_USER: kong
      KONG_PG_PASSWORD: kongpass
      KONG_PROXY_ACCESS_LOG: /dev/stdout
      KONG_ADMIN_ACCESS_LOG: /dev/stdout
      KONG_PROXY_ERROR_LOG: /dev/stderr
      KONG_ADMIN_ERROR_LOG: /dev/stderr
      KONG_ADMIN_LISTEN: 0.0.0.0:8001
      # OpenTelemetry configuration
      KONG_TRACING_INSTRUMENTATIONS: all
      KONG_TRACING_SAMPLING_RATE: 1.0
      KONG_OPENTELEMETRY_TRACING_ENDPOINT: http://otel-collector:4317
    depends_on:
      - kong-database
      - kong-migration
      - otel-collector
    networks:
      - otel-network
    volumes:
      - ./kong/plugins:/usr/local/share/lua/5.1/kong/plugins
      - ./kong/declarative-config.yml:/usr/local/kong/declarative/kong.yml
```

```yaml
# kong/declarative-config.yml
_format_version: "3.0"

services:
  - name: bff-service
    url: http://bff:3001
    routes:
      - name: bff-route
        paths:
          - /api
        strip_path: false
    plugins:
      - name: opentelemetry
        config:
          endpoint: "http://otel-collector:4317"
          resource_attributes:
            service.name: "kong-gateway"
      - name: rate-limiting
        config:
          minute: 60
          hour: 1000
          policy: local
      - name: cors
        config:
          origins:
            - "*"
          headers:
            - Accept
            - Accept-Version
            - Content-Length
            - Content-MD5
            - Content-Type
            - Date
            - X-Auth-Token
            - traceparent
            - tracestate
      - name: request-transformer
        config:
          add:
            headers:
              - "X-Gateway-Version:1.0"
              - "X-Kong-Proxy:true"

  - name: url-api-service
    url: http://url-api:8080
    routes:
      - name: url-api-route
        paths:
          - /internal/api/urls
        strip_path: true
    plugins:
      - name: opentelemetry
      - name: key-auth
      - name: circuit-breaker
        config:
          error_threshold: 50
          volume_threshold: 100
          timeout: 10
          half_open_timeout: 30

  - name: redirect-service
    url: http://redirect-service:8081
    routes:
      - name: redirect-route
        paths:
          - /r
        strip_path: false
    plugins:
      - name: opentelemetry
      - name: response-ratelimiting
        config:
          limits:
            limit_name:
              minute: 1000

plugins:
  - name: prometheus
    config:
      per_consumer: true
```

### Phase 3: Caching Layer (Week 2)
**Goal:** Add Redis for distributed caching with full tracing

#### 3.1 Redis with OpenTelemetry

```yaml
# docker-compose.additions.yml (continued)
services:
  redis-master:
    image: redis:7-alpine
    container_name: otel-redis-master
    command: redis-server --appendonly yes --requirepass redis123
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    networks:
      - otel-network

  redis-replica:
    image: redis:7-alpine
    container_name: otel-redis-replica
    command: redis-server --replicaof redis-master 6379 --masterauth redis123 --requirepass redis123
    depends_on:
      - redis-master
    networks:
      - otel-network

  redis-sentinel:
    image: redis:7-alpine
    container_name: otel-redis-sentinel
    command: redis-sentinel /etc/redis/sentinel.conf
    volumes:
      - ./redis/sentinel.conf:/etc/redis/sentinel.conf
    depends_on:
      - redis-master
      - redis-replica
    networks:
      - otel-network
```

```javascript
// bff/redis-tracing.js
const redis = require('redis');
const { trace } = require('@opentelemetry/api');

class TracedRedisClient {
    constructor() {
        this.client = redis.createClient({
            url: 'redis://redis123@redis-master:6379',
            socket: {
                reconnectStrategy: (retries) => Math.min(retries * 100, 3000)
            }
        });
        
        this.tracer = trace.getTracer('redis-cache');
        this.setupTracing();
    }

    setupTracing() {
        // Wrap Redis commands with OpenTelemetry spans
        const commands = ['get', 'set', 'del', 'expire', 'ttl', 'exists'];
        
        commands.forEach(cmd => {
            const original = this.client[cmd].bind(this.client);
            
            this.client[cmd] = async (...args) => {
                const span = this.tracer.startSpan(`redis.${cmd}`, {
                    attributes: {
                        'db.system': 'redis',
                        'db.operation': cmd,
                        'db.redis.key': args[0],
                        'cache.hit': false // Will be updated
                    }
                });
                
                try {
                    const result = await original(...args);
                    
                    // Update cache hit/miss
                    if (cmd === 'get') {
                        span.setAttribute('cache.hit', result !== null);
                        span.setAttribute('cache.item_size', result ? result.length : 0);
                    }
                    
                    return result;
                } catch (error) {
                    span.recordException(error);
                    span.setStatus({ code: SpanStatusCode.ERROR });
                    throw error;
                } finally {
                    span.end();
                }
            };
        });
    }

    // Cache-aside pattern with tracing
    async getWithCache(key, fetchFunction, ttl = 3600) {
        const span = this.tracer.startSpan('cache.get_with_fallback');
        
        try {
            // Try cache first
            const cached = await this.client.get(key);
            if (cached) {
                span.setAttribute('cache.hit', true);
                return JSON.parse(cached);
            }
            
            // Cache miss - fetch from source
            span.setAttribute('cache.hit', false);
            const data = await fetchFunction();
            
            // Store in cache
            await this.client.set(key, JSON.stringify(data), {
                EX: ttl
            });
            
            return data;
        } finally {
            span.end();
        }
    }
}
```

### Phase 4: External Service Simulation (Week 2-3)
**Goal:** Add mock external services to demonstrate third-party API tracing

#### 4.1 Mock External API Service

```yaml
# docker-compose.additions.yml (continued)
services:
  mock-payment-gateway:
    build: ./mock-services/payment-gateway
    container_name: otel-payment-gateway
    environment:
      - OTEL_SERVICE_NAME=payment-gateway
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
    networks:
      - otel-network

  mock-email-service:
    build: ./mock-services/email-service
    container_name: otel-email-service
    environment:
      - OTEL_SERVICE_NAME=email-service
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
    networks:
      - otel-network

  webhook-receiver:
    build: ./mock-services/webhook-receiver
    container_name: otel-webhook-receiver
    ports:
      - "9090:9090"
    environment:
      - OTEL_SERVICE_NAME=webhook-receiver
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
    networks:
      - otel-network
```

```javascript
// mock-services/payment-gateway/index.js
const express = require('express');
const { trace, context, SpanStatusCode } = require('@opentelemetry/api');

const app = express();
const tracer = trace.getTracer('payment-gateway');

app.post('/process-payment', async (req, res) => {
    const span = tracer.startSpan('payment.process', {
        attributes: {
            'payment.amount': req.body.amount,
            'payment.currency': req.body.currency,
            'payment.method': req.body.method
        }
    });

    try {
        // Simulate payment processing with variable latency
        const processingTime = Math.random() * 2000 + 500;
        await new Promise(resolve => setTimeout(resolve, processingTime));
        
        // Simulate occasional failures
        if (Math.random() < 0.1) {
            throw new Error('Payment declined');
        }
        
        const transactionId = `txn_${Date.now()}`;
        span.setAttribute('payment.transaction_id', transactionId);
        span.setStatus({ code: SpanStatusCode.OK });
        
        res.json({
            success: true,
            transactionId,
            processingTime
        });
    } catch (error) {
        span.recordException(error);
        span.setStatus({ code: SpanStatusCode.ERROR });
        res.status(400).json({ error: error.message });
    } finally {
        span.end();
    }
});

app.listen(8090);
```

### Phase 5: Background Job Processing (Week 3)
**Goal:** Add background job processing with distributed tracing

#### 5.1 Background Job Processor

```yaml
# docker-compose.additions.yml (continued)
services:
  job-scheduler:
    build: ./job-scheduler
    container_name: otel-job-scheduler
    environment:
      - OTEL_SERVICE_NAME=job-scheduler
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
      - REDIS_URL=redis://redis123@redis-master:6379
    depends_on:
      - redis-master
      - kafka
    networks:
      - otel-network
```

```python
# job-scheduler/scheduler.py
import asyncio
import json
from datetime import datetime
from opentelemetry import trace, propagate
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.interval import IntervalTrigger
import redis
import aiokafka

class TracedJobScheduler:
    def __init__(self):
        self.tracer = trace.get_tracer(__name__)
        self.scheduler = AsyncIOScheduler()
        self.redis_client = redis.Redis(
            host='redis-master',
            port=6379,
            password='redis123'
        )
        self.kafka_producer = aiokafka.AIOKafkaProducer(
            bootstrap_servers='kafka:9092'
        )
        self.propagator = TraceContextTextMapPropagator()
    
    async def cleanup_expired_links(self):
        """Background job to clean expired short links"""
        with self.tracer.start_as_current_span('job.cleanup_expired_links') as span:
            span.set_attribute('job.type', 'scheduled')
            span.set_attribute('job.name', 'cleanup_expired_links')
            
            try:
                # Simulate database cleanup
                expired_count = await self.simulate_cleanup()
                
                span.set_attribute('job.items_processed', expired_count)
                
                # Send event to Kafka with trace context
                carrier = {}
                self.propagator.inject(carrier)
                
                event = {
                    'event_type': 'links_cleaned',
                    'count': expired_count,
                    'timestamp': datetime.utcnow().isoformat(),
                    'trace_context': carrier
                }
                
                await self.kafka_producer.send(
                    'job-events',
                    json.dumps(event).encode(),
                    headers=[
                        ('traceparent', carrier.get('traceparent', '').encode())
                    ]
                )
                
                span.set_status(Status(StatusCode.OK))
            except Exception as e:
                span.record_exception(e)
                span.set_status(Status(StatusCode.ERROR))
    
    async def generate_analytics_report(self):
        """Background job for analytics aggregation"""
        with self.tracer.start_as_current_span('job.generate_analytics') as span:
            span.set_attribute('job.type', 'scheduled')
            span.set_attribute('job.name', 'generate_analytics')
            
            # Simulate analytics processing
            await asyncio.sleep(2)
            
            # Store results in Redis with trace context
            report_key = f"analytics:{datetime.utcnow().strftime('%Y%m%d')}"
            report_data = {
                'total_clicks': 1234,
                'unique_users': 567,
                'trace_id': span.get_span_context().trace_id
            }
            
            self.redis_client.setex(
                report_key,
                86400,  # 24 hour TTL
                json.dumps(report_data)
            )
    
    def start(self):
        # Schedule jobs with different intervals
        self.scheduler.add_job(
            self.cleanup_expired_links,
            IntervalTrigger(minutes=30),
            id='cleanup_expired_links',
            name='Cleanup Expired Links',
            replace_existing=True
        )
        
        self.scheduler.add_job(
            self.generate_analytics_report,
            IntervalTrigger(hours=1),
            id='generate_analytics',
            name='Generate Analytics Report',
            replace_existing=True
        )
        
        self.scheduler.start()
```

### Phase 6: WebSocket Support (Week 3-4)
**Goal:** Add WebSocket connections with trace context propagation

#### 6.1 WebSocket Server with Tracing

```javascript
// websocket-server/index.js
const WebSocket = require('ws');
const { trace, context, propagation } = require('@opentelemetry/api');

class TracedWebSocketServer {
    constructor(port = 8085) {
        this.tracer = trace.getTracer('websocket-server');
        this.wss = new WebSocket.Server({ port });
        this.connections = new Map();
        
        this.setupServer();
    }
    
    setupServer() {
        this.wss.on('connection', (ws, req) => {
            // Extract trace context from connection headers
            const traceParent = req.headers['traceparent'];
            let parentContext = context.active();
            
            if (traceParent) {
                parentContext = propagation.extract(
                    context.active(),
                    { traceparent: traceParent }
                );
            }
            
            // Create connection span
            const connectionSpan = this.tracer.startSpan(
                'websocket.connection',
                {
                    attributes: {
                        'websocket.endpoint': req.url,
                        'websocket.protocol': req.headers['sec-websocket-protocol'],
                        'client.address': req.socket.remoteAddress
                    }
                },
                parentContext
            );
            
            const connectionId = `conn_${Date.now()}`;
            this.connections.set(connectionId, {
                ws,
                span: connectionSpan,
                context: trace.setSpan(parentContext, connectionSpan)
            });
            
            ws.on('message', (message) => {
                this.handleMessage(connectionId, message);
            });
            
            ws.on('close', () => {
                const conn = this.connections.get(connectionId);
                if (conn) {
                    conn.span.end();
                    this.connections.delete(connectionId);
                }
            });
            
            ws.on('error', (error) => {
                const conn = this.connections.get(connectionId);
                if (conn) {
                    conn.span.recordException(error);
                    conn.span.setStatus({ code: SpanStatusCode.ERROR });
                }
            });
        });
    }
    
    handleMessage(connectionId, message) {
        const conn = this.connections.get(connectionId);
        if (!conn) return;
        
        context.with(conn.context, () => {
            const messageSpan = this.tracer.startSpan('websocket.message', {
                attributes: {
                    'websocket.message.type': 'text',
                    'websocket.message.size': message.length
                }
            });
            
            try {
                const data = JSON.parse(message);
                
                // Process different message types
                switch (data.type) {
                    case 'subscribe':
                        this.handleSubscribe(connectionId, data);
                        break;
                    case 'unsubscribe':
                        this.handleUnsubscribe(connectionId, data);
                        break;
                    case 'ping':
                        conn.ws.send(JSON.stringify({ 
                            type: 'pong',
                            traceId: messageSpan.spanContext().traceId
                        }));
                        break;
                }
                
                messageSpan.setStatus({ code: SpanStatusCode.OK });
            } catch (error) {
                messageSpan.recordException(error);
                messageSpan.setStatus({ code: SpanStatusCode.ERROR });
            } finally {
                messageSpan.end();
            }
        });
    }
}
```

### Phase 7: Service Mesh Patterns (Week 4)
**Goal:** Implement service mesh patterns (circuit breaking, retries, timeouts)

#### 7.1 Circuit Breaker Implementation

```java
// shared-libraries/circuit-breaker/src/main/java/com/demo/circuitbreaker/TracedCircuitBreaker.java
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

@Component
public class TracedCircuitBreaker {
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("circuit-breaker");
    private final CircuitBreaker circuitBreaker;
    
    public TracedCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(10)
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build();
        
        this.circuitBreaker = CircuitBreaker.of("default", config);
        
        // Add event listeners with tracing
        circuitBreaker.getEventPublisher()
            .onStateTransition(event -> {
                Span span = tracer.spanBuilder("circuit_breaker.state_transition")
                    .setAttribute("circuit_breaker.name", event.getCircuitBreakerName())
                    .setAttribute("circuit_breaker.from_state", event.getStateTransition().getFromState().toString())
                    .setAttribute("circuit_breaker.to_state", event.getStateTransition().getToState().toString())
                    .startSpan();
                span.end();
            })
            .onCallNotPermitted(event -> {
                Span span = tracer.spanBuilder("circuit_breaker.call_not_permitted")
                    .setAttribute("circuit_breaker.name", event.getCircuitBreakerName())
                    .startSpan();
                span.end();
            });
    }
    
    public <T> T executeWithCircuitBreaker(Supplier<T> supplier, String operationName) {
        Span span = tracer.spanBuilder("circuit_breaker.execute")
            .setAttribute("circuit_breaker.operation", operationName)
            .setAttribute("circuit_breaker.state", circuitBreaker.getState().toString())
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            return circuitBreaker.executeSupplier(supplier);
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.setAttribute("circuit_breaker.state_after", circuitBreaker.getState().toString());
            span.end();
        }
    }
}
```

### Phase 8: Advanced Observability Features (Week 4)
**Goal:** Implement advanced tracing features

#### 8.1 Baggage Propagation for Business Context

```javascript
// shared-libraries/context-propagation/baggage.js
const { propagation, ROOT_CONTEXT } = require('@opentelemetry/api');

class BusinessContextPropagator {
    inject(context, carrier) {
        // Inject business context into baggage
        const baggage = propagation.getBaggage(context) || propagation.createBaggage();
        
        // Add business metadata
        baggage.setEntry('user.id', { value: context.userId });
        baggage.setEntry('tenant.id', { value: context.tenantId });
        baggage.setEntry('feature.flags', { value: JSON.stringify(context.featureFlags) });
        baggage.setEntry('session.id', { value: context.sessionId });
        
        // Standard trace propagation
        propagation.inject(context, carrier);
    }
    
    extract(context, carrier) {
        // Extract both trace and business context
        const extractedContext = propagation.extract(context, carrier);
        const baggage = propagation.getBaggage(extractedContext);
        
        if (baggage) {
            // Restore business context
            return {
                ...extractedContext,
                userId: baggage.getEntry('user.id')?.value,
                tenantId: baggage.getEntry('tenant.id')?.value,
                featureFlags: JSON.parse(baggage.getEntry('feature.flags')?.value || '{}'),
                sessionId: baggage.getEntry('session.id')?.value
            };
        }
        
        return extractedContext;
    }
}
```

## Docker Compose Integration

### Complete Docker Compose File Structure

```yaml
# docker-compose.yml
version: '3.8'

networks:
  otel-network:
    driver: bridge

volumes:
  postgres-data:
  kong-db-data:
  redis-data:
  kafka-data:
  zookeeper-data:

services:
  # Phase 1: Edge Layer
  nginx:
    extends:
      file: docker-compose.additions.yml
      service: nginx

  # Phase 2: API Gateway
  kong-database:
    extends:
      file: docker-compose.additions.yml
      service: kong-database

  kong:
    extends:
      file: docker-compose.additions.yml
      service: kong

  # Phase 3: Caching
  redis-master:
    extends:
      file: docker-compose.additions.yml
      service: redis-master

  redis-replica:
    extends:
      file: docker-compose.additions.yml
      service: redis-replica

  # Phase 4: External Services
  mock-payment-gateway:
    extends:
      file: docker-compose.additions.yml
      service: mock-payment-gateway

  webhook-receiver:
    extends:
      file: docker-compose.additions.yml
      service: webhook-receiver

  # Phase 5: Background Jobs
  job-scheduler:
    extends:
      file: docker-compose.additions.yml
      service: job-scheduler

  # Phase 6: WebSocket
  websocket-server:
    build: ./websocket-server
    container_name: otel-websocket
    ports:
      - "8085:8085"
    environment:
      - OTEL_SERVICE_NAME=websocket-server
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
    networks:
      - otel-network

  # Existing services continue...
  frontend:
    # ... existing config ...

  bff:
    # ... existing config ...
    depends_on:
      - redis-master  # Add Redis dependency

  # ... rest of existing services ...
```

## Implementation Timeline

### Week 1: Edge & Gateway
- [ ] Day 1-2: Implement NGINX with OpenTelemetry module
- [ ] Day 3-4: Add Kong API Gateway with plugins
- [ ] Day 5: Integration testing and trace validation

### Week 2: Caching & External Services
- [ ] Day 1-2: Redis cluster with tracing
- [ ] Day 3-4: Mock external services (payment, email)
- [ ] Day 5: Webhook receiver implementation

### Week 3: Advanced Patterns
- [ ] Day 1-2: Background job scheduler
- [ ] Day 3-4: WebSocket server with tracing
- [ ] Day 5: Circuit breaker patterns

### Week 4: Polish & Documentation
- [ ] Day 1-2: Baggage propagation for business context
- [ ] Day 3: Advanced sampling strategies
- [ ] Day 4: Performance testing
- [ ] Day 5: Documentation and demo preparation

## Validation Checklist

### Trace Completeness
- [ ] Frontend → NGINX → Kong → BFF → Services → Database
- [ ] All async operations (Kafka, jobs) maintain trace context
- [ ] External API calls include trace propagation
- [ ] WebSocket connections preserve trace context
- [ ] Cache operations are properly traced

### Performance Metrics
- [ ] <5ms overhead per service hop
- [ ] <1% CPU overhead from tracing
- [ ] Sampling reduces data volume by 90%
- [ ] All traces complete within 30 seconds

### Architecture Evolution
- [ ] Stage 2 → Stage 3 migration complete
- [ ] All components dockerized
- [ ] Load balancing demonstrated
- [ ] Circuit breaking functional
- [ ] Rate limiting visible in traces

## Demo Scenarios

### Scenario 1: Complete User Journey
1. User creates short URL
2. Request flows through NGINX → Kong → BFF
3. BFF checks Redis cache
4. On miss, calls URL API
5. URL API writes to PostgreSQL
6. Async event sent to Kafka
7. Analytics API processes event
8. Background job updates statistics
9. WebSocket notifies user

### Scenario 2: Circuit Breaker in Action
1. Simulate external service failure
2. Show circuit breaker opening in traces
3. Demonstrate fallback behavior
4. Show circuit recovery

### Scenario 3: Rate Limiting Impact
1. Generate high request volume
2. Show rate limiting at Kong
3. Demonstrate trace attributes for throttled requests
4. Show different limits for different endpoints

## Success Criteria

### Technical Requirements
✅ All services emit W3C compliant traces
✅ 100% trace propagation across boundaries
✅ Zero-code instrumentation where possible
✅ Sub-second trace visibility in Jaeger
✅ Business context preserved via baggage

### Architecture Requirements
✅ NGINX edge layer operational
✅ Kong API Gateway routing traffic
✅ Redis caching with hit/miss metrics
✅ External service simulation working
✅ Background jobs traced end-to-end
✅ WebSocket connections traced

### Observability Requirements
✅ Complete service dependency map visible
✅ Request flow traceable across all components
✅ Error correlation functional
✅ Performance bottlenecks identifiable
✅ Cache effectiveness measurable

## Conclusion

This roadmap transforms the otel-shortener-demo from a basic Stage 2 architecture into a comprehensive Stage 3 platform that demonstrates enterprise-grade distributed tracing. All components are fully dockerized and can be deployed with a single `docker-compose up` command.

The implementation provides:
1. **Complete edge-to-database tracing** with NGINX and Kong
2. **Cache operation visibility** through Redis instrumentation
3. **External service integration** patterns
4. **Async processing traceability** via jobs and WebSockets
5. **Service mesh patterns** including circuit breaking
6. **Business context propagation** using OpenTelemetry baggage

This enhanced demo serves as a best-in-class reference implementation for SaaS platform observability, demonstrating what "good looks like" for robust and consistent end-to-end traceability.