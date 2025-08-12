# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

otel-shortener-demo is a microservices-based URL shortener demonstrating distributed tracing with OpenTelemetry. It showcases W3C Trace Context propagation across HTTP and Kafka boundaries, OAuth 2.0 authentication, and both blocking (Servlet) and non-blocking (WebFlux) Java frameworks.

## Architecture

### Services
- **Frontend** (Next.js/React): User interface at localhost:3000
- **BFF** (Node.js/Express): Gateway service handling auth and routing at localhost:3001
- **URL API** (Spring Boot MVC): Creates short URLs, uses JDBC
- **Redirect Service** (Spring Boot WebFlux): High-performance redirects, uses R2DBC
- **Analytics API** (Spring Boot): Kafka consumer for analytics events
- **Infrastructure**: PostgreSQL, Kafka, Keycloak, Jaeger, OpenTelemetry Collector

### Key Flows
1. **Create Link**: Frontend → BFF → URL API → PostgreSQL → Kafka → Analytics
2. **Use Link**: Browser → Redirect Service → PostgreSQL (R2DBC) → Kafka → Analytics

## Development Commands

### Quick Start
```bash
# Start all services
docker-compose up --build -d

# View logs for a service
docker-compose logs -f <service-name>

# Rebuild specific service
docker-compose up --build <service-name> -d

# Stop everything
docker-compose down -v
```

### Service URLs
- Frontend: http://localhost:3000
- BFF API: http://localhost:3001
- Redirect: http://localhost:8081/{shortCode}
- Jaeger UI: http://localhost:16686
- Keycloak: http://localhost:8880/auth/ (admin/admin)

### Database Access
```bash
# Connect to PostgreSQL
docker exec -it otel-shortener-demo-postgres-1 psql -U otel_user -d otel_db

# Common queries
SELECT * FROM links;
SELECT * FROM clicks;
```

## Code Structure

### Frontend (frontend/)
- Next.js 14 with App Router
- Keycloak integration via `next-auth`
- API calls to BFF service

### BFF (bff/)
- Express server with OAuth token management
- Forwards requests with M2M tokens
- Adds X-User-ID and X-Tenant-ID headers

### Java Services (url-api/, redirect-service/, analytics-api/)
- Spring Boot 3.5.3, Java 17
- OpenTelemetry auto-instrumentation
- Custom context propagation library in shared modules

### Shared Libraries
- `context-filter-library/`: Unified context management for servlet/reactive apps
- `kafka-propagation/`: W3C trace context for Kafka messages

## Key Implementation Patterns

### OpenTelemetry Tracing
- Auto-instrumentation via Spring Boot starter
- Manual Kafka instrumentation using TextMapSetter/Getter
- W3C traceparent/tracestate headers for context propagation

### Authentication
- User auth: OAuth 2.0 Authorization Code Flow
- Service auth: OAuth 2.0 Client Credentials Flow
- BFF handles authorization, downstream services trust headers

### Context Headers
- `traceparent`: W3C trace context (replaces X-Request-ID)
- `Authorization`: M2M Bearer token
- `X-User-ID`: Verified user identity from JWT
- `X-Tenant-ID`: Tenant context

## Testing Distributed Traces

1. Create a short URL through the UI
2. Open Jaeger UI (http://localhost:16686)
3. Search for traces from "frontend" service
4. Verify complete trace spans across all services
5. Check context propagation in span tags

## Common Development Tasks

### Adding New Endpoints
1. Update BFF route in `bff/server.js`
2. Add corresponding endpoint in target Java service
3. Ensure proper context propagation using existing filters

### Modifying Database Schema
1. Update schema in `postgres-init/init.sql`
2. Update entity classes in Java services
3. Rebuild with `docker-compose up --build postgres -d`

### Debugging Traces
1. Check service logs: `docker-compose logs -f <service>`
2. Verify in Jaeger UI for trace visualization
3. Check OpenTelemetry Collector logs for export issues

## Important Notes

- Services use auto-instrumentation - avoid manual span creation unless necessary
- BFF is the authorization boundary - downstream services trust headers
- Kafka messages include trace context in headers for async tracing
- Feature flags via flagd can dynamically control behavior

## Recent Implementation (Jan 2025) - E2E Traceability Enhancements

### Current Branch: feat/initial-docker-compose-setup

### Completed Enhancements (3 Sprints)

#### Sprint 1: Core Infrastructure
- **Frontend**: Added W3C trace propagation (traceparent header) in `frontend/instrumentation.ts`
- **NGINX**: Edge layer at port 80 with trace preservation and rate limiting (`nginx/nginx.conf`)
- **Redis**: Caching layer with traced operations (`bff/redis-client.js`)

#### Sprint 2: Context Establishment
- **BFF Context Middleware** (`bff/middleware/context.js`):
  - Extracts JWT claims (placeholder validation returning mock claims)
  - Caches context in Redis with TTL
  - Creates standard headers: X-Tenant-ID, X-User-ID, X-User-Email, X-User-Groups, X-Service-Name, X-Transaction-Name, X-Correlation-ID
  - Includes authorization scope checking
- **Java MDC Integration**:
  - `MdcContextFilter` for url-api and analytics-api
  - `MdcContextWebFilter` for redirect-service (reactive)
  - All services use SLF4J with MDC instead of System.out.println
  - Logback configured with MDC pattern including traceId, userId, tenantId
  - Centralized logging to `/logs/shared/all-services.log`

#### Sprint 3: Advanced Features
- **Scheduled Jobs** (`url-api/src/main/java/com/example/urlapi/ScheduledJobs.java`):
  - Check expired links (every 30 seconds)
  - Generate analytics report (every 60 seconds)
  - Full trace propagation to Kafka via headers
  - MDC context for job execution
- **Exploratory Test Guide** (`docs/EXPLORATORY_TEST_GUIDE.md`):
  - 10 comprehensive test scenarios
  - Performance testing instructions
  - Troubleshooting checklist

### Key Design Decisions (from Desktop Review)
1. **Edge Layer**: Simple NGINX (no Kong/API Gateway)
2. **Context Establishment**: At BFF layer (not API Gateway)
3. **Caching**: Redis standalone dockerized
4. **Job Processing**: @Scheduled annotation (no Quartz)
5. **Real-time**: No WebSocket for this demo
6. **External Services**: No mocks needed
7. **Observability**: 100% sampling, centralized logging to file

### Current State
- All OAuth is placeholder/mock (returns hardcoded claims)
- Services accept any Bearer token (no real validation)
- Focus is on trace propagation and context flow, not security
- Everything runs in Docker Compose for easy demo

### Testing the Implementation
1. Start services: `docker-compose up -d`
2. Access frontend: http://localhost:3000
3. Create a short link and check:
   - Jaeger UI (http://localhost:16686) for distributed trace
   - Logs in `logs/shared/all-services.log` for MDC fields
   - Redis for cached context: `docker exec -it otel-redis redis-cli`
4. Wait for scheduled jobs to run (check url-api logs)

### Next Steps if Resuming
- Consider implementing real OAuth validation with Keycloak
- Add Prometheus metrics collection
- Implement actual link expiration logic
- Add integration tests for trace verification
- Consider adding distributed rate limiting with Redis
- Implement circuit breakers for resilience