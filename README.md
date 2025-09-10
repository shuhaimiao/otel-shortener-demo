# OpenTelemetry URL Shortener Demo

A comprehensive microservices application demonstrating end-to-end distributed tracing with OpenTelemetry, showcasing trace propagation across synchronous HTTP calls and asynchronous CDC/Kafka boundaries.

## 🎯 Project Overview

This project implements a URL shortener service to demonstrate:
- **End-to-end distributed tracing** with W3C Trace Context specification
- **Trace propagation** through synchronous (HTTP) and asynchronous (CDC/Kafka) boundaries
- **Transactional Outbox Pattern** with Debezium CDC for reliable event publishing
- **Context propagation** in both blocking (Servlet) and non-blocking (Reactive) Java frameworks
- **Unified logging** with MDC (Mapped Diagnostic Context) integration
- **Edge services** with NGINX and Redis caching

## 🏗️ Architecture

### System Components

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Frontend  │────▶│    NGINX    │────▶│     BFF     │
│  (Next.js)  │     │(Edge Layer) │     │ (Node.js)   │
└─────────────┘     └─────────────┘     └──────┬──────┘
                                                │
                    ┌───────────────────────────┴───────────────────────────┐
                    │                                                       │
            ┌───────▼────────┐                                   ┌─────────▼──────────┐
            │    URL API     │                                   │  Redirect Service  │
            │ (Spring MVC)   │                                   │ (Spring WebFlux)   │
            └───────┬────────┘                                   └─────────┬──────────┘
                    │                                                       │
        ┌───────────┴───────────┐                                         │
        │                       │                                          │
┌───────▼────────┐     ┌────────▼────────┐                              │
│   PostgreSQL   │     │  Outbox Table   │                              │
│    (Links)     │     │  (CDC Events)   │◀─────────────────────────────┘
└────────────────┘     └────────┬────────┘
                                │
                        ┌───────▼────────┐
                        │    Debezium    │
                        │      CDC       │
                        └───────┬────────┘
                                │
                        ┌───────▼────────┐
                        │     Kafka      │
                        └───────┬────────┘
                                │
                        ┌───────▼────────┐     ┌──────────────┐
                        │ Analytics API  │────▶│   Jaeger     │
                        │ (Spring Boot)  │     │ (Trace UI)   │
                        └────────────────┘     └──────────────┘
```

### Service Descriptions

| Service | Technology | Purpose | Trace Features |
|---------|------------|---------|----------------|
| **Frontend** | Next.js 14 | User interface | Initiates traces with W3C traceparent |
| **NGINX** | NGINX 1.25 | Edge routing, rate limiting | Preserves trace context |
| **BFF** | Node.js/Express | API gateway, auth handling | Context establishment, Redis caching |
| **URL API** | Spring Boot MVC | Link creation, business logic | Outbox pattern, scheduled jobs |
| **Redirect Service** | Spring WebFlux | High-performance redirects | Reactive R2DBC, async processing |
| **Analytics API** | Spring Boot | Event processing | Kafka consumer, trace continuation |
| **PostgreSQL** | PostgreSQL 16 | Primary datastore | Outbox table for CDC |
| **Kafka** | Apache Kafka | Event streaming | Async messaging backbone |
| **Debezium** | Debezium 2.5 | Change Data Capture | Trace context preservation |
| **Redis** | Redis 7 | Caching layer | Context caching with TTL |
| **Jaeger** | Jaeger | Trace visualization | Full trace analysis |

## 🚀 Quick Start

### Prerequisites
- Docker Desktop 4.0+
- 8GB RAM minimum
- Ports 80, 3000, 3001, 8080-8083, 16686 available

### Start the Demo

```bash
# Clone the repository
git clone https://github.com/yourusername/otel-shortener-demo.git
cd otel-shortener-demo

# Start all services
docker-compose up -d

# Wait for services to initialize (30-60 seconds)
docker-compose ps

