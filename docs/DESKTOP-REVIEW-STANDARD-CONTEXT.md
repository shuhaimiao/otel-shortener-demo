# Desktop Review: Standard Context Headers Implementation

## Review Date: 2025-01-10
## Status: APPROVED

## Executive Summary

This document captures the design decisions for implementing standard context headers in the otel-shortener-demo. The implementation will demonstrate how to propagate business context (user, tenant, request metadata) alongside technical trace context for enhanced observability.

## Approved Decisions

### Decision 1: Core Context Headers (Option A - Minimal Set)
**Decision**: Implement 5 core headers for the reference implementation

```yaml
Core Headers:
  X-Request-ID:      Unique request identifier
  X-User-ID:         User identifier  
  X-Tenant-ID:       Multi-tenant identifier
  X-Service-Name:    Originating service
  X-Transaction-Type: Business operation type
```

**Rationale**: 
- Demonstrates the pattern without overwhelming complexity
- These 5 are universal across all services
- Easy to extend based on specific needs

**Note**: The adoption guide will document all three categories (Core, Extended, Environment) for teams to choose from based on their needs.

### Decision 2: Header Establishment Strategy (Option C - Immutable After BFF)
**Decision**: Context is established at BFF and remains immutable downstream

```javascript
// BFF establishes all context
const context = establishContext(req);
Object.freeze(context);

// Downstream services can only read, not modify
```

**Rationale**:
- BFF is the security boundary where context is validated
- Prevents context tampering downstream
- Simpler mental model with one-way flow

### Decision 3: Kafka/CDC Propagation (Option B - JSON Context Column)
**Decision**: Store context as JSONB in outbox table

```sql
ALTER TABLE outbox_events ADD COLUMN context JSONB;
-- Example: {"user_id": "123", "tenant_id": "acme", "request_id": "req-456", ...}
```

**Rationale**:
- **Extensibility**: Easy to add new context fields without schema changes
- **Flexibility**: Different events can have different context fields
- **Real-world alignment**: Matches common enterprise patterns
- **Schema evolution**: No migration needed for new headers

### Decision 4: MDC/Logging Integration (Option B - Selective MDC Population)
**Decision**: Selectively populate MDC with specific context fields

```java
private static final Set<String> MDC_HEADERS = Set.of(
    "X-User-ID", "X-Tenant-ID", "X-Request-ID", 
    "X-Service-Name", "X-Transaction-Type"
);
```

**Rationale**:
- Avoids MDC pollution
- Clear logging boundaries
- Better performance
- Easier PII auditing

### Decision 5: Implementation Order (Option C - Middle-Out)
**Decision**: Start with BFF → url-api flow, then expand

1. BFF establishes and forwards headers
2. url-api consumes and logs context
3. Verify end-to-end with logs
4. Expand to other services
5. Add Kafka propagation last

**Rationale**:
- Early validation of the pattern
- Minimal initial change
- Reduces risk
- Kafka complexity handled last

### Decision 6: Testing Strategy (Option B + C Combined)
**Decision**: Automated integration tests + trace validation

- Integration tests verify functional propagation
- Trace validation ensures observability
- Both manual and automated verification

**Rationale**:
- Comprehensive validation
- Regression prevention
- Documentation through tests

## Implementation Roadmap

### Phase 1: Core HTTP Propagation (BFF → url-api)
1. **Update BFF context middleware**
   - Extract/mock user context from JWT
   - Set 5 core headers
   - Make context immutable

2. **Update url-api MDC filter**
   - Extract headers to MDC
   - Update logback pattern
   - Verify in logs

3. **Manual verification**
   - Send test request
   - Check logs for context fields

### Phase 2: Database Integration
1. **Update outbox schema**
   ```sql
   ALTER TABLE outbox_events ADD COLUMN context JSONB;
   ```

2. **Modify LinkService**
   - Populate context column from MDC
   - Store as JSON

### Phase 3: Kafka Propagation
1. **Configure Debezium transformation**
   - Extract context JSON fields
   - Map to Kafka headers

2. **Update analytics-api consumer**
   - Extract context from headers
   - Populate MDC
   - Log with context

### Phase 4: Complete Coverage
1. **Add redirect-service support**
   - Reactive context extraction
   - WebFlux MDC bridge

2. **Frontend context initialization**
   - Add X-Request-ID generation
   - Include in fetch requests

### Phase 5: Testing & Documentation
1. **Integration tests**
   - Context propagation verification
   - Trace validation

2. **Update documentation**
   - Context flow diagrams
   - Configuration guide

## Success Criteria

- [ ] Context headers visible in all service logs
- [ ] Context preserved through Kafka/CDC boundary
- [ ] Integration tests passing
- [ ] Context visible in Jaeger traces
- [ ] Documentation updated

## Risk Mitigation

1. **Performance Impact**: JSON parsing overhead
   - Mitigation: Cache parsed context, use efficient JSON library

2. **Schema Evolution**: Adding new fields
   - Mitigation: JSON flexibility allows additions without migration

3. **PII Concerns**: Sensitive data in logs
   - Mitigation: Selective MDC population, configurable filtering

## Next Steps

1. Create feature branch for implementation
2. Implement Phase 1 (BFF → url-api)
3. Verify with manual testing
4. Proceed with subsequent phases

## Appendix: Context Field Categories

For reference in adoption guide:

### Core (Minimal - Reference Implementation)
- X-Request-ID
- X-User-ID  
- X-Tenant-ID
- X-Service-Name
- X-Transaction-Type

### Extended (Common Enterprise)
- X-User-Email
- X-User-Roles
- X-User-Groups
- X-Tenant-Name
- X-Tenant-Tier
- X-Session-ID
- X-Correlation-ID
- X-API-Version

### Environment (Advanced)
- X-Environment
- X-Region
- X-Feature-Flags
- X-Client-IP
- X-Device-Type
- X-Client-Version