#!/bin/bash

# Final connector registration script with proper field mappings and trace propagation

CONNECT_HOST=${1:-kafka-connect}
CONNECT_PORT=${2:-8083}

echo "Waiting for Kafka Connect to be ready..."

# Wait for Kafka Connect to be ready (max 60 seconds)
for i in {1..12}; do
    if curl -s -f "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors" > /dev/null 2>&1; then
        echo "Kafka Connect is ready!"
        break
    fi
    echo "Waiting for Kafka Connect to start... (attempt $i/12)"
    sleep 5
done

# Check if connector already exists and delete it
if curl -s "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector" > /dev/null 2>&1; then
    echo "Deleting existing connector..."
    curl -X DELETE "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector"
    sleep 2
fi

echo "Registering Debezium PostgreSQL connector with trace propagation..."

# Register the connector with correct configuration
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
      "topic.prefix": "dbserver1",
      
      "schema.include.list": "public",
      "table.include.list": "public.outbox_events",
      
      "plugin.name": "pgoutput",
      "publication.autocreate.mode": "filtered",
      "tombstones.on.delete": "false",
      
      "slot.name": "debezium_outbox_slot",
      "publication.name": "debezium_outbox_pub",
      
      "transforms": "outbox",
      
      "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
      "transforms.outbox.table.field.event.id": "id",
      "transforms.outbox.table.field.event.key": "aggregate_id",
      "transforms.outbox.table.field.event.type": "event_type",
      "transforms.outbox.table.field.event.payload": "payload",
      "transforms.outbox.table.field.event.aggregate.id": "aggregate_id",
      "transforms.outbox.table.field.event.aggregate.type": "aggregate_type",
      "transforms.outbox.table.expand.json.payload": "true",
      "transforms.outbox.route.by.field": "event_type",
      "transforms.outbox.route.topic.replacement": "link-events",
      "transforms.outbox.table.fields.additional.placement": "trace_id:header,parent_span_id:header,trace_flags:header,tenant_id:header,created_by:header",
      
      "key.converter": "org.apache.kafka.connect.storage.StringConverter",
      "value.converter": "org.apache.kafka.connect.json.JsonConverter",
      "value.converter.schemas.enable": "false",
      
      "snapshot.mode": "never",
      "heartbeat.interval.ms": "10000"
    }
  }'

echo ""
echo "Waiting for connector to initialize..."
sleep 5

# Check connector status
echo "Connector status:"
curl -s "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector/status"

echo ""
echo ""
echo "✅ Debezium connector registered successfully!"