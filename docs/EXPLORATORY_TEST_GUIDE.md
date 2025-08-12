# Exploratory Test Guide for E2E Traceability

This guide provides step-by-step instructions for testing the end-to-end traceability features implemented in the otel-shortener-demo application.

## Prerequisites

1. Docker and Docker Compose installed
2. Postman or curl for API testing
3. Web browser for UI testing
4. Terminal access for log monitoring

## Test Environment Setup

### 1. Start All Services

```bash
# Start all services
docker-compose up -d

# Verify all services are running
docker-compose ps

# Check health status
docker-compose ps | grep healthy
```

### 2. Access Points

- **Frontend**: http://localhost:3000
- **NGINX Edge**: http://localhost
- **BFF API**: http://localhost:3001/api
- **Jaeger UI**: http://localhost:16686
- **Redis**: localhost:6379
- **Keycloak**: http://localhost:8880/auth

### 3. Monitor Logs

Open multiple terminals to monitor different aspects:

```bash
# Terminal 1: Watch shared logs
tail -f logs/shared/all-services.log

# Terminal 2: Watch specific service
tail -f logs/url-api/url-api.log

# Terminal 3: Monitor Docker logs
docker-compose logs -f bff url-api redirect-service
```

## Test Scenarios

### Test 1: Trace Propagation Through Full Stack

**Objective**: Verify trace context propagates from frontend through all services

**Steps**:
1. Open browser developer tools (Network tab)
2. Navigate to http://localhost:3000
3. Create a short link using the UI
4. Observe in Network tab:
   - Request should have `traceparent` header
   - Format: `00-{trace-id}-{span-id}-01`

