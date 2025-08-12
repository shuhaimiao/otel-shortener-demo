import { WebTracerProvider, BatchSpanProcessor } from '@opentelemetry/sdk-trace-web';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { ZoneContextManager } from '@opentelemetry/context-zone';
import { Resource } from '@opentelemetry/resources';
import { SemanticResourceAttributes } from '@opentelemetry/semantic-conventions';
import { W3CTraceContextPropagator } from '@opentelemetry/core';

export function register() {
    if (typeof window !== 'undefined') {
        const provider = new WebTracerProvider({
            resource: new Resource({
                [SemanticResourceAttributes.SERVICE_NAME]: 'frontend-spa',
                [SemanticResourceAttributes.SERVICE_VERSION]: '1.0.0',
                'deployment.environment': 'demo',
            }),
        });

        const exporter = new OTLPTraceExporter({
            url: 'http://localhost:4318/v1/traces',
            headers: {},
        });

        // Use BatchSpanProcessor for better performance
        provider.addSpanProcessor(new BatchSpanProcessor(exporter));

        provider.register({
            contextManager: new ZoneContextManager(),
            // Explicitly set W3C propagator for traceparent header
            propagator: new W3CTraceContextPropagator(),
        });

        registerInstrumentations({
            instrumentations: [
                new FetchInstrumentation({
                    // Propagate trace headers to all localhost endpoints
                    propagateTraceHeaderCorsUrls: [
                        /http:\/\/localhost.*/,
                        /http:\/\/127\.0\.0\.1.*/,
                    ],
                    // Clear timing resources to avoid memory leaks
                    clearTimingResources: true,
                    // Add custom attributes to spans
                    applyCustomAttributesOnSpan: (span, request, response) => {
                        span.setAttribute('http.request.method', request.method);
                        span.setAttribute('http.url', request.url);
                        if (response) {
                            span.setAttribute('http.response.status_code', response.status);
                        }
                    },
                }),
            ],
        });
        console.log('OpenTelemetry tracing initialized for frontend-spa with W3C propagation');
    }
} 