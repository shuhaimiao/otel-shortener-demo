# End-to-End Trace Propagation Test Procedure

This document outlines the complete procedure to test distributed tracing with OpenTelemetry and CDC (Change Data Capture) using the transactional outbox pattern.

## Prerequisites
- Docker and Docker Compose installed
- Web browser with Developer Tools
- Access to Jaeger UI (http://localhost:16686)

## Test Steps

### 1. Clean Start
```bash
# Stop and remove all containers, volumes, and networks
docker-compose down -v

# Optional: Clean all data for a completely fresh start
docker system prune -a --volumes
```

### 2. Build and Start All Services
```bash
# Build and start all services in detached mode
docker-compose up --build -d
```

### 3. Wait for Services to Initialize
Wait approximately 30-60 seconds for all services to fully initialize:
- PostgreSQL database initialization
- Kafka and Zookeeper startup
- Kafka Connect initialization
- **Automatic Debezium connector registration** (NEW - no manual step needed!)

### 4. Verify Services Are Running
```bash
# Check all services are up
docker-compose ps

# Verify Debezium connector is registered (should show RUNNING status)
curl -s http://localhost:8083/connectors/postgres-outbox-connector/status | grep state
```

Expected output should show:
```json
"state":"RUNNING"
```

### 5. Create a Test Link via Frontend

1. Open browser and navigate to: http://localhost:3000
2. Enter a URL to shorten (e.g., `https://example.com/trace-test`)
3. Click "Shorten"
4. Copy the shortened URL for later use

### 6. Capture Trace ID from Browser

1. Open Browser Developer Tools (F12)
2. Go to Network tab
3. Look for the POST request to `/api/links`
4. In Response Headers, find the `traceparent` header
5. Extract the trace ID (32 characters after "00-")
   
   Example traceparent: `00-29329c07cbc7cdfbe6c6dd2b5526fa50-4679a282ab8dea01-01`
   
   Trace ID: `29329c07cbc7cdfbe6c6dd2b5526fa50`

### 7. Verify Complete Trace in Jaeger

1. Open Jaeger UI: http://localhost:16686
2. In the Search panel:
   - Service: Select "frontend"
   - Enter the Trace ID from step 6
   - Click "Find Traces"

### 8. Expected Trace Structure

You should see a complete distributed trace with the following spans:

```
frontend: HTTP POST /api/links
├── bff: POST /api/links
│   ├── middleware - query (Redis cache check)
│   ├── middleware - expressInit
│   ├── middleware - corsMiddleware
│   ├── middleware - establishContext
│   ├── middleware - jsonParser
│   ├── request handler - /api/links
│   └── POST (forwarding to url-api)
│
├── url-api: POST /api/links
│   ├── HikariDataSource.getConnection
│   ├── SELECT otel_shortener_db.links (check if exists)
│   ├── INSERT otel_shortener_db.links (save link)
│   └── INSERT otel_shortener_db.outbox_events (save event with trace context)
│
└── analytics-api: kafka.consume.link-events
    └── Processing link event (with same trace ID!)
```

### 9. Verify Trace Context in Logs

Check that the same trace ID appears in all service logs:

```bash
# Check url-api logs
docker-compose logs url-api | grep "<trace-id>"

# Check analytics-api logs  
docker-compose logs analytics-api | grep "<trace-id>"
```

### 10. Test Click Tracking (Optional)

1. Use the shortened URL from step 5
2. Open it in a browser
3. Check Jaeger for the redirect trace
4. Verify click event appears with trace context

## Troubleshooting

### Connector Not Running
If the Debezium connector is not in RUNNING state:

```bash
# Check connector status
curl -s http://localhost:8083/connectors/postgres-outbox-connector/status

# Check Kafka Connect logs
docker-compose logs kafka-connect

# Manually register connector if needed
docker exec otel-connector-init /bin/sh /register-connector.sh
```

### Missing Traces in Analytics
If analytics-api is not receiving events:

```bash
# Check if messages are in Kafka
docker exec otel-shortener-demo-kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic link-events \
  --from-beginning \
  --max-messages 5 \
  --property print.headers=true

# Check analytics-api logs
docker-compose logs analytics-api | tail -50
```

### Trace Context Not Propagating
Verify trace headers are present in Kafka messages:

```bash
# Look for trace_id, parent_span_id, trace_flags headers
docker exec otel-shortener-demo-kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic link-events \
  --property print.headers=true \
  --property print.key=true \
  --max-messages 1
```

## Architecture Overview

The trace propagation flow:

1. **Frontend** generates initial trace with OpenTelemetry
2. **BFF** forwards trace via `traceparent` header  
3. **URL API** saves trace context to outbox_events table
4. **Debezium CDC** captures changes and preserves trace headers
5. **Kafka** transports messages with trace context in headers
6. **Analytics API** extracts trace context and continues the trace

## Key Components

- **Outbox Pattern**: Ensures transactional consistency between business operations and events
- **W3C Trace Context**: Standard format for distributed tracing (`traceparent` header)
- **Debezium EventRouter**: Transforms CDC events into domain events with trace propagation
- **OpenTelemetry**: Provides automatic instrumentation and trace context propagation

## Notes

- The Debezium connector is automatically registered when docker-compose starts
- Trace context is preserved even across asynchronous boundaries (CDC/Kafka)
- All services use the same trace ID for end-to-end visibility
- The outbox cleanup job runs every 5 minutes to remove processed events older than 24 hours