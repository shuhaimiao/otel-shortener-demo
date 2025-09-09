#!/bin/bash

# Simplified script to register Debezium connector with trace context propagation

CONNECT_HOST=${1:-localhost}
CONNECT_PORT=${2:-8083}

echo "Waiting for Kafka Connect to be ready..."

# Wait for Kafka Connect to be ready
until curl -s -f "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors" > /dev/null; do
    echo "Waiting for Kafka Connect to start..."
    sleep 5
done

echo "Kafka Connect is ready!"

# Delete existing connector if present
if curl -s "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector" > /dev/null 2>&1; then
    echo "Deleting existing connector..."
    curl -X DELETE "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector"
    sleep 2
fi

echo "Registering Debezium PostgreSQL connector with outbox pattern..."

# Register the connector with simplified configuration
curl -X POST \
  "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "postgres-outbox-connector",
    "config": {
      "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
      "tasks.max": "1",
      
      "database.hostname": "postgres",
      "database.port": "5432",
      "database.user": "otel_user",
      "database.password": "otel_password",
      "database.dbname": "otel_shortener_db",
      "database.server.name": "dbserver1",
      "topic.prefix": "",
      
      "schema.include.list": "public",
      "table.include.list": "public.outbox_events",
      
      "plugin.name": "pgoutput",
      "tombstones.on.delete": "false",
      
      "slot.name": "debezium_outbox",
      
      "transforms": "outbox",
      
      "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
      "transforms.outbox.table.field.event.id": "id",
      "transforms.outbox.table.field.event.key": "aggregate_id",
      "transforms.outbox.table.field.event.type": "event_type",
      "transforms.outbox.table.field.event.payload": "payload",
      "transforms.outbox.table.expand.json.payload": "true",
      "transforms.outbox.route.by.field": "event_type",
      "transforms.outbox.route.topic.replacement": "link-events",
      "transforms.outbox.table.fields.additional.placement": "trace_id:header,parent_span_id:header,trace_flags:header,tenant_id:header,created_by:header",
      
      "key.converter": "org.apache.kafka.connect.storage.StringConverter",
      "value.converter": "org.apache.kafka.connect.json.JsonConverter",
      "value.converter.schemas.enable": "false",
      
      "snapshot.mode": "never",
      "publication.autocreate.mode": "filtered"
    }
  }'

echo ""
echo "Waiting for connector to initialize..."
sleep 5

# Check connector status
echo "Connector status:"
curl -s "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector/status" | jq '.'

echo ""
echo "✅ Debezium connector registered successfully!"
echo ""
echo "The connector will:"
echo "  1. Read from outbox_events table"
echo "  2. Extract trace_id, parent_span_id, trace_flags as headers"
echo "  3. Route events to 'link-events' topic"
echo "  4. Preserve trace context for end-to-end tracing"
echo ""
echo "Useful commands:"
echo "  # Check status:"
echo "  curl http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector/status | jq"
echo ""
echo "  # View tasks:"
echo "  curl http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector/tasks | jq"
echo ""
echo "  # Delete connector:"
echo "  curl -X DELETE http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector"