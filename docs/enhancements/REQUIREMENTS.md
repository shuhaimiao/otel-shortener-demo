# Requirements Specification: E2E Traceability Enhancements

## 1. Executive Summary

This document outlines the requirements for enhancing the otel-shortener-demo to demonstrate enterprise-grade end-to-end distributed tracing capabilities. The enhancements will evolve the architecture from Stage 2 (Growth) to Stage 3 (Scale) while maintaining a Java-centric backend implementation.

## 2. Business Requirements

### 2.1 Primary Objectives
- **BR-01**: Demonstrate complete end-to-end traceability from edge to database
- **BR-02**: Showcase essential enterprise patterns for distributed tracing
- **BR-03**: Provide a simplified reference implementation for OpenTelemetry adoption
- **BR-04**: Maintain fully dockerized deployment for easy demonstration
- **BR-05**: Focus on trace context propagation over complex architecture

### 2.2 Success Criteria
- 100% trace propagation across all service boundaries
- Complete visibility of synchronous and asynchronous operations
- Demonstration of cache, external API, and background job tracing
- Sub-second trace visibility in Jaeger UI
- Single `docker-compose up` deployment

## 3. Functional Requirements

### 3.1 Edge Layer (NGINX)

#### FR-NGINX-01: OpenTelemetry Instrumentation
- **Description**: NGINX must generate and propagate traces for all requests
- **Acceptance Criteria**:
  - Every request generates a span with proper attributes
  - W3C traceparent headers are preserved and forwarded
  - Response time, status code, and upstream metrics are captured

#### FR-NGINX-02: Load Balancing Tracing
- **Description**: Load balancing decisions must be visible in traces
- **Acceptance Criteria**:
  - Upstream server selection is recorded as span attribute
  - Failed upstream attempts are traced
  - Health check requests are excluded from traces

#### FR-NGINX-03: Static Asset Caching
- **Description**: Cache operations for static assets must be traced
- **Acceptance Criteria**:
  - Cache hit/miss status recorded in span
  - Cache key and TTL visible in trace attributes
  - CDN-like behavior simulation with proper cache headers

#### FR-NGINX-04: Rate Limiting
- **Description**: Rate limiting must be visible in traces
- **Acceptance Criteria**:
  - Rate limit zone and status recorded
  - 429 responses include rate limit headers
  - Trace shows when requests are throttled

### 3.2 Context Establishment Layer (BFF)

#### FR-BFF-01: Token Claims Extraction
- **Description**: Extract user context from authentication tokens
- **Acceptance Criteria**:
  - Token claims parsed (using placeholders for demo)
  - User ID, Tenant ID extracted
  - Context cached with token TTL

#### FR-BFF-02: Standard Header Creation
- **Description**: Establish standard context headers
- **Acceptance Criteria**:
  - X-Tenant-ID header created
  - X-User-ID header created
  - X-Service-Name header added
  - X-Transaction-Name for operations

#### FR-BFF-03: Context Propagation
- **Description**: Propagate context to all downstream services
- **Acceptance Criteria**:
  - Headers included in all service calls
  - Context added to trace spans
  - MDC population supported

### 3.3 Caching Layer (Redis)

#### FR-REDIS-01: Cache Operations Tracing
- **Description**: All Redis operations must be traced
- **Acceptance Criteria**:
  - GET, SET, DEL operations create spans
  - Cache hit/miss clearly indicated
  - Key names and TTL values recorded

#### FR-REDIS-02: Cache-Aside Pattern
- **Description**: Cache-aside pattern must be fully observable
- **Acceptance Criteria**:
  - Cache check → miss → fetch → store sequence visible
  - Data source query linked to cache miss
  - Cache population after miss is traced

#### FR-REDIS-03: Distributed Cache
- **Description**: Redis cluster operations must be traced
- **Acceptance Criteria**:
  - Master/replica operations distinguished
  - Failover events create spans
  - Sentinel decisions traced

### 3.4 Background Job Processing (Simplified)

#### FR-JOB-01: Scheduled Job Execution
- **Description**: Single scheduled job with trace propagation
- **Acceptance Criteria**:
  - @Scheduled annotation creates root span
  - Job execution traced
  - Trace context propagated to Kafka

#### FR-JOB-02: Link Expiration Job
- **Description**: Simple cleanup job for demonstration
- **Acceptance Criteria**:
  - Runs every 30 minutes
  - Creates root trace span
  - Sends events to Kafka with trace context

### 3.5 ~~WebSocket Support~~ (Removed per Desktop Review)

#### FR-WS-01: Connection Lifecycle
- **Description**: WebSocket connections must be traced
- **Acceptance Criteria**:
  - Connection establishment creates span
  - Connection duration tracked
  - Disconnection reason recorded

#### FR-WS-02: Message Tracing
- **Description**: Individual messages must be traced
- **Acceptance Criteria**:
  - Each message creates a child span
  - Message type and size recorded
  - Binary vs text messages distinguished

#### FR-WS-03: Trace Context Propagation
- **Description**: Trace context must flow through WebSocket
- **Acceptance Criteria**:
  - Initial HTTP upgrade includes traceparent
  - Messages can carry trace context
  - Server-pushed messages include trace

