import { WebTracerProvider, SimpleSpanProcessor } from '@opentelemetry/sdk-trace-web';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { ZoneContextManager } from '@opentelemetry/context-zone';
import { Resource } from '@opentelemetry/resources';
import { SemanticResourceAttributes } from '@opentelemetry/semantic-conventions';

export function register() {
    if (typeof window !== 'undefined') {
        const provider = new WebTracerProvider({
            resource: new Resource({
                [SemanticResourceAttributes.SERVICE_NAME]: 'frontend',
            }),
        });

        const exporter = new OTLPTraceExporter({
            url: 'http://localhost:4318/v1/traces',
        });

        provider.addSpanProcessor(new SimpleSpanProcessor(exporter));

        provider.register({
            contextManager: new ZoneContextManager(),
        });

        registerInstrumentations({
            instrumentations: [
                new FetchInstrumentation({
                    propagateTraceHeaderCorsUrls: [
                        /http:\/\/localhost:3001\/.*/,
                    ]
                }),
            ],
        });
        console.log('OpenTelemetry tracing initialized for frontend');
    }
} 