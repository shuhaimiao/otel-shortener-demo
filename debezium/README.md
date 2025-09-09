# Debezium CDC Setup for Outbox Pattern

This directory contains the configuration and scripts for setting up Debezium Change Data Capture (CDC) with the transactional outbox pattern.

## Overview

The setup implements CDC to capture events from the `outbox_events` table and publish them to Kafka while preserving W3C trace context for distributed tracing.

## Architecture

```
PostgreSQL (outbox_events) 
    ↓ (WAL/Logical Replication)
Debezium PostgreSQL Connector
    ↓ (EventRouter Transform)
Kafka Connect
    ↓ (Headers: trace_id, parent_span_id, trace_flags)
Kafka Topic (link-events)
    ↓
Analytics API (continues trace)
```

## Quick Start

### 1. Start Infrastructure

```bash
# Start all services including Kafka Connect
docker-compose up -d postgres kafka zookeeper kafka-connect

# Wait for services to be ready
docker-compose ps

# Check Kafka Connect health
curl http://localhost:8083/connector-plugins | jq
```

### 2. Register Debezium Connector

```bash
# Run the registration script
./debezium/register-connector-simple.sh

# Or if Kafka Connect is not on localhost
./debezium/register-connector-simple.sh kafka-connect 8083
```

### 3. Verify Setup

```bash
# Check connector status
curl http://localhost:8083/connectors/postgres-outbox-connector/status | jq

# List all connectors
curl http://localhost:8083/connectors | jq

# View connector configuration
curl http://localhost:8083/connectors/postgres-outbox-connector/config | jq
```

## How It Works

### 1. Outbox Table Structure

Events are written to `outbox_events` with trace context:

```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id VARCHAR(255),      -- Used as Kafka message key
    event_type VARCHAR(100),        -- Determines topic routing
    payload JSONB,                  -- Event data
    trace_id VARCHAR(32),           -- W3C trace ID
    parent_span_id VARCHAR(16),     -- Parent span ID
    trace_flags VARCHAR(2),         -- Trace flags (01 = sampled)
    -- ... other fields
);
```

### 2. Debezium Configuration

The connector uses the EventRouter SMT to:
- Extract events from the outbox table
- Route by `event_type` to appropriate topics
- Place trace fields in Kafka headers
- Expand JSON payload

Key configuration:
```json
{
  "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
  "transforms.outbox.table.fields.additional.placement": 
    "trace_id:header,parent_span_id:header,trace_flags:header"
}
```

### 3. Trace Context Propagation

The trace context flows as:
1. **Application**: Captures current span context when writing to outbox
2. **Debezium**: Extracts trace fields and adds as Kafka headers
3. **Consumer**: Reconstructs W3C traceparent from headers
4. **Result**: Complete distributed trace across async boundaries

## Monitoring

### Check CDC Lag

```bash
# View oldest pending event
docker exec -it otel-shortener-demo-postgres-1 psql -U otel_user -d otel_shortener_db -c \
  "SELECT id, event_type, created_at FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at LIMIT 1;"
```

### View Kafka Messages

```bash
# Consume from link-events topic
docker exec -it otel-shortener-demo-kafka-1 \
  kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic link-events \
  --from-beginning \
  --property print.headers=true
```

### Check Replication Slot

```bash
# View replication slots
docker exec -it otel-shortener-demo-postgres-1 psql -U otel_user -d otel_shortener_db -c \
  "SELECT slot_name, active, restart_lsn FROM pg_replication_slots;"
```

## Troubleshooting

### Connector Fails to Start

1. Check PostgreSQL WAL level:
```sql
SHOW wal_level;  -- Should be 'logical'
```

2. Check user permissions:
```sql
ALTER USER otel_user REPLICATION;
```

3. View connector logs:
```bash
docker logs otel-kafka-connect
```

### No Events in Kafka

1. Verify outbox has events:
```sql
SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING';
```

2. Check connector tasks:
```bash
curl http://localhost:8083/connectors/postgres-outbox-connector/tasks | jq
```

3. Restart connector:
```bash
curl -X POST http://localhost:8083/connectors/postgres-outbox-connector/restart
```

### Trace Context Not Propagating

1. Verify trace fields are populated:
```sql
SELECT trace_id, parent_span_id, trace_flags 
FROM outbox_events 
WHERE trace_id IS NOT NULL 
LIMIT 5;
```

2. Check Kafka headers:
```bash
# Headers should include trace_id, parent_span_id, trace_flags
docker exec -it otel-shortener-demo-kafka-1 \
  kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic link-events \
  --from-beginning \
  --property print.headers=true \
  --max-messages 1
```

## Custom SMT (Optional)

For more control over trace header format, build and deploy the custom SMT:

```bash
# Build custom SMT
cd debezium/custom-smt
./build.sh

# The JAR will be mounted to Kafka Connect container
# Update connector config to use:
# "transforms.trace.type": "com.example.connect.transforms.TraceContextHeaderTransform"
```

## Cleanup

```bash
# Delete connector (preserves data)
curl -X DELETE http://localhost:8083/connectors/postgres-outbox-connector

# Drop replication slot (if needed)
docker exec -it otel-shortener-demo-postgres-1 psql -U otel_user -d otel_shortener_db -c \
  "SELECT pg_drop_replication_slot('debezium_outbox');"

# Clear processed events
docker exec -it otel-shortener-demo-postgres-1 psql -U otel_user -d otel_shortener_db -c \
  "DELETE FROM outbox_events WHERE status = 'PROCESSED' AND processed_at < NOW() - INTERVAL '1 hour';"
```

## Configuration Files

- `connector-config.json` - Full Debezium connector configuration
- `simplified-connector-config.json` - Simplified configuration using built-in transforms
- `register-connector-simple.sh` - Script to register the connector
- `custom-smt/` - Optional custom Single Message Transform for W3C traceparent header

## References

- [Debezium Outbox Event Router](https://debezium.io/documentation/reference/transformations/outbox-event-router.html)
- [PostgreSQL Logical Replication](https://www.postgresql.org/docs/current/logical-replication.html)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [Kafka Connect Transforms](https://kafka.apache.org/documentation/#connect_transforms)