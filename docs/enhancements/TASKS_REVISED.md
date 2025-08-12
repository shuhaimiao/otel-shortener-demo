# Implementation Tasks: E2E Traceability Enhancements (Revised)

## Overview

This document contains the simplified task list based on desktop review decisions. Focus is on essential components for demonstrating end-to-end trace context propagation.

## Sprint Planning

### Sprint 1 (Week 1): Core Infrastructure
**Goal**: Set up edge layer and caching

### Sprint 2 (Week 2): Context Establishment  
**Goal**: Implement trace initiation and context propagation

### Sprint 3 (Week 3): Async Patterns
**Goal**: Add scheduled job and ensure Kafka propagation

---

## Phase 1: Frontend Trace Initiation - Sprint 1

### TASK-001: Add OpenTelemetry to SPA
**Priority**: P0  
**Effort**: 4 hours  
**Dependencies**: None

**Subtasks**:
- [ ] Install OpenTelemetry Web SDK
- [ ] Configure trace provider
- [ ] Set up W3C propagator
- [ ] Configure CORS for trace headers
- [ ] Test traceparent generation

**Acceptance Criteria**:
- SPA generates traceparent for all API calls
- Headers visible in browser DevTools
- Traces appear in Jaeger

---

## Phase 2: NGINX Edge Layer - Sprint 1

### TASK-002: Create NGINX Docker Image
**Priority**: P0  
**Effort**: 6 hours  
**Dependencies**: None

**Subtasks**:
- [ ] Find or build NGINX with OpenTelemetry module
- [ ] Create minimal nginx.conf
- [ ] Configure OTLP exporter
- [ ] Create Dockerfile
- [ ] Test container builds

**Files to Create**:
```
nginx/
├── Dockerfile
├── nginx.conf
└── otel-nginx.toml
```

### TASK-003: Configure NGINX Routing
**Priority**: P0  
**Effort**: 2 hours  
**Dependencies**: TASK-002

**Subtasks**:
- [ ] Set up proxy_pass to BFF
- [ ] Configure trace header preservation
- [ ] Add basic rate limiting
- [ ] Test end-to-end routing

**Acceptance Criteria**:
- Requests routed to BFF
- traceparent header preserved
- NGINX spans visible in Jaeger

---

## Phase 3: Redis Cache - Sprint 1

### TASK-004: Add Redis Container
**Priority**: P0  
**Effort**: 2 hours  
**Dependencies**: None

**Subtasks**:
- [ ] Add Redis to docker-compose.yml
- [ ] Configure persistence
- [ ] Set memory limits
- [ ] Test container starts

### TASK-005: Implement Traced Redis Client
**Priority**: P0  
**Effort**: 3 hours  
**Dependencies**: TASK-004

**Subtasks**:
- [ ] Create TracedRedisClient for BFF
- [ ] Add span creation for operations
- [ ] Implement cache hit/miss tracking
- [ ] Test trace generation

**Files to Create**:
```
bff/
├── redis-client.js
└── middleware/cache.js
```

---

## Phase 4: BFF Context Establishment - Sprint 2

### TASK-006: Create Context Middleware
**Priority**: P0  
**Effort**: 4 hours  
**Dependencies**: TASK-005

**Subtasks**:
- [ ] Create token validation placeholder
- [ ] Extract mock claims
- [ ] Cache claims in Redis
- [ ] Create standard headers
- [ ] Add trace attributes

**Files to Create**:
```
bff/middleware/
├── context.js
└── auth.js
```

### TASK-007: Propagate Context Headers
**Priority**: P0  
**Effort**: 2 hours  
**Dependencies**: TASK-006

**Subtasks**:
- [ ] Add headers to downstream calls
- [ ] Update URL API client
- [ ] Test header propagation
- [ ] Verify in trace spans

**Acceptance Criteria**:
- X-Tenant-ID propagated
- X-User-ID propagated
- X-Service-Name added
- Headers visible in traces

---

## Phase 5: Java MDC Integration - Sprint 2

### TASK-008: Create MDC Interceptor
**Priority**: P0  
**Effort**: 3 hours  
**Dependencies**: None

**Subtasks**:
- [ ] Create Spring HandlerInterceptor
- [ ] Extract headers to MDC
- [ ] Configure in all Java services
- [ ] Test MDC population

**Files to Create**:
```
shared-libraries/
└── src/main/java/com/demo/shared/
    └── MDCInterceptor.java
```

