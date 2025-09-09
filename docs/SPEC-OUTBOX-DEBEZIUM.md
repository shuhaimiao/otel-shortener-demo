# Specification: Outbox Pattern with Debezium CDC and Trace Propagation

## 1. Overview

### Purpose
Implement the Transactional Outbox pattern with Change Data Capture (CDC) using Debezium to ensure reliable event publishing while maintaining end-to-end trace propagation through asynchronous boundaries.

### Goals
- Eliminate dual-write problem between database and message broker
- Ensure transactional consistency for domain events
- Maintain W3C trace context through CDC pipeline
- Demonstrate enterprise-grade event sourcing patterns
- Enable real-time event streaming without polling

### Scope
- Add outbox table to PostgreSQL database
- Modify URL API to write events to outbox
- Deploy Debezium connector for PostgreSQL
- Configure Kafka Connect with SMT for trace propagation
- Update Analytics API to process CDC events

## 2. Requirements

### Functional Requirements

#### FR1: Outbox Event Recording
- URL API MUST write domain events to outbox table atomically with business operations
- Each outbox entry MUST capture W3C trace context (trace_id, span_id, trace_flags)
- System MUST support multiple event types (LINK_CREATED, LINK_CLICKED, LINK_EXPIRED)

#### FR2: Change Data Capture
- Debezium MUST capture outbox table changes in real-time
- CDC pipeline MUST preserve event ordering per aggregate
- System MUST transform Debezium envelope to business events

#### FR3: Trace Context Propagation
- Original trace_id MUST flow from user request through CDC to analytics
- Kafka messages MUST include traceparent header in W3C format
- Analytics API MUST continue traces using propagated context

### Non-Functional Requirements

#### NFR1: Reliability
- Zero message loss during database to Kafka transfer
- At-least-once delivery guarantee
- Automatic retry on transient failures

#### NFR2: Performance
- CDC lag < 1 second under normal load
- Support 1000 events/second throughput
- Minimal impact on PostgreSQL performance

#### NFR3: Observability
- All CDC operations must generate trace spans
- Metrics for CDC lag, throughput, and errors
- Clear logging of event processing pipeline

## 3. Technical Design

### 3.1 Database Schema

```sql
-- Outbox table for transactional event publishing
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id VARCHAR(255) NOT NULL,      -- e.g., short_code
    aggregate_type VARCHAR(100) NOT NULL,    -- e.g., 'Link'
    event_type VARCHAR(100) NOT NULL,        -- e.g., 'LINK_CREATED'
    payload JSONB NOT NULL,                  -- Event data
    
    -- W3C Trace Context fields
    trace_id VARCHAR(32),                    -- 32 hex chars
    parent_span_id VARCHAR(16),              -- 16 hex chars  
    trace_flags VARCHAR(2),                  -- 2 hex chars
    trace_state TEXT,                        -- Optional vendor-specific
    
    -- Metadata
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    
    -- Processing status (for fallback polling if needed)
    processed_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'PENDING'     -- PENDING, PROCESSED, FAILED
);

CREATE INDEX idx_outbox_status_created ON outbox_events(status, created_at);
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);
```

### 3.2 Event Flow Architecture

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ HTTP + traceparent
       ▼
┌─────────────┐     Transaction ┌──────────────┐
│   URL API   │────────────────►│  PostgreSQL  │
│             │                  │              │
│ 1. Save Link│                  │ - links      │
│ 2. Write    │                  │ - outbox     │
│    Outbox   │                  └──────┬───────┘
└─────────────┘                         │ WAL
                                        │
                                        ▼
┌─────────────┐     ┌──────────────────────────┐
│  Analytics  │◄────│      Kafka Connect       │
│     API     │     │                          │
│             │     │ 1. Debezium Connector    │
│ Continue    │     │ 2. SMT: ExtractTrace     │
│ Trace from  │     │ 3. SMT: OutboxRouter     │
│ Headers     │     └──────────┬───────────────┘
└─────────────┘                │
       ▲                       ▼
       │           ┌──────────────────┐
       └───────────│      Kafka        │
         traceparent│                  │
         in headers │  link-events     │
                   └──────────────────┘
