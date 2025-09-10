# Quick Start Guide

Get the OpenTelemetry URL Shortener Demo running in under 5 minutes!

## Prerequisites

- **Docker Desktop** 4.0 or higher
- **8GB RAM** minimum (16GB recommended)
- **20GB disk space** for Docker images
- **Available ports**: 80, 3000, 3001, 5432, 6379, 8080-8083, 9092, 16686

## 🚀 Start Everything

```bash
# Clone the repository
git clone https://github.com/yourusername/otel-shortener-demo.git
cd otel-shortener-demo

# Start all services (this will take 2-3 minutes first time)
docker-compose up -d

# Wait 30-60 seconds for initialization
sleep 60

# Verify all services are running
docker-compose ps
```

## ✅ Verify Services

```bash
# Check Debezium connector is registered (should show "state":"RUNNING")
curl -s http://localhost:8083/connectors/postgres-outbox-connector/status | grep state

# Check service health
docker-compose ps --format "table {{.Name}}\t{{.Status}}"
```

## 🎯 Test the Application

### Step 1: Create a Short Link

1. Open **http://localhost:3000**
2. Enter any URL (e.g., `https://example.com`)
3. Click "Shorten"
4. Copy the generated short URL

> **Note**: Authentication is mocked for demo purposes. The BFF returns placeholder user context.

### Step 2: Capture Trace Information

1. Open browser DevTools (F12) → Network tab
2. Find the POST request to `/api/links`
3. Check Response Headers for `traceparent`
4. Extract trace ID (32 hex characters after "00-")
   
   Example: `00-a1b2c3d4e5f6789...` → trace ID: `a1b2c3d4e5f6789...`

### Step 3: View the Distributed Trace

1. Open Jaeger UI: **http://localhost:16686**
2. Enter the trace ID in search
3. Click "Find Traces"
4. Explore the complete trace showing:
   ```
   frontend: HTTP POST
   └── bff: POST /api/links
       └── url-api: POST /links
           ├── INSERT links
           ├── INSERT outbox_events
           └── analytics-api: kafka.consume.link-events
   ```

### Step 4: Test URL Redirect

1. Open the shortened URL in a new tab
2. Verify redirect to original URL
3. Check Jaeger for redirect trace

## 📊 Service Endpoints

| Service | URL | Purpose |
|---------|-----|---------|
| **Frontend** | http://localhost:3000 | User interface |
| **BFF API** | http://localhost:3001 | API gateway |
| **URL API** | http://localhost:8080 | Link management |
| **Redirect** | http://localhost:8081/{code} | URL redirects |
| **Jaeger UI** | http://localhost:16686 | Trace visualization |
| **Kafka Connect** | http://localhost:8083 | CDC management |

## 🔍 Useful Commands

### View Logs
```bash
# All services
docker-compose logs -f

# Specific service with trace info
docker-compose logs -f analytics-api | grep trace

# Centralized logs
tail -f logs/shared/all-services.log
```

### Database Queries
```bash
# Connect to PostgreSQL
docker exec -it otel-shortener-demo-postgres-1 psql -U otel_user -d otel_shortener_db

# View recent links
SELECT short_code, long_url, created_at FROM links ORDER BY created_at DESC LIMIT 5;

# Check outbox events with trace context
SELECT event_type, trace_id, parent_span_id, created_at 
FROM outbox_events 
ORDER BY created_at DESC LIMIT 5;
```

### Kafka Inspection
```bash
# View link events with headers
docker exec otel-shortener-demo-kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic link-events \
  --property print.headers=true \
  --max-messages 1
```

## 🛑 Shutdown

```bash
# Stop all services
docker-compose down

# Complete cleanup (removes volumes)
docker-compose down -v
```

## 🐛 Troubleshooting

### Services Won't Start
- Check Docker Desktop memory (Settings → Resources → Memory ≥ 8GB)
- Verify ports are available: `netstat -an | grep -E "3000|8080|9092"`
- Review logs: `docker-compose logs [service-name]`

### No Traces in Jaeger
- Verify OTEL collector: `docker-compose logs otel-collector`
- Check service instrumentation: `docker-compose logs url-api | grep OTEL`
- Ensure Debezium connector is running: `curl http://localhost:8083/connectors`

### Database Issues
- Verify PostgreSQL: `docker-compose ps postgres`
- Check initialization: `docker-compose logs postgres | grep "database system is ready"`
- Reinitialize if needed: `docker-compose down -v && docker-compose up -d`

## 💡 Tips

- **First run**: Image downloads take 5-10 minutes
- **Performance**: Close unnecessary applications
- **Browser**: Chrome/Firefox recommended for DevTools
- **Traces**: 100% sampling enabled for demo

## 📚 Next Steps

- Explore [Architecture Documentation](docs/ARCHITECTURE.md)
- Try [E2E Trace Test Scenarios](docs/E2E_TRACE_TEST_PROCEDURE.md)
- Review [Context Propagation](docs/CONTEXT_PROPAGATION.md)
- Check scheduled jobs traces (run every 30/60 seconds) 