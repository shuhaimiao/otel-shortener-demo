# Desktop Review: Outbox Pattern with Debezium CDC

## Overview
Before implementing the Outbox Pattern with Debezium CDC, we need to review and align on key architecture decisions. This review covers 6 critical decisions that will impact the implementation.

---

## DECISION #1: Outbox Table Scope

**Context**: We need to decide which events should go through the outbox pattern vs direct Kafka publishing.

**Options**:

**Option A: All Events Through Outbox**
- Pros: Consistent pattern, guaranteed delivery for all events, single code path
- Cons: Additional latency for all events, more database writes

**Option B: Critical Events Only (Selected)**
- Pros: Balance between reliability and performance, selective use for important events
- Cons: Two code paths to maintain, developers must decide per event

**Option C: Async Events Only**
- Pros: Synchronous operations remain fast, async gets reliability
- Cons: Inconsistent patterns, potential confusion

**Recommendation**: Option B - Use outbox for critical business events (LINK_CREATED, LINK_EXPIRED) while keeping high-volume, low-criticality events (LINK_CLICKED) as direct Kafka publishes.

**Question**: Do you agree with using the outbox pattern selectively for critical events only?

---

## DECISION #2: Trace Context Storage Format

**Context**: W3C trace context needs to be stored in the outbox table for propagation through CDC.

**Options**:

**Option A: Separate Columns (Selected)**
- Pros: Clean schema, easy querying, type safety, direct mapping
- Cons: Multiple nullable columns

**Option B: JSON Column**
- Pros: Flexible, single column, extensible
- Cons: No schema validation, harder to query, parsing overhead

**Option C: Single Concatenated String**
- Pros: Minimal columns, matches W3C format
- Cons: Parsing required, no validation, harder to query individual components

**Recommendation**: Option A - Store trace_id, parent_span_id, trace_flags as separate VARCHAR columns for clarity and type safety.

**Question**: Do you agree with storing trace context as separate columns?

---

## DECISION #3: CDC Tool Selection

**Context**: Choosing between Debezium and alternatives for Change Data Capture.

**Options**:

**Option A: Debezium (Selected)**
- Pros: Mature, PostgreSQL support, built-in outbox pattern, active community
- Cons: Requires Kafka Connect, additional infrastructure

**Option B: Custom CDC with LISTEN/NOTIFY**
- Pros: Simpler infrastructure, full control
- Cons: More code to maintain, less reliable, no built-in features

**Option C: AWS DMS or Cloud CDC**
- Pros: Managed service, less operational overhead
- Cons: Vendor lock-in, cost, less control

**Recommendation**: Option A - Use Debezium for its maturity, PostgreSQL support, and built-in outbox event router.

**Question**: Do you agree with using Debezium for CDC?

---

## DECISION #4: SMT Strategy for Trace Propagation

**Context**: How to transform trace context from database columns to Kafka headers.

**Options**:

**Option A: Custom SMT (Selected)**
- Pros: Full control, exact W3C format, reusable
- Cons: Must build and maintain, deployment complexity

**Option B: Chain Built-in SMTs**
- Pros: No custom code, use existing transforms
- Cons: Complex configuration, may not achieve exact format

**Option C: Application-level Transform**
- Pros: Business logic stays in app, easier debugging
- Cons: Defeats purpose of CDC, requires app deployment

**Recommendation**: Option A - Build custom SMT for precise W3C traceparent header generation.

**Question**: Do you agree with building a custom SMT for trace header transformation?

---

## DECISION #5: Outbox Cleanup Strategy

**Context**: Processed outbox events accumulate and need cleanup.

**Options**:

**Option A: Scheduled Database Job**
- Pros: Runs in database, no app changes
- Cons: Database load, stored procedure maintenance

**Option B: Application Scheduled Task (Selected)**
- Pros: Business logic in app, monitoring integration, flexible rules
- Cons: Requires app resources, must handle failures

**Option C: TTL/Partition Drop**
- Pros: Automatic, efficient, no processing needed
- Cons: PostgreSQL doesn't have native TTL, partitioning complexity

**Recommendation**: Option B - Use Spring @Scheduled task to clean processed events older than 7 days.

**Question**: Do you agree with application-managed cleanup of old outbox events?

---

## DECISION #6: Topic Routing Strategy

**Context**: How Debezium routes outbox events to Kafka topics.

**Options**:

**Option A: Event Type to Topic Mapping (Selected)**
- Pros: Clean topic separation, easier consumption, natural routing
- Cons: Multiple topics to manage

**Option B: Single Topic with Event Type in Message**
- Pros: Simple topic management, one subscription
- Cons: Consumers must filter, mixed concerns

**Option C: Aggregate-based Topics**
- Pros: Domain-driven design, clear boundaries
- Cons: Cross-aggregate events unclear, more topics

**Recommendation**: Option A - Route by event_type field: LINK_CREATED → link-events, CLICK_RECORDED → click-events

**Question**: Do you agree with event-type-based topic routing?

---

## Summary of Decisions

1. **Outbox Scope**: Critical events only (selective approach)
2. **Trace Storage**: Separate columns for trace components
3. **CDC Tool**: Debezium with PostgreSQL connector
4. **SMT Strategy**: Custom SMT for W3C header generation
5. **Cleanup**: Application-scheduled cleanup task
6. **Topic Routing**: Event-type to topic mapping

## Next Steps

After alignment on these decisions:
1. Implement outbox table (schema already designed)
2. Create OutboxEvent entity and repository
3. Build and deploy custom SMT
4. Setup Debezium infrastructure
5. Modify URL API to use outbox
6. Test end-to-end trace propagation

## Risks to Monitor

1. **PostgreSQL WAL growth** - Monitor disk usage
2. **Debezium lag** - Set up alerting for CDC delays
3. **Custom SMT bugs** - Thorough testing required
4. **Topic creation** - Ensure auto-creation or pre-create topics

---

Please review each decision and provide your alignment or suggest modifications.