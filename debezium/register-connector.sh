#!/bin/bash

# Script to register the Debezium PostgreSQL connector for the outbox pattern

CONNECT_HOST=${1:-localhost}
CONNECT_PORT=${2:-8083}

echo "Waiting for Kafka Connect to be ready..."

# Wait for Kafka Connect to be ready
until curl -s -f "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors" > /dev/null; do
    echo "Waiting for Kafka Connect to start..."
    sleep 5
done

echo "Kafka Connect is ready!"

# Check if connector already exists
if curl -s "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector" > /dev/null 2>&1; then
    echo "Connector 'postgres-outbox-connector' already exists. Deleting it first..."
    curl -X DELETE "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector"
    sleep 2
fi

echo "Registering Debezium PostgreSQL connector..."

# Register the connector
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
      
      "transforms": "outbox,route",
      
      "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
      "transforms.outbox.table.field.event.id": "id",
      "transforms.outbox.table.field.event.key": "aggregate_id",
      "transforms.outbox.table.field.event.type": "event_type",
      "transforms.outbox.table.field.event.payload": "payload",
      "transforms.outbox.table.expand.json.payload": "true",
      "transforms.outbox.table.fields.additional.placement": "trace_id:header,parent_span_id:header,trace_flags:header,tenant_id:header,created_by:header",
      
      "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
      "transforms.route.regex": "outbox.event.(.*)",
      "transforms.route.replacement": "$1",
      
      "key.converter": "org.apache.kafka.connect.storage.StringConverter",
      "value.converter": "org.apache.kafka.connect.json.JsonConverter",
      "value.converter.schemas.enable": "false",
      
      "snapshot.mode": "initial",
      "heartbeat.interval.ms": "10000"
    }
  }'

echo ""
echo "Checking connector status..."
sleep 3

# Check connector status
curl -s "http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector/status" | jq '.'

echo ""
echo "Connector registration complete!"
echo ""
echo "To view connector status:"
echo "  curl http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector/status | jq"
echo ""
echo "To view all connectors:"
echo "  curl http://${CONNECT_HOST}:${CONNECT_PORT}/connectors | jq"
echo ""
echo "To delete the connector:"
echo "  curl -X DELETE http://${CONNECT_HOST}:${CONNECT_PORT}/connectors/postgres-outbox-connector"