**Verification**:
1. Check Jaeger UI (http://localhost:16686)
   - Search for the trace ID from the header
   - Should see spans from: Frontend → NGINX → BFF → URL-API
2. Check logs for same trace ID:
   ```bash
   grep "{trace-id}" logs/shared/all-services.log
   ```
3. Verify each service logs contain:
   - TraceId matching the original
   - UserId and TenantId from context
   - Correct transaction name

**Expected Result**:
- Single trace visible in Jaeger spanning all services
- Consistent trace ID in all service logs
- Context headers properly propagated

### Test 2: Context Establishment and Caching

**Objective**: Verify context is extracted from JWT and cached in Redis

**Steps**:
1. Send request with Bearer token to BFF:
   ```bash
   curl -X POST http://localhost:3001/api/links \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer test-token-123" \
     -d '{"url": "https://example.com"}'
   ```

2. Check Redis for cached context:
   ```bash
   docker exec -it otel-redis redis-cli
   > KEYS token:*
   > GET token:test-token-123
   ```

3. Send same request again and monitor logs

**Verification**:
1. First request logs should show:
   - "Context cache miss"
   - "Context cached for user"
2. Second request logs should show:
   - "Context cache hit"
3. Redis should contain the cached context

**Expected Result**:
- Context successfully cached on first request
- Cache hit on subsequent requests with same token
- TTL set appropriately on cached items

### Test 3: MDC Logging Integration

**Objective**: Verify MDC fields are properly populated in Java service logs

**Steps**:
1. Create a short link through the UI
2. Note the trace ID from browser network tab
3. Check Java service logs

**Verification**:
```bash
# Check url-api logs
grep "TraceId=" logs/url-api/url-api.log | tail -5

# Check redirect-service logs  
grep "UserId=" logs/redirect-service/redirect-service.log | tail -5
```

**Expected Log Format**:
```
2025-01-12 10:30:45.123 [http-nio-8080-exec-1] INFO  c.e.u.LinkController - TraceId=abc123 UserId=user-123 TenantId=tenant-456 Transaction=POST /links CorrelationId=xyz789 - Creating short link for URL: https://example.com
```

### Test 4: Scheduled Job Trace Propagation

**Objective**: Verify scheduled jobs create traces and propagate context to Kafka

**Steps**:
1. Wait for scheduled job to run (every 30 seconds for expired links check)
2. Monitor logs:
   ```bash
   grep "scheduled.check-expired-links" logs/url-api/url-api.log
   ```
3. Check Jaeger for scheduled job traces

**Verification**:
1. Log should show:
   - "Starting scheduled job: check-expired-links"
   - Job ID and trace ID
   - "Published expired link event" (if any expired links found)
2. Jaeger should show:
   - Parent span: `scheduled.check-expired-links`
   - Child spans: `kafka.publish.expired-link` (if events published)

**Expected Result**:
- Scheduled jobs generate new trace contexts
- Trace context propagated to Kafka messages
- Job execution visible in distributed trace

### Test 5: Rate Limiting at Edge

**Objective**: Verify NGINX rate limiting works correctly

**Steps**:
1. Send rapid requests to NGINX:
   ```bash
   for i in {1..20}; do
     curl -X GET http://localhost/api/health &
   done
   wait
   ```

2. Check NGINX logs:
   ```bash
   docker logs otel-nginx 2>&1 | grep "limiting requests"
   ```

**Expected Result**:
- Some requests return 429 status
- Rate limit log entries in NGINX
- Trace still propagated even for rate-limited requests

### Test 6: Service-to-Service Authentication

**Objective**: Verify M2M token handling between BFF and backend services

**Steps**:
1. Send request without token:
   ```bash
   curl -X POST http://localhost:3001/api/links \
     -H "Content-Type: application/json" \
     -d '{"url": "https://example.com"}'
   ```

2. Monitor BFF and url-api logs

**Verification**:
- BFF should establish anonymous context
- BFF should acquire M2M token (placeholder)
- URL-API should validate M2M token
- Request should be rejected if no valid token

### Test 7: Centralized Logging

**Objective**: Verify all services write to shared log file

**Steps**:
1. Trigger actions in different services
2. Check shared log file:
   ```bash
   tail -f logs/shared/all-services.log
   ```

**Verification**:
- Logs from all services appear in chronological order
- Each log entry includes service identifier
- Trace IDs allow correlation across services

### Test 8: Cache-Aside Pattern

**Objective**: Verify Redis caching for performance optimization

**Steps**:
1. Monitor Redis commands:
   ```bash
   docker exec -it otel-redis redis-cli MONITOR
   ```

2. Send multiple requests with same token
3. Observe Redis operations

**Expected Redis Commands**:
```
GET token:test-token
SETEX token:test-token 900 {json-context}
GET token:test-token (cache hit)
```

### Test 9: Error Propagation

**Objective**: Verify errors are properly traced and logged

**Steps**:
1. Send malformed request:
   ```bash
   curl -X POST http://localhost:3001/api/links \
     -H "Content-Type: application/json" \
     -d '{"invalid": "data"}'
   ```

2. Check error handling in traces and logs

**Verification**:
- Error visible in Jaeger trace
- Error details in MDC logs
- Proper error response to client

### Test 10: Analytics Event Flow

**Objective**: Verify analytics scheduled job publishes events

**Steps**:
1. Wait for analytics job (runs every 60 seconds)
2. Check logs:
   ```bash
   grep "generate-analytics" logs/url-api/url-api.log
   ```

3. Monitor Kafka topics (if Kafka UI available)

**Expected Result**:
- Analytics report generated
- Event published to analytics-events topic
- Trace shows job execution and Kafka publish

## Performance Testing

### Load Test Scenario

Test system behavior under load:

```bash
# Install Apache Bench if not available
# Run load test
ab -n 1000 -c 10 -H "Authorization: Bearer test-token" \
   -T "application/json" \
   -p test-data.json \
   http://localhost:3001/api/links
```

Monitor:
- Response times in Jaeger
- Cache hit rates in Redis
- Log volume and MDC field consistency

## Troubleshooting Checklist

### If Traces Don't Appear:
1. Check OpenTelemetry collector is running
2. Verify OTEL environment variables in docker-compose
3. Check network connectivity between services
4. Review collector configuration

### If Context Not Propagated:
1. Verify headers in browser network tab
2. Check middleware execution order
3. Review context establishment logs
4. Validate Redis connectivity

### If Logs Missing MDC Fields:
1. Check MdcContextFilter is registered
2. Verify filter order (@Order(1))
3. Review logback configuration
4. Check MDC.clear() not called prematurely

## Summary Metrics to Track

After testing, verify these metrics:

1. **Trace Completeness**: 100% of requests have complete traces
2. **Cache Hit Rate**: >80% after warm-up
3. **Log Correlation**: All logs contain trace ID
4. **Context Propagation**: All services receive context headers
5. **Job Execution**: Scheduled jobs run on schedule
6. **Error Tracking**: All errors appear in traces

## Cleanup

After testing:

```bash
# Stop all services
docker-compose down

# Clean up volumes (optional)
docker-compose down -v

# Remove logs
rm -rf logs/*
```

## Additional Resources

- [OpenTelemetry Documentation](https://opentelemetry.io/docs/)
- [W3C Trace Context Specification](https://www.w3.org/TR/trace-context/)
- [Jaeger UI Guide](https://www.jaegertracing.io/docs/latest/frontend-ui/)
- [Docker Compose Commands](https://docs.docker.com/compose/reference/)