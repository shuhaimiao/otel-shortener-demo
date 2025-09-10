# End-to-End Trace Propagation Test Procedure

Comprehensive test scenarios to verify distributed tracing across all services and boundaries.

## Prerequisites
- Docker Compose environment running
- All services healthy (verify with `docker-compose ps`)
- Jaeger UI accessible at http://localhost:16686
- Browser with Developer Tools

## Test Scenarios

### Scenario 1: Complete Link Creation Flow ✅

**Objective**: Verify trace propagation from frontend through CDC to analytics

#### Steps:
1. **Create a link**
   ```bash
   # Open frontend
   http://localhost:3000
   
   # Enter URL: https://test-e2e-trace.com
   # Click "Shorten"
   ```

2. **Capture trace ID**
   - Open DevTools (F12) → Network tab
   - Find POST to `/api/links`
   - Copy `traceparent` from response headers
   - Extract trace ID (32 chars after "00-")

3. **Verify in Jaeger**
   - Open http://localhost:16686
   - Search for trace ID
   - Expected spans:
     ```
     frontend: HTTP POST
     └── bff: POST /api/links
         └── url-api: POST /links
             ├── HikariDataSource.getConnection
             ├── SELECT links (duplicate check)
             ├── INSERT links
             ├── INSERT outbox_events (with trace context)
             └── analytics-api: kafka.consume.link-events
     ```

4. **Verify trace context in database**
   ```sql
   docker exec -it otel-shortener-demo-postgres-1 psql -U otel_user -d otel_shortener_db -c \
   "SELECT trace_id, parent_span_id, event_type FROM outbox_events ORDER BY created_at DESC LIMIT 1;"
   ```

### Scenario 2: URL Redirect Flow ⚠️

**Objective**: Test redirect service trace propagation

#### Steps:
1. **Use a shortened URL**
   - Copy a short URL from Scenario 1
   - Open in new browser tab
   - Verify redirect occurs

2. **Find trace in Jaeger**
   - Service: `redirect-service`
   - Operation: `GET /{shortCode}`
   - Expected spans:
     ```
     redirect-service: GET /{shortCode}
     └── SELECT otel_shortener_db.links (R2DBC lookup)
     ```

> **Note**: Click event publishing to Kafka is TODO - trace ends at redirect

### Scenario 3: Scheduled Jobs Tracing 🕐

**Objective**: Verify scheduled jobs maintain trace context

#### Steps:
1. **Wait for scheduled execution**
   - Link expiration check: Every 30 seconds
   - Analytics report: Every 60 seconds

2. **Check url-api logs**
   ```bash
   docker-compose logs url-api | grep -E "Checking expired links|Generating analytics"
   ```

3. **Find scheduled job traces in Jaeger**
   - Service: `url-api`
   - Operation: Contains "scheduled"
   - Verify trace propagates to analytics-api

### Scenario 4: Cache Hit/Miss Testing 💾

**Objective**: Verify Redis cache integration in BFF

#### Steps:
1. **Create same link twice**
   - First request: Cache miss
   - Second request: Cache hit (context from Redis)

2. **Check Redis**
   ```bash
   docker exec -it otel-redis redis-cli
   KEYS context:*
   TTL context:user:123:tenant:456
   ```

3. **Verify in traces**
   - First trace shows context establishment
   - Second trace shows faster response

## Performance Testing 🚀

### Load Test Scenario
```bash
# Generate 100 requests
for i in {1..100}; do
  curl -X POST http://localhost:3001/api/links \
    -H "Content-Type: application/json" \
    -d "{\"url\":\"https://example.com/test-$i\"}" &
done
wait

# Check Jaeger for trace patterns
# Look for slowest traces, errors, bottlenecks
```

## Verification Checklist ✓

- [ ] Frontend generates valid W3C traceparent
- [ ] BFF preserves and forwards trace context
- [ ] URL API saves trace to outbox table
- [ ] Debezium captures trace from outbox
- [ ] Kafka headers contain trace context
- [ ] Analytics API continues the trace
- [ ] MDC logs show correlated trace IDs
- [ ] Jaeger displays complete trace flow
- [ ] Scheduled jobs create new traces
- [ ] Redis caches context with TTL

## Common Issues & Solutions

### Issue: Broken Trace Chain
**Symptom**: Analytics-api trace disconnected from main flow
**Solution**: Verify outbox table has trace_id populated
```sql
SELECT trace_id, parent_span_id FROM outbox_events 
WHERE trace_id IS NOT NULL LIMIT 5;
```

### Issue: Missing Kafka Headers
**Symptom**: No trace context in Kafka messages
**Solution**: Check Debezium connector configuration
```bash
curl http://localhost:8083/connectors/postgres-outbox-connector/config | jq .
```

### Issue: Slow Traces
**Symptom**: Traces taking >1 second
**Possible Causes**:
- Cold start (first request)
- Database connection pool exhausted
- Kafka consumer lag

## Trace Context Flow Diagram

```
[Frontend]
    |
    | traceparent: 00-{traceId}-{spanId}-01
    ↓
[BFF] → [Redis Cache]
    |
    | + X-User-ID, X-Tenant-ID
    ↓
[URL API]
    |
    |→ [PostgreSQL: links]
    |→ [PostgreSQL: outbox_events]
         |
         | trace_id, parent_span_id columns
         ↓
    [Debezium CDC]
         |
         | Kafka headers: trace_id, parent_span_id
         ↓
    [Kafka Topic]
         |
         ↓
[Analytics API]
    |
    | Reconstructs traceparent
    ↓
[Continued Trace]
```

## Success Metrics

✅ **Complete E2E trace** visible in Jaeger
✅ **Trace context preserved** through async boundaries  
✅ **MDC correlation** in all Java service logs
✅ **Sub-second latency** for link creation
✅ **100% trace continuity** (no orphaned spans)