### TASK-009: Configure Centralized Logging
**Priority**: P1  
**Effort**: 3 hours  
**Dependencies**: TASK-008

**Subtasks**:
- [ ] Create shared volume for logs
- [ ] Configure logback.xml for Java
- [ ] Configure Winston for Node.js
- [ ] Test unified log format

**Files to Create**:
```
config/
├── logback.xml
└── winston.config.js
```

---

## Phase 6: Scheduled Job - Sprint 3

### TASK-010: Add @Scheduled Job to Analytics API
**Priority**: P0  
**Effort**: 4 hours  
**Dependencies**: None

**Subtasks**:
- [ ] Create ScheduledJobs class
- [ ] Implement link cleanup job
- [ ] Create root span for job
- [ ] Add trace context to Kafka
- [ ] Test job execution

**Files to Create**:
```
analytics-api/src/main/java/com/demo/analytics/
└── jobs/
    └── ScheduledJobs.java
```

### TASK-011: Verify Kafka Trace Propagation
**Priority**: P0  
**Effort**: 3 hours  
**Dependencies**: TASK-010

**Subtasks**:
- [ ] Add traceparent to Kafka headers
- [ ] Extract context in consumer
- [ ] Continue trace in Analytics API
- [ ] Test async trace continuity

**Acceptance Criteria**:
- Job creates root span
- Kafka message includes trace
- Consumer continues trace
- Complete trace in Jaeger

---

## Phase 7: Integration Testing - Sprint 3

### TASK-012: End-to-End Trace Validation
**Priority**: P0  
**Effort**: 4 hours  
**Dependencies**: All previous tasks

**Subtasks**:
- [ ] Create test script
- [ ] Verify SPA → Database trace
- [ ] Verify scheduled job trace
- [ ] Check context propagation
- [ ] Document trace patterns

### TASK-013: Performance Validation
**Priority**: P1  
**Effort**: 2 hours  
**Dependencies**: TASK-012

**Subtasks**:
- [ ] Measure trace overhead
- [ ] Check memory usage
- [ ] Verify 100% sampling works
- [ ] Document performance metrics

---

## Phase 8: Documentation - Sprint 3

### TASK-014: Create Setup Guide
**Priority**: P0  
**Effort**: 3 hours  
**Dependencies**: TASK-012

**Subtasks**:
- [ ] Document Docker setup
- [ ] Create configuration guide
- [ ] Write troubleshooting section
- [ ] Add example traces

**Files to Create**:
```
docs/
├── SETUP_GUIDE.md
├── TRACE_PATTERNS.md
└── TROUBLESHOOTING.md
```

### TASK-015: Document Patterns Not Implemented
**Priority**: P1  
**Effort**: 2 hours  
**Dependencies**: None

**Subtasks**:
- [ ] Document external API pattern
- [ ] Document real OAuth flow
- [ ] Document WebSocket pattern
- [ ] Document production considerations

---

## Summary

### Total Tasks: 15 (Simplified from 35)

### By Priority:
- **P0 (Critical)**: 11 tasks
- **P1 (Important)**: 4 tasks

### By Sprint:
- **Sprint 1**: 5 tasks (Frontend, NGINX, Redis)
- **Sprint 2**: 4 tasks (Context, MDC, Logging)
- **Sprint 3**: 6 tasks (Job, Testing, Docs)

### Estimated Effort:
- **Total**: ~50 hours (vs 200 originally)
- **Team Size**: 1-2 developers
- **Duration**: 3 weeks

### Key Simplifications:
- ❌ No Kong API Gateway
- ❌ No Quartz Scheduler
- ❌ No WebSocket Service
- ❌ No External Service Mocks
- ❌ No SSL/TLS Configuration
- ❌ No Load Balancing
- ✅ Focus on trace propagation
- ✅ Simple, working demo

## Definition of Done

### For Each Task:
- [ ] Code complete
- [ ] Traces visible in Jaeger
- [ ] Context propagated correctly
- [ ] Logs contain trace ID

### For Sprint:
- [ ] All P0 tasks complete
- [ ] Integration tested
- [ ] Documentation updated

### For Project:
- [ ] End-to-end traces working
- [ ] SPA → Database complete trace
- [ ] Scheduled job → Kafka trace
- [ ] Context headers throughout
- [ ] Centralized logging working
- [ ] Single docker-compose deployment

---

**Document Version**: 2.0 (Revised)  
**Last Updated**: 2024-12-12  
**Status**: Ready for Implementation  
**Total Effort**: ~50 hours (reduced from 200)