```

### 3.3 Debezium Configuration

```json
{
  "name": "outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "postgres",
    "database.port": "5432",
    "database.user": "otel_user",
    "database.password": "otel_password",
    "database.dbname": "otel_shortener_db",
    "database.server.name": "postgres",
    "table.include.list": "public.outbox_events",
    "plugin.name": "pgoutput",
    "publication.autocreate.mode": "filtered",
    
    "transforms": "outbox,extractTrace,unwrap",
    
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.route.by.field": "event_type",
    "transforms.outbox.route.topic.replacement": "link-events",
    
    "transforms.extractTrace.type": "com.example.smt.ExtractTraceContext",
    "transforms.extractTrace.trace.id.field": "trace_id",
    "transforms.extractTrace.span.id.field": "parent_span_id",
    "transforms.extractTrace.trace.flags.field": "trace_flags",
    
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false"
  }
}
```

### 3.4 Custom SMT for Trace Propagation

```java
// Custom Single Message Transform to add trace context to headers
public class ExtractTraceContext<R extends ConnectRecord<R>> 
    implements Transformation<R> {
    
    @Override
    public R apply(R record) {
        Struct value = (Struct) record.value();
        
        String traceId = value.getString("trace_id");
        String spanId = value.getString("parent_span_id");
        String traceFlags = value.getString("trace_flags");
        
        if (traceId != null && spanId != null) {
            // Build W3C traceparent header
            String traceparent = String.format("00-%s-%s-%s",
                traceId, spanId, 
                traceFlags != null ? traceFlags : "01");
            
            // Add to Kafka headers
            Headers headers = record.headers();
            headers.addString("traceparent", traceparent);
        }
        
        return record;
    }
}
```

### 3.5 Application Changes

#### URL API - LinkController
```java
@Transactional
public LinkResponse createLink(CreateLinkRequest request) {
    // 1. Create and save link
    Link link = new Link(generateShortCode(), request.getUrl());
    linkRepository.save(link);
    
    // 2. Create outbox event with trace context
    OutboxEvent event = OutboxEvent.builder()
        .aggregateId(link.getShortCode())
        .aggregateType("Link")
        .eventType("LINK_CREATED")
        .payload(Map.of(
            "shortCode", link.getShortCode(),
            "originalUrl", link.getOriginalUrl(),
            "createdBy", getCurrentUser()
        ))
        .traceId(Span.current().getSpanContext().getTraceId())
        .parentSpanId(Span.current().getSpanContext().getSpanId())
        .traceFlags(Span.current().getSpanContext().getTraceFlags())
        .build();
    
    outboxRepository.save(event);
    
    // Both operations committed atomically
    return new LinkResponse(link.getShortCode());
}
```

## 4. Implementation Plan

### Phase 1: Outbox Pattern (Week 1)
1. Create outbox table schema
2. Implement OutboxEvent entity and repository
3. Modify LinkController to write to outbox
4. Test transactional consistency

### Phase 2: Debezium Setup (Week 2)
1. Configure PostgreSQL for logical replication
2. Deploy Kafka Connect with Debezium
3. Create and deploy custom SMT
4. Configure Debezium PostgreSQL connector

### Phase 3: Integration & Testing (Week 3)
1. Update Analytics API to handle CDC events
2. Verify trace propagation end-to-end
3. Performance testing
4. Documentation and demo preparation

## 5. Testing Strategy

### Unit Tests
- OutboxRepository operations
- Custom SMT trace extraction
- Event payload serialization

### Integration Tests
- Transactional consistency (link + outbox)
- CDC event capture and transformation
- Trace context propagation through pipeline

### E2E Tests
- Create link → CDC → Analytics flow
- Trace continuity verification in Jaeger
- Failure recovery scenarios

## 6. Success Criteria

- [ ] Zero message loss during normal operations
- [ ] Trace context preserved through CDC pipeline
- [ ] CDC lag < 1 second average
- [ ] All events visible in Jaeger with correct parent-child relationships
- [ ] Successful demo of enterprise patterns

## 7. Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| PostgreSQL WAL growth | High disk usage | Configure appropriate WAL retention |
| Debezium connector failures | Event delivery stops | Implement monitoring and alerts |
| Custom SMT bugs | Trace context lost | Thorough testing, fallback to new traces |
| Performance degradation | Slow response times | Load testing, connection pooling |

## 8. Future Enhancements

1. **Outbox Cleanup**: Scheduled job to purge processed events
2. **Dead Letter Queue**: Handle permanently failed events
3. **Multi-tenant Support**: Tenant-specific event routing
4. **Event Versioning**: Schema evolution support
5. **Distributed Tracing**: Span links for related events

## 9. References

- [Debezium Outbox Pattern](https://debezium.io/documentation/reference/transformations/outbox-event-router.html)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [Kafka Connect SMT](https://kafka.apache.org/documentation/#connect_transforms)
- [PostgreSQL Logical Replication](https://www.postgresql.org/docs/current/logical-replication.html)