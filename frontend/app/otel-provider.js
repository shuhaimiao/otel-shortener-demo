"use client";

import { useEffect } from 'react';
import { WebTracerProvider, BatchSpanProcessor } from '@opentelemetry/sdk-trace-web';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { ZoneContextManager } from '@opentelemetry/context-zone';
import { Resource } from '@opentelemetry/resources';
import { SemanticResourceAttributes } from '@opentelemetry/semantic-conventions';
import { W3CTraceContextPropagator } from '@opentelemetry/core';

export default function OtelProvider({ children }) {
    useEffect(() => {
        // Initialize OpenTelemetry only once on client side
        if (typeof window !== 'undefined' && !window.__otelInitialized) {
            const provider = new WebTracerProvider({
                resource: new Resource({
                    [SemanticResourceAttributes.SERVICE_NAME]: 'frontend',
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
                        // Propagate trace headers to all endpoints
                        propagateTraceHeaderCorsUrls: [
                            /.*/,  // Propagate to all URLs
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
            
            window.__otelInitialized = true;
            console.log('OpenTelemetry tracing initialized for frontend with W3C propagation');
        }
    }, []);

    return children;
}