#### FR-WS-04: Real-time Notifications
- **Description**: Push notifications must be traceable
- **Acceptance Criteria**:
  - Broadcast operations create spans
  - Target client selection traced
  - Delivery confirmation recorded

### 3.6 ~~External Service Integration~~ (Documentation Only)

- External service patterns will be documented
- No mock services will be implemented
- Focus on internal service-to-service tracing

### 3.7 Advanced Observability Features

#### FR-OBS-01: Baggage Propagation
- **Description**: Business context must flow with traces
- **Acceptance Criteria**:
  - User ID, tenant ID in baggage
  - Feature flags propagated
  - Session ID correlation

#### FR-OBS-02: Sampling Strategies
- **Description**: Intelligent sampling must be implemented
- **Acceptance Criteria**:
  - 100% sampling for errors
  - 100% sampling for slow requests (>1s)
  - 10% sampling for normal traffic
  - Head-based and tail-based options

#### FR-OBS-03: Trace Correlation
- **Description**: Traces must correlate with logs and metrics
- **Acceptance Criteria**:
  - Trace ID in all log entries
  - Metrics tagged with trace ID
  - Error correlation across signals

## 4. Non-Functional Requirements

### 4.1 Performance

#### NFR-PERF-01: Latency Overhead
- **Requirement**: Tracing overhead <5ms per service hop
- **Measurement**: P99 latency with/without tracing

#### NFR-PERF-02: Resource Usage
- **Requirement**: <5% CPU overhead from instrumentation
- **Measurement**: CPU usage comparison baseline vs traced

#### NFR-PERF-03: Memory Usage
- **Requirement**: <50MB additional memory per service
- **Measurement**: Memory footprint with full instrumentation

#### NFR-PERF-04: Trace Processing
- **Requirement**: Traces visible in Jaeger within 1 second
- **Measurement**: Time from span creation to UI visibility

### 4.2 Scalability

#### NFR-SCALE-01: Trace Volume
- **Requirement**: Support 10,000 traces/second
- **Measurement**: Collector throughput testing

#### NFR-SCALE-02: Span Cardinality
- **Requirement**: Maintain <1000 unique operation names
- **Measurement**: Cardinality analysis in Jaeger

#### NFR-SCALE-03: Storage Efficiency
- **Requirement**: <1KB average span size
- **Measurement**: Storage usage per million spans

### 4.3 Reliability

#### NFR-REL-01: Trace Completeness
- **Requirement**: >99% of traces complete without gaps
- **Measurement**: Trace integrity validation

#### NFR-REL-02: Context Propagation
- **Requirement**: 100% trace context preservation
- **Measurement**: End-to-end trace validation

#### NFR-REL-03: Failure Isolation
- **Requirement**: Tracing failures don't affect business logic
- **Measurement**: Service availability with collector outage

### 4.4 Deployment

#### NFR-DEPLOY-01: Container Images
- **Requirement**: All components as Docker containers
- **Validation**: Single docker-compose file

#### NFR-DEPLOY-02: Configuration
- **Requirement**: Environment variable configuration
- **Validation**: No hardcoded values

#### NFR-DEPLOY-03: Startup Time
- **Requirement**: Full stack operational in <2 minutes
- **Measurement**: Time from docker-compose up to ready

### 4.5 Technology Constraints

#### NFR-TECH-01: Java Version
- **Requirement**: Java 17+ for all Java services
- **Rationale**: LTS version with modern features

#### NFR-TECH-02: Spring Boot Version
- **Requirement**: Spring Boot 3.2+ 
- **Rationale**: Native OpenTelemetry support

#### NFR-TECH-03: OpenTelemetry Version
- **Requirement**: OpenTelemetry Java Agent 1.32+
- **Rationale**: Stable auto-instrumentation

## 5. Java-Specific Requirements

### 5.1 Job Scheduler (Simplified Java Implementation)

#### JR-SCHED-01: Framework Selection
- **Requirement**: Use Spring Boot @Scheduled annotation
- **Rationale**: Simple, built-in, sufficient for demo

#### JR-SCHED-02: Job Implementation
- **Required Jobs**:
  - Single link expiration cleanup job (every 30 minutes)
  - Implemented in Analytics API service
  - Focus on trace propagation to Kafka

### 5.2 ~~WebSocket Server~~ (Not Implemented)

#### JR-WS-01: Framework Selection
- **Requirement**: Spring Boot with Spring WebSocket
- **Rationale**: Native Spring integration, STOMP support

#### JR-WS-02: Messaging Protocol
- **Requirement**: Support both raw WebSocket and STOMP
- **Rationale**: Flexibility for different client types

#### JR-WS-03: Features
- **Required Capabilities**:
  - Real-time link click notifications
  - Analytics dashboard updates
  - System status broadcasts
  - User-specific channels

## 6. Data Requirements

### 6.1 Trace Attributes

#### DR-01: Standard Attributes
All spans must include:
- `service.name`
- `service.version`
- `deployment.environment`
- `host.name`
- `process.pid`

#### DR-02: HTTP Attributes
HTTP spans must include:
- `http.method`
- `http.url`
- `http.status_code`
- `http.user_agent`
- `http.request.size`
- `http.response.size`

#### DR-03: Database Attributes
Database spans must include:
- `db.system`
- `db.name`
- `db.operation`
- `db.statement` (sanitized)

#### DR-04: Messaging Attributes
Message spans must include:
- `messaging.system`
- `messaging.destination`
- `messaging.operation`
- `messaging.message.id`

### 6.2 Business Context

#### DR-05: User Context
- `user.id`
- `user.tenant_id`
- `user.role`
- `user.subscription_tier`

#### DR-06: Request Context
- `request.id` (correlation ID)
- `request.client_id`
- `request.api_version`
- `request.feature_flags`

## 7. Testing Requirements

### 7.1 Trace Validation

#### TR-01: End-to-End Trace Tests
- **Requirement**: Automated tests validating complete traces
- **Coverage**: All critical user journeys

#### TR-02: Trace Propagation Tests
- **Requirement**: Verify context propagation across boundaries
- **Coverage**: All service integrations

#### TR-03: Failure Scenario Tests
- **Requirement**: Traces remain intact during failures
- **Coverage**: Network, service, and database failures

### 7.2 Performance Testing

#### TR-04: Load Testing with Tracing
- **Requirement**: Validate performance under load
- **Target**: 1000 concurrent users

#### TR-05: Sampling Validation
- **Requirement**: Verify sampling decisions
- **Validation**: Statistical sampling accuracy

## 8. Documentation Requirements

### 8.1 Technical Documentation

#### DOC-01: Architecture Diagrams
- Component interaction diagram
- Trace flow diagrams
- Deployment architecture

#### DOC-02: Configuration Guide
- Environment variables
- Sampling configuration
- Performance tuning

#### DOC-03: Troubleshooting Guide
- Common issues and solutions
- Trace debugging techniques
- Performance optimization

### 8.2 User Documentation

#### DOC-04: Demo Scenarios
- Step-by-step demo scripts
- Expected trace patterns
- Failure injection scenarios

#### DOC-05: Query Examples
- Jaeger query patterns
- Trace analysis techniques
- Performance investigation

## 9. Acceptance Criteria

### 9.1 Mandatory Criteria
- [ ] All components deployed via docker-compose
- [ ] Complete traces for all user journeys
- [ ] SPA initiates traceparent
- [ ] BFF establishes context from tokens
- [ ] Context propagated through all services
- [ ] Scheduled job maintains trace context
- [ ] Centralized logging with trace ID
- [ ] Cache operations traced

### 9.2 Demo Readiness
- [ ] 5 prepared demo scenarios
- [ ] Trace screenshots for documentation
- [ ] Performance baseline established
- [ ] Failure scenarios documented

## 10. Constraints and Assumptions

### 10.1 Constraints
- Must remain fully dockerized
- Cannot use cloud-specific services
- Must work on 16GB RAM machine
- Java 17+ only

### 10.2 Assumptions
- Development team familiar with Spring Boot
- Jaeger already configured and operational
- OpenTelemetry Collector deployed
- Existing services won't be modified significantly

## 11. Risks and Mitigation

| Risk | Probability | Impact | Mitigation |
|------|-------------|---------|------------|
| NGINX OpenTelemetry module compilation issues | Medium | High | Pre-built Docker image with module |
| Kong learning curve | Medium | Medium | Use declarative configuration |
| WebSocket trace propagation complexity | High | Medium | Implement custom propagator |
| Performance overhead exceeds limits | Low | High | Implement aggressive sampling |
| Trace data volume overwhelming | Medium | Medium | Tail-based sampling in collector |

## 12. Dependencies

### 12.1 External Dependencies
- OpenTelemetry Java Agent 1.32+
- Spring Boot 3.2+
- Quartz Scheduler 2.3+
- Spring WebSocket 6.1+
- NGINX OpenTelemetry module
- Kong 3.5+
- Redis 7+

### 12.2 Internal Dependencies
- Existing URL shortener services
- Kafka infrastructure
- PostgreSQL database
- Keycloak authentication

## 13. Timeline

| Phase | Duration | Deliverables |
|-------|----------|--------------|
| Phase 1: Edge & Gateway | 1 week | NGINX + Kong with tracing |
| Phase 2: Caching & External | 1 week | Redis + Mock services |
| Phase 3: Java Components | 1 week | Job Scheduler + WebSocket |
| Phase 4: Integration & Testing | 1 week | Full integration, testing, documentation |

## 14. Success Metrics

### 14.1 Technical Metrics
- Trace completeness: >99%
- Context propagation: 100%
- Performance overhead: <5%
- Deployment time: <2 minutes

### 14.2 Business Metrics
- Demo scenarios: 5+ prepared
- Documentation: 100% complete
- Team training: Completed
- Reference implementation: Published

---

**Document Version**: 1.0  
**Last Updated**: 2024-12-12  
**Status**: Draft for Review  
**Owner**: Platform Team