# Verify Debezium connector is running
curl -s http://localhost:8083/connectors/postgres-outbox-connector/status | grep state
```

### Access the Application

- **Frontend**: http://localhost:3000
- **Jaeger UI**: http://localhost:16686
- **Keycloak**: http://localhost:8880/auth (admin/admin)

## 📡 Distributed Tracing Features

### Trace Propagation Flow

1. **Frontend → BFF → URL API → Analytics API**
   - Complete end-to-end trace through synchronous and asynchronous boundaries
   - Trace context preserved through CDC/Kafka using transactional outbox pattern

2. **Context Headers**
   - `traceparent`: W3C trace context (replaces traditional X-Request-ID)
   - `tracestate`: Vendor-specific trace information
   - `X-User-ID`, `X-Tenant-ID`: Business context
   - `X-Correlation-ID`: Additional correlation tracking

### Key Implementation Details

#### Transactional Outbox Pattern
- Events written to `outbox_events` table with trace context
- Debezium captures changes and publishes to Kafka with trace headers
- Analytics service extracts trace context and continues the trace

#### MDC Integration
All Java services use SLF4J with MDC for structured logging:
```java
MDC.put("traceId", span.getSpanContext().getTraceId());
MDC.put("userId", userContext.getUserId());
MDC.put("tenantId", userContext.getTenantId());
```

#### Scheduled Jobs with Tracing
URL API includes scheduled jobs that maintain trace context:
- Link expiration checking (every 30 seconds)
- Analytics report generation (every 60 seconds)

## 🧪 Testing the End-to-End Trace

### Create a Short Link

1. Open http://localhost:3000
2. Enter a URL to shorten
3. Click "Shorten"
4. Copy the trace ID from browser DevTools (Network tab → Response Headers → `traceparent`)

### View the Complete Trace

1. Open Jaeger UI: http://localhost:16686
2. Search for the trace ID
3. Verify the complete trace flow:
   ```
   frontend: HTTP POST
   └── bff: POST /api/links
       └── url-api: POST /links
           ├── HikariDataSource.getConnection
           ├── INSERT otel_shortener_db.links
           ├── INSERT otel_shortener_db.outbox_events
           └── analytics-api: kafka.consume.link-events (async continuation)
   ```

## 🔧 Configuration

### Environment Variables

Key configuration options in `docker-compose.yml`:

```yaml
# OpenTelemetry Configuration
OTEL_SERVICE_NAME: Service name for tracing
OTEL_EXPORTER_OTLP_ENDPOINT: Collector endpoint
OTEL_PROPAGATORS: tracecontext,baggage

# Context Headers
X-Service-Name: Service identification
X-Transaction-Name: Transaction type
X-Correlation-ID: Request correlation
```

### Logging Configuration

Centralized logging to `/logs/shared/all-services.log` with MDC pattern:
```xml
<pattern>%d{ISO8601} [%thread] %-5level %logger{36} - traceId=%X{traceId} userId=%X{userId} tenantId=%X{tenantId} - %msg%n</pattern>
```

## 📊 Observability Stack

- **OpenTelemetry Collector**: Receives and exports traces
- **Jaeger**: Distributed trace visualization
- **Centralized Logging**: All services log to shared file with trace correlation
- **Redis Monitoring**: Context cache hit/miss tracking

## 🛠️ Development

### Project Structure

```
otel-shortener-demo/
├── frontend/                 # Next.js frontend application
├── nginx/                    # NGINX configuration
├── bff/                      # Node.js BFF service
├── url-api/                  # Spring Boot MVC service
├── redirect-service/         # Spring WebFlux reactive service
├── analytics-api/            # Kafka consumer service
├── scripts/                  # Database initialization
├── debezium/                 # CDC configuration
├── otel-collector-config.yml # OpenTelemetry configuration
└── docker-compose.yml        # Service orchestration
```

### Adding New Services

1. Implement OpenTelemetry instrumentation
2. Add MDC context filter (for Java services)
3. Configure trace propagation headers
4. Update docker-compose.yml with OTEL environment variables

## 📚 Documentation

- [Architecture Details](docs/ARCHITECTURE.md)
- [E2E Trace Test Procedure](docs/E2E_TRACE_TEST_PROCEDURE.md)
- [Context Propagation Guide](docs/CONTEXT_PROPAGATION.md)
- [Outbox Pattern Implementation](docs/SPEC-OUTBOX-DEBEZIUM.md)

## 🤝 Contributing

This is a demonstration project for learning distributed tracing concepts. Contributions that enhance the educational value are welcome!

## 📝 License

MIT License - See LICENSE file for details

## 🙏 Acknowledgments

Built with:
- [OpenTelemetry](https://opentelemetry.io/)
- [Spring Boot](https://spring.io/projects/spring-boot)
- [Debezium](https://debezium.io/)
- [Apache Kafka](https://kafka.apache.org/)
- [Next.js](https://nextjs.org/)