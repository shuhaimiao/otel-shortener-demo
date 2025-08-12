# Desktop Review Decisions

## Summary of Approved Decisions

This document captures all decisions made during the desktop review session for evolving the otel-shortener-demo to Stage 3 architecture with a focus on end-to-end traceability.

## Decision Log

### ✅ Decision #1: Edge Layer Architecture
**Decision**: NGINX with OpenTelemetry Module
- Single BFF instance (no load balancing complexity)
- HTTP only (no SSL/TLS)
- Focus on trace initiation at edge

### ✅ Decision #2: API Gateway & Context Establishment
**Decision**: No separate API Gateway, Context at BFF
- Skip Kong/API Gateway to reduce complexity
- NGINX handles edge routing only
- BFF establishes context from token claims
- Context propagated as headers to downstream services

### ✅ Decision #3: Caching Strategy
**Decision**: Redis Standalone (Dockerized)
- Simple Redis container
- Cache token claims with TTL matching token expiration
- Focus on cache hit/miss tracing

### ✅ Decision #4: Job Processing
**Decision**: @Scheduled in Existing Service
- Use Spring @Scheduled annotations (no Quartz)
- Single scheduled job is sufficient
- Add to Analytics API service
- Focus on trace propagation to Kafka

### ✅ Decision #5: Real-time Communication
**Decision**: Skip WebSocket
- No WebSocket implementation
- Kafka + Scheduled Jobs sufficient for async patterns
- Keep demo focused and simple

### ✅ Decision #6: External Service Integration
**Decision**: Skip External Services
- No mock external services
- Internal service-to-service tracing is sufficient
- Document external call patterns only

### ✅ Decision #7: Observability Strategy
**Decision**: Focused Trace Propagation
- 100% sampling for demo
- Centralized logging to single file
- Key attributes: traceparent, tenant_id, user_id, service_name, transaction_name
- OAuth placeholders (show in traces but don't implement)

## Simplified Architecture

### What We're Building:
```
SPA (initiates trace)
  ↓
NGINX (edge routing, fallback trace initiation)
  ↓
BFF (context establishment from tokens)
  ↓
Services (URL API, Redirect, Analytics)
  ↓
Redis | Kafka | PostgreSQL
  ↓
Scheduled Job (in Analytics API)
```

### What We're NOT Building:
- Kong API Gateway
- Quartz Scheduler
- WebSocket Service
- External Service Mocks
- Real OAuth 2.0 implementation
- SSL/TLS configuration
- Multiple BFF instances for load balancing

## Context Propagation Strategy

### Trace Initiation:
1. **Primary**: SPA generates traceparent using OpenTelemetry JS
2. **Fallback**: NGINX generates if missing

### Context Establishment (at BFF):
```
Token Claims → Standard Headers:
- X-Tenant-ID
- X-User-ID
- X-User-Groups
- X-Service-Name
- X-Transaction-Name
```

### Propagation Mechanisms:
- **HTTP**: Headers with traceparent
- **Kafka**: Message headers
- **Database**: SQL comments
- **Redis**: Span attributes
- **Logs**: MDC (Java) / Context (Node.js)

## Implementation Priorities

### Phase 1: Core Infrastructure
1. NGINX with OpenTelemetry
2. Redis standalone
3. Centralized logging

### Phase 2: Context Flow
1. SPA trace initiation
2. BFF context establishment
3. MDC integration in Java services

### Phase 3: Async Patterns
1. Scheduled job with trace propagation
2. Kafka header propagation
3. Cache operation tracing

## Success Criteria

- ✅ End-to-end trace visible from SPA to database
- ✅ Context headers propagated through all services
- ✅ Async boundaries maintain trace context
- ✅ Cache operations visible in traces
- ✅ Scheduled jobs create root spans
- ✅ All logs contain trace ID and business context
- ✅ Single docker-compose deployment

## Documentation Requirements

Will document but not implement:
- External API call patterns
- OAuth 2.0 Client Credentials flow
- WebSocket trace propagation
- Production sampling strategies
- Sumo Logic integration

---

**Document Version**: 1.0  
**Review Date**: 2024-12-12  
**Approved By**: Desktop Review Session  
**Status**: Approved for Implementation