const { NodeSDK } = require('@opentelemetry/sdk-node');
const { OTLPTraceExporter } = require('@opentelemetry/exporter-trace-otlp-grpc');
const { getNodeAutoInstrumentations } = require('@opentelemetry/auto-instrumentations-node');
const { Resource } = require('@opentelemetry/resources');
const { SemanticResourceAttributes } = require('@opentelemetry/semantic-conventions');

// Configuration for OTLP Exporter
const otlpExporter = new OTLPTraceExporter({
  // url: 'grpc://localhost:4317', // Default, or specify if different
  // For Docker, use the service name:
  url: process.env.OTEL_EXPORTER_OTLP_ENDPOINT || 'http://otel-collector:4317',
});

const sdk = new NodeSDK({
  resource: new Resource({
    [SemanticResourceAttributes.SERVICE_NAME]: process.env.OTEL_SERVICE_NAME || 'bff',
  }),
  traceExporter: otlpExporter,
  instrumentations: [getNodeAutoInstrumentations()],
  // You can also configure propagators if needed, though defaults are usually fine
  // textMapPropagator: new CompositePropagator({
  //   propagators: [new W3CTraceContextPropagator(), new W3CBaggagePropagator()],
  // }),
});

// Gracefully shut down the SDK on process exit
process.on('SIGTERM', () => {
  sdk.shutdown()
    .then(() => console.log('Tracing terminated'))
    .catch((error) => console.error('Error terminating tracing', error))
    .finally(() => process.exit(0));
});

try {
  sdk.start();
  console.log('OpenTelemetry SDK for BFF started successfully.');
} catch (error) {
  console.error('Error starting OpenTelemetry SDK for BFF:', error);
}

// Note: This file should be required by Node.js using the --require flag
// Example: node --require ./otel-instrumentation.js server.js
