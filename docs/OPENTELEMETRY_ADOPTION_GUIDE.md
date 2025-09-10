# OpenTelemetry Adoption Guide: Enterprise Implementation Patterns

## Table of Contents
1. [Introduction](#introduction)
2. [Frontend (Browser/React)](#frontend-browserreact)
3. [BFF/API Gateway (Node.js)](#bffapi-gateway-nodejs)
4. [Java Backend Services](#java-backend-services)
5. [Python Services](#python-services)
6. [Kafka/Async Messaging](#kafkaasync-messaging)
7. [Database & CDC](#database--cdc)
8. [Common Patterns](#common-patterns)
9. [Troubleshooting](#troubleshooting)

## Introduction

This guide provides production-ready OpenTelemetry implementation patterns extracted from our reference implementation. While zero-code instrumentation is appealing, **explicit library usage is recommended for enterprise applications** due to:

- **Transitive dependency conflicts** in complex Java applications
- **Fine-grained control** over what gets traced
- **Custom business context** propagation requirements
- **Performance optimization** needs
- **Debugging clarity** when issues arise

### Reference Implementation

The patterns in this guide are extracted from the **[otel-shortener-demo](https://github.com/smiao-icims/otel-shortener-demo)**, a microservices-based URL shortener that demonstrates end-to-end distributed tracing. For detailed architecture and system design, see:

- **[Architecture Documentation](./ARCHITECTURE.md)** - Comprehensive system design and service descriptions
- **[Quick Start Guide](../QUICK_START.md)** - Get the demo running in 5 minutes
- **[E2E Trace Test Procedures](./E2E_TRACE_TEST_PROCEDURE.md)** - Verify trace propagation across all boundaries

The demo showcases trace propagation across:
- Frontend (React) → BFF (Node.js) → Backend Services (Java Spring Boot)
- Synchronous HTTP calls with W3C traceparent headers
- Asynchronous boundaries via Kafka with CDC (Debezium)
- Database operations with transactional outbox pattern

### Core Principles

1. **Explicit over Implicit**: Use OpenTelemetry libraries directly rather than auto-instrumentation agents
2. **W3C Standards**: Always use W3C Trace Context (traceparent/tracestate) for propagation
3. **Selective Instrumentation**: Instrument only what provides value, not everything
4. **Context Preservation**: Ensure trace context survives all boundaries (HTTP, async, CDC)
5. **Performance First**: Batch exports, sampling strategies, and selective spans

## Architectural Decision: Where to Initiate Traces?

### The Critical Choice: Frontend vs Edge

One of the most important decisions in your OpenTelemetry implementation is **where traces begin**. This choice fundamentally affects the observability you achieve.

#### Option 1: Frontend-Initiated Tracing (Recommended)

**Start traces in the browser/mobile app where user interactions occur.**

**Advantages:**
- **True End-to-End Visibility**: See the complete user journey from click to response
- **Client Performance Metrics**: Page load time, rendering, JavaScript execution
- **Network Visibility**: Latency between user and edge (CDN, DNS, ISP issues)
- **Error Correlation**: Link client-side errors with backend traces
- **User Experience Debugging**: Can investigate "it's slow for me" complaints
- **Real User Monitoring**: Actual user experience, not synthetic

**Disadvantages:**
- Requires frontend instrumentation effort
- Slightly increases bundle size (~30-50KB gzipped)
- Need to handle CORS for trace export
- Trace IDs visible to users (minor concern)

**Best For:**
- User-facing applications (B2C, SaaS)
- Single Page Applications with complex client logic
- Mobile applications
- When user experience is critical

#### Option 2: Edge-Initiated Tracing

**Start traces at API Gateway, Load Balancer, or NGINX.**

**Advantages:**
- Less development effort (no frontend changes)
- Centralized control
- Works uniformly for all client types
- No CORS configuration needed
- Smaller frontend bundle

**Disadvantages:**
- **Blind to Client Issues**: Can't see JavaScript errors, rendering problems
- **Missing Network Layer**: No visibility between user and edge
- **Incomplete User Journey**: Starts at your infrastructure, not user action
- **Limited Debugging**: Can't correlate user complaints with traces
- **No Performance Metrics**: Missing Core Web Vitals, interaction timing

**Acceptable For:**
- Internal APIs with controlled clients
- B2B APIs where partners own client instrumentation
- Legacy systems where frontend changes are prohibitive
- Simple CRUD applications with thin clients

#### Option 3: Hybrid Approach (Pragmatic)

**Combine both strategies for maximum flexibility:**

```javascript
// Frontend: Continue or create trace
const traceHeader = response.headers.get('traceparent');
if (traceHeader) {
  // Continue edge-initiated trace
  continueTrace(traceHeader);
} else {
  // Start new trace from frontend
  startNewTrace();
}
```

**Implementation Strategy:**
1. **Phase 1**: Implement edge tracing for immediate operational visibility
2. **Phase 2**: Add frontend tracing for critical user journeys
3. **Phase 3**: Expand frontend coverage based on debugging needs

### Our Recommendation

**Start with frontend tracing whenever possible.** Here's why:

1. **You can't add visibility retroactively**: When a user reports an issue, frontend traces provide crucial context that edge traces miss

2. **The earlier you start tracing, the more you see**: Problems often occur before requests reach your edge

3. **Modern tools make it easy**: Libraries like OpenTelemetry Browser SDK require minimal setup

4. **Performance impact is negligible**: With sampling and batching, overhead is <5ms per request

### Real-World Example

Consider an e-commerce checkout flow:

**With Frontend Tracing:**
```
[Browser: User clicks "Checkout"] → 2.3s client processing
  ├── React render: 450ms
  ├── Form validation: 200ms
  ├── API call to edge → 1.65s
      ├── Network transit: 120ms
      ├── Edge processing: 50ms
      └── Backend services: 1.48s
```

**With Edge-Only Tracing:**
```
[Edge: Receives request] → 1.53s
  ├── Edge processing: 50ms
  └── Backend services: 1.48s
```

The edge-only trace misses the 820ms of client-side work and network transit - often where user experience problems hide.

### Decision Framework

Choose **Frontend Tracing** if:
- User experience is critical to your business
- You have SPAs or mobile apps with significant client logic
- You need to debug user-reported performance issues
- You want to measure Core Web Vitals or interaction metrics

Choose **Edge Tracing** if:
- You only need operational metrics (uptime, latency)
- All clients are backend services or controlled APIs
- Frontend changes require regulatory approval or long cycles
- You're in Phase 1 of observability adoption (temporary)

Choose **Hybrid** if:
- You have mixed client types (web, mobile, API)
- You're incrementally adopting observability
- Different teams own frontend vs backend

> **Remember**: Observability is about understanding your system from your users' perspective. Frontend tracing aligns with this goal, while edge tracing only shows you what happens inside your infrastructure.

## Frontend (Browser/React)

### Installation

```bash
npm install --save \
  @opentelemetry/api \
  @opentelemetry/sdk-trace-web \
  @opentelemetry/instrumentation-fetch \
  @opentelemetry/instrumentation-xml-http-request \
  @opentelemetry/exporter-trace-otlp-http \
  @opentelemetry/context-zone \
  @opentelemetry/core
```

### Implementation Pattern

```typescript
// instrumentation.ts - Frontend trace initialization
import { WebTracerProvider, BatchSpanProcessor } from '@opentelemetry/sdk-trace-web';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { XMLHttpRequestInstrumentation } from '@opentelemetry/instrumentation-xml-http-request';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { ZoneContextManager } from '@opentelemetry/context-zone';
import { Resource } from '@opentelemetry/resources';
import { SemanticResourceAttributes } from '@opentelemetry/semantic-conventions';
import { W3CTraceContextPropagator } from '@opentelemetry/core';
import { trace, context, SpanStatusCode } from '@opentelemetry/api';

class FrontendTracing {
  private provider: WebTracerProvider;
  private isInitialized = false;

  initialize(config: {
    serviceName: string;
    serviceVersion: string;
    environment: string;
    otlpEndpoint?: string;
    propagateToUrls?: RegExp[];
  }) {
    if (this.isInitialized || typeof window === 'undefined') {
      return;
    }

    // Create provider with resource attributes
    this.provider = new WebTracerProvider({
      resource: new Resource({
        [SemanticResourceAttributes.SERVICE_NAME]: config.serviceName,
        [SemanticResourceAttributes.SERVICE_VERSION]: config.serviceVersion,
        [SemanticResourceAttributes.DEPLOYMENT_ENVIRONMENT]: config.environment,
        // Add custom attributes
        'browser.user_agent': navigator.userAgent,
        'browser.language': navigator.language,
      }),
    });

    // Configure OTLP exporter with batching
    const exporter = new OTLPTraceExporter({
      url: config.otlpEndpoint || 'http://localhost:4318/v1/traces',
      headers: {}, // Add auth headers if needed
    });

    // Use BatchSpanProcessor for better performance
    this.provider.addSpanProcessor(new BatchSpanProcessor(exporter, {
      maxQueueSize: 100,
      maxExportBatchSize: 50,
      scheduledDelayMillis: 500, // Batch delay
      exportTimeoutMillis: 30000,
    }));

    // Register provider with W3C propagator
    this.provider.register({
      contextManager: new ZoneContextManager(),
      propagator: new W3CTraceContextPropagator(),
    });

    // Instrument fetch with custom configuration
    registerInstrumentations({
      instrumentations: [
        new FetchInstrumentation({
          propagateTraceHeaderCorsUrls: config.propagateToUrls || [/.*/],
          clearTimingResources: true,
          applyCustomAttributesOnSpan: (span, request, response) => {
            // Add custom attributes
            span.setAttribute('http.request.method', request.method);
            span.setAttribute('http.url.path', new URL(request.url).pathname);
            
            if (response) {
              span.setAttribute('http.response.status_code', response.status);
              
              // Set span status based on HTTP status
              if (response.status >= 400) {
                span.setStatus({
                  code: SpanStatusCode.ERROR,
                  message: `HTTP ${response.status}`,
                });
              }
            }
            
            // Add business context if available
            const userId = this.getUserId();
            if (userId) {
              span.setAttribute('user.id', userId);
            }
            
            const tenantId = this.getTenantId();
            if (tenantId) {
              span.setAttribute('tenant.id', tenantId);
            }
          },
        }),
        new XMLHttpRequestInstrumentation({
          propagateTraceHeaderCorsUrls: config.propagateToUrls || [/.*/],
        }),
      ],
    });

    this.isInitialized = true;
    console.log(`OpenTelemetry initialized for ${config.serviceName}`);
  }

  // Helper to create custom spans for business operations
  startSpan(name: string, attributes?: Record<string, any>) {
    const tracer = trace.getTracer('frontend-app');
    const span = tracer.startSpan(name, {
      attributes: {
        ...attributes,
        'user.id': this.getUserId(),
        'tenant.id': this.getTenantId(),
        'session.id': this.getSessionId(),
      },
    });
    
    return {
      span,
      end: (status?: { code: SpanStatusCode; message?: string }) => {
        if (status) {
          span.setStatus(status);
        }
        span.end();
      },
    };
  }

  // Business context helpers (implement based on your auth system)
  private getUserId(): string | undefined {
    return sessionStorage.getItem('userId') || undefined;
  }

  private getTenantId(): string | undefined {
    return sessionStorage.getItem('tenantId') || undefined;
  }

  private getSessionId(): string | undefined {
    return sessionStorage.getItem('sessionId') || undefined;
  }
}

// Export singleton instance
export const tracing = new FrontendTracing();

// Initialize in your app entry point
export function initializeTracing() {
  tracing.initialize({
    serviceName: 'frontend-spa',
    serviceVersion: process.env.REACT_APP_VERSION || '1.0.0',
    environment: process.env.REACT_APP_ENV || 'development',
    otlpEndpoint: process.env.REACT_APP_OTLP_ENDPOINT,
    propagateToUrls: [
      /http:\/\/localhost.*/,
      /https:\/\/api\.yourdomain\.com.*/,
    ],
  });
}
```

### Usage in React Components

```typescript
// LinkCreator.tsx - Example React component with tracing
import React, { useState } from 'react';
import { tracing } from './instrumentation';
import { SpanStatusCode } from '@opentelemetry/api';

export const LinkCreator: React.FC = () => {
  const [url, setUrl] = useState('');
  
  const createShortLink = async () => {
    // Start a custom span for business operation
    const { span, end } = tracing.startSpan('create_short_link', {
      'link.original_url': url,
      'component': 'LinkCreator',
    });
    
    try {
      const response = await fetch('/api/links', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ url }),
      });
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      
      const data = await response.json();
      span.setAttribute('link.short_code', data.shortCode);
      
      end({ code: SpanStatusCode.OK });
      return data;
      
    } catch (error) {
      span.recordException(error as Error);
      end({ 
        code: SpanStatusCode.ERROR, 
        message: error.message 
      });
      throw error;
    }
  };
  
  // Component render logic...
};
```

## BFF/API Gateway (Node.js)

### Installation

```bash
npm install --save \
  @opentelemetry/api \
  @opentelemetry/sdk-node \
  @opentelemetry/instrumentation-http \
  @opentelemetry/instrumentation-express \
  @opentelemetry/exporter-trace-otlp-grpc \
  @opentelemetry/resources \
  @opentelemetry/semantic-conventions
```

### Implementation Pattern

```javascript
// otel-instrumentation.js - BFF trace initialization
const { NodeSDK } = require('@opentelemetry/sdk-node');
const { OTLPTraceExporter } = require('@opentelemetry/exporter-trace-otlp-grpc');
const { Resource } = require('@opentelemetry/resources');
const { SemanticResourceAttributes } = require('@opentelemetry/semantic-conventions');
const { HttpInstrumentation } = require('@opentelemetry/instrumentation-http');
const { ExpressInstrumentation } = require('@opentelemetry/instrumentation-express');
const opentelemetry = require('@opentelemetry/api');

class BFFTracing {
  constructor() {
    this.sdk = null;
  }

  initialize(config) {
    // Configure OTLP exporter
    const traceExporter = new OTLPTraceExporter({
      url: config.otlpEndpoint || 'grpc://localhost:4317',
      headers: config.otlpHeaders || {},
    });

    // Create SDK with selective instrumentation
    this.sdk = new NodeSDK({
      resource: new Resource({
        [SemanticResourceAttributes.SERVICE_NAME]: config.serviceName,
        [SemanticResourceAttributes.SERVICE_VERSION]: config.serviceVersion,
        [SemanticResourceAttributes.DEPLOYMENT_ENVIRONMENT]: config.environment,
        // Add custom resource attributes
        'service.layer': 'bff',
        'service.framework': 'express',
      }),
      traceExporter,
      instrumentations: [
        // HTTP instrumentation with custom config
        new HttpInstrumentation({
          requestHook: (span, request) => {
            // Add custom attributes to all HTTP client spans
            span.setAttribute('http.request.method', request.method);
            span.setAttribute('http.url.path', request.path || request.url);
            
            // Don't trace health checks
            if (request.url?.includes('/health')) {
              span.setAttribute('http.route', '/health');
              span.end(); // End immediately to reduce overhead
            }
          },
          responseHook: (span, response) => {
            // Add response attributes
            span.setAttribute('http.response.status_code', response.statusCode);
          },
          // Ignore certain endpoints
          ignoreIncomingPaths: ['/health', '/metrics', '/favicon.ico'],
          ignoreOutgoingUrls: [(url) => url.includes('prometheus')],
        }),
        
        // Express instrumentation for route-level tracing
        new ExpressInstrumentation({
          requestHook: (span, { req }) => {
            // Add business context from headers/JWT
            const userId = req.headers['x-user-id'] || extractUserIdFromJWT(req);
            const tenantId = req.headers['x-tenant-id'] || extractTenantIdFromJWT(req);
            const requestId = req.headers['x-request-id'] || generateRequestId();
            
            if (userId) span.setAttribute('user.id', userId);
            if (tenantId) span.setAttribute('tenant.id', tenantId);
            span.setAttribute('request.id', requestId);
            
            // Store in request for downstream propagation
            req.traceContext = {
              userId,
              tenantId,
              requestId,
              traceId: span.spanContext().traceId,
              spanId: span.spanContext().spanId,
            };
          },
        }),
      ],
    });

    // Start the SDK
    this.sdk.start();
    console.log(`OpenTelemetry initialized for ${config.serviceName}`);
  }

  // Get tracer for manual instrumentation
  getTracer(name = 'bff-manual') {
    return opentelemetry.trace.getTracer(name);
  }

  // Graceful shutdown
  async shutdown() {
    if (this.sdk) {
      await this.sdk.shutdown();
      console.log('OpenTelemetry SDK shut down successfully');
    }
  }
}

// Helper functions
function extractUserIdFromJWT(req) {
  // Implement JWT parsing logic
  const token = req.headers.authorization?.replace('Bearer ', '');
  if (!token) return null;
  
  try {
    // Decode JWT (use proper library in production)
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64').toString());
    return payload.sub || payload.user_id;
  } catch {
    return null;
  }
}

function extractTenantIdFromJWT(req) {
  // Similar to extractUserIdFromJWT
  // Extract tenant_id from JWT claims
}

function generateRequestId() {
  return `req-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

// Export singleton
const tracing = new BFFTracing();
module.exports = { tracing };

// Initialize in your server startup
// server.js
const { tracing } = require('./otel-instrumentation');

tracing.initialize({
  serviceName: process.env.OTEL_SERVICE_NAME || 'bff',
  serviceVersion: process.env.SERVICE_VERSION || '1.0.0',
  environment: process.env.NODE_ENV || 'development',
  otlpEndpoint: process.env.OTEL_EXPORTER_OTLP_ENDPOINT,
});

// Graceful shutdown
process.on('SIGTERM', async () => {
  await tracing.shutdown();
  process.exit(0);
});
```

### Express Middleware for Context Propagation

```javascript
// middleware/trace-context.js
const opentelemetry = require('@opentelemetry/api');

/**
 * Middleware to ensure trace context propagation to downstream services
 */
function traceContextMiddleware(req, res, next) {
  const span = opentelemetry.trace.getActiveSpan();
  
  if (span) {
    const spanContext = span.spanContext();
    
    // Ensure traceparent header is set for downstream propagation
    req.headers['traceparent'] = `00-${spanContext.traceId}-${spanContext.spanId}-01`;
    
    // Add business context headers from JWT/session
    if (req.traceContext) {
      req.headers['x-user-id'] = req.traceContext.userId;
      req.headers['x-tenant-id'] = req.traceContext.tenantId;
      req.headers['x-request-id'] = req.traceContext.requestId;
      req.headers['x-service-name'] = 'bff';
      req.headers['x-transaction-type'] = deriveTransactionType(req);
    }
    
    // Log the request with context
    console.log(`[${spanContext.traceId}] ${req.method} ${req.path}`, {
      userId: req.traceContext?.userId,
      tenantId: req.traceContext?.tenantId,
    });
  }
  
  next();
}

function deriveTransactionType(req) {
  // Derive business transaction type from route
  if (req.path.includes('/links') && req.method === 'POST') {
    return 'create-link';
  }
  if (req.path.includes('/links') && req.method === 'GET') {
    return 'list-links';
  }
  // Add more mappings as needed
  return 'unknown';
}

module.exports = { traceContextMiddleware };
```

## Java Backend Services

### Maven Dependencies

```xml
<!-- pom.xml - Explicit OpenTelemetry dependencies -->
<properties>
    <opentelemetry.version>1.40.0</opentelemetry.version>
    <opentelemetry-instrumentation.version>2.10.0</opentelemetry-instrumentation.version>
</properties>

<dependencies>
    <!-- OpenTelemetry API -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-api</artifactId>
        <version>${opentelemetry.version}</version>
    </dependency>
    
    <!-- OpenTelemetry SDK -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-sdk</artifactId>
        <version>${opentelemetry.version}</version>
    </dependency>
    
    <!-- OTLP Exporter -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
        <version>${opentelemetry.version}</version>
    </dependency>
    
    <!-- Context Propagation -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-extension-trace-propagators</artifactId>
        <version>${opentelemetry.version}</version>
    </dependency>
    
    <!-- Spring Boot Instrumentation (explicit) -->
    <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-spring-boot-starter</artifactId>
        <version>${opentelemetry-instrumentation.version}</version>
        <exclusions>
            <!-- Exclude auto-instrumentation to avoid conflicts -->
            <exclusion>
                <groupId>io.opentelemetry.javaagent</groupId>
                <artifactId>*</artifactId>
            </exclusion>
        </exclusions>
    </dependency>
    
    <!-- Logback Integration -->
    <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-logback-mdc-1.0</artifactId>
        <version>${opentelemetry-instrumentation.version}</version>
    </dependency>
</dependencies>
```

### Configuration Class

```java
// OpenTelemetryConfig.java - Explicit configuration
package com.example.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class OpenTelemetryConfig {
    
    @Value("${spring.application.name}")
    private String serviceName;
    
    @Value("${otel.exporter.otlp.endpoint:http://localhost:4317}")
    private String otlpEndpoint;
    
    @Value("${otel.traces.sampler.probability:1.0}")
    private double samplingProbability;
    
    @Bean
    public OpenTelemetry openTelemetry() {
        // Create resource with service information
        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.builder()
                .put(ResourceAttributes.SERVICE_NAME, serviceName)
                .put(ResourceAttributes.SERVICE_VERSION, getServiceVersion())
                .put(ResourceAttributes.DEPLOYMENT_ENVIRONMENT, getEnvironment())
                .put("service.layer", "backend")
                .put("service.framework", "spring-boot")
                .build()));
        
        // Configure OTLP exporter
        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(otlpEndpoint)
            .setTimeout(10, TimeUnit.SECONDS)
            .build();
        
        // Create tracer provider with batch processor
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(
                BatchSpanProcessor.builder(spanExporter)
                    .setMaxQueueSize(2048)
                    .setMaxExportBatchSize(512)
                    .setScheduleDelay(5, TimeUnit.SECONDS)
                    .build())
            .setResource(resource)
            .setSampler(Sampler.traceIdRatioBased(samplingProbability))
            .build();
        
        // Build OpenTelemetry SDK
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal();
        
        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));
        
        return openTelemetry;
    }
    
    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(serviceName, getServiceVersion());
    }
    
    private String getServiceVersion() {
        return getClass().getPackage().getImplementationVersion() != null 
            ? getClass().getPackage().getImplementationVersion() 
            : "1.0.0";
    }
    
    private String getEnvironment() {
        return System.getenv("ENVIRONMENT") != null 
            ? System.getenv("ENVIRONMENT") 
            : "development";
    }
}
```

### Service Implementation with Manual Tracing

```java
// LinkService.java - Service with explicit tracing
package com.example.service;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkService {
    
    private static final Logger logger = LoggerFactory.getLogger(LinkService.class);
    
    @Autowired
    private Tracer tracer;
    
    @Autowired
    private LinkRepository linkRepository;
    
    @Autowired
    private OutboxService outboxService;
    
    @Transactional
    public ShortLink createShortLink(String longUrl, String userId, String tenantId) {
        // Start a span for the business operation
        Span span = tracer.spanBuilder("create_short_link")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("operation.type", "create")
            .setAttribute("link.original_url", longUrl)
            .setAttribute("user.id", userId)
            .setAttribute("tenant.id", tenantId)
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            // Business logic with automatic context propagation
            logger.info("Creating short link for URL: {}", longUrl);
            
            // Validate URL
            validateUrl(longUrl);
            
            // Generate short code
            String shortCode = generateShortCode();
            span.setAttribute("link.short_code", shortCode);
            
            // Save to database (automatically traced if JDBC instrumentation is enabled)
            Link link = new Link();
            link.setLongUrl(longUrl);
            link.setShortCode(shortCode);
            link.setCreatedBy(userId);
            link.setTenantId(tenantId);
            link = linkRepository.save(link);
            
            // Create outbox event for CDC with trace context
            createOutboxEvent(link, span.getSpanContext());
            
            span.setStatus(StatusCode.OK);
            return new ShortLink(link);
            
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            logger.error("Error creating short link", e);
            throw e;
        } finally {
            span.end();
        }
    }
    
    private void createOutboxEvent(Link link, io.opentelemetry.api.trace.SpanContext spanContext) {
        // Create a child span for outbox operation
        Span span = tracer.spanBuilder("create_outbox_event")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("event.type", "LINK_CREATED")
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            OutboxEvent event = new OutboxEvent();
            event.setEventType("LINK_CREATED");
            event.setAggregateId(link.getId().toString());
            event.setPayload(toJson(link));
            
            // Add trace context for CDC propagation
            event.setTraceId(spanContext.getTraceId());
            event.setParentSpanId(spanContext.getSpanId());
            event.setTraceFlags(spanContext.getTraceFlags().asHex());
            
            // Add business context
            event.setUserId(MDC.get("userId"));
            event.setTenantId(MDC.get("tenantId"));
            event.setRequestId(MDC.get("requestId"));
            
            outboxService.save(event);
            
            span.setAttribute("event.id", event.getId().toString());
            span.setStatus(StatusCode.OK);
            
        } finally {
            span.end();
        }
    }
    
    // Other methods...
}
```

### RestTemplate with Trace Propagation

```java
// TracingRestTemplateConfig.java
package com.example.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;
import org.slf4j.MDC;

import java.io.IOException;

@Configuration
public class TracingRestTemplateConfig {
    
    @Autowired
    private OpenTelemetry openTelemetry;
    
    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new TracePropagationInterceptor());
        return restTemplate;
    }
    
    private class TracePropagationInterceptor implements ClientHttpRequestInterceptor {
        
        private final TextMapSetter<HttpRequest> setter = (carrier, key, value) -> {
            carrier.getHeaders().set(key, value);
        };
        
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, 
                                           ClientHttpRequestExecution execution) throws IOException {
            // Inject trace context
            openTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), request, setter);
            
            // Add business context headers from MDC
            String userId = MDC.get("userId");
            String tenantId = MDC.get("tenantId");
            String requestId = MDC.get("requestId");
            
            if (userId != null) request.getHeaders().set("X-User-ID", userId);
            if (tenantId != null) request.getHeaders().set("X-Tenant-ID", tenantId);
            if (requestId != null) request.getHeaders().set("X-Request-ID", requestId);
            
            // Add current span attributes
            Span currentSpan = Span.current();
            currentSpan.setAttribute("http.request.method", request.getMethod().toString());
            currentSpan.setAttribute("http.url", request.getURI().toString());
            
            return execution.execute(request, body);
        }
    }
}
```

## Python Services

### Installation

```bash
pip install \
  opentelemetry-api \
  opentelemetry-sdk \
  opentelemetry-instrumentation \
  opentelemetry-exporter-otlp \
  opentelemetry-instrumentation-flask \
  opentelemetry-instrumentation-requests \
  opentelemetry-instrumentation-sqlalchemy
```

### Implementation Pattern

```python
# tracing.py - Python service tracing setup
from opentelemetry import trace, baggage, metrics
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.resources import Resource
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.propagate import set_global_textmap
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
from opentelemetry.instrumentation.flask import FlaskInstrumentor
from opentelemetry.instrumentation.requests import RequestsInstrumentor
from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor
from opentelemetry.sdk.trace.sampling import TraceIdRatioBasedSampler
import logging
import os
from typing import Optional, Dict, Any
from functools import wraps

logger = logging.getLogger(__name__)

class PythonServiceTracing:
    def __init__(self):
        self.tracer_provider: Optional[TracerProvider] = None
        self.tracer: Optional[trace.Tracer] = None
        
    def initialize(
        self,
        service_name: str,
        service_version: str = "1.0.0",
        environment: str = "development",
        otlp_endpoint: str = "localhost:4317",
        sampling_rate: float = 1.0
    ):
        """Initialize OpenTelemetry with explicit configuration"""
        
        # Create resource
        resource = Resource.create({
            "service.name": service_name,
            "service.version": service_version,
            "deployment.environment": environment,
            "service.language": "python",
            "service.framework": "flask",
        })
        
        # Create tracer provider with sampling
        self.tracer_provider = TracerProvider(
            resource=resource,
            sampler=TraceIdRatioBasedSampler(sampling_rate)
        )
        
        # Configure OTLP exporter
        otlp_exporter = OTLPSpanExporter(
            endpoint=otlp_endpoint,
            insecure=True,  # Use False with TLS in production
        )
        
        # Add batch processor
        span_processor = BatchSpanProcessor(
            otlp_exporter,
            max_queue_size=2048,
            max_export_batch_size=512,
            schedule_delay_millis=5000,
        )
        self.tracer_provider.add_span_processor(span_processor)
        
        # Set as global
        trace.set_tracer_provider(self.tracer_provider)
        
        # Set W3C propagator
        set_global_textmap(TraceContextTextMapPropagator())
        
        # Get tracer
        self.tracer = trace.get_tracer(service_name, service_version)
        
        logger.info(f"OpenTelemetry initialized for {service_name}")
        
    def instrument_flask(self, app):
        """Instrument Flask application"""
        FlaskInstrumentor().instrument_app(
            app,
            request_hook=self._flask_request_hook,
            response_hook=self._flask_response_hook,
        )
        
    def instrument_requests(self):
        """Instrument requests library for outgoing HTTP"""
        RequestsInstrumentor().instrument(
            tracer_provider=self.tracer_provider,
            span_callback=self._requests_span_callback,
        )
        
    def instrument_sqlalchemy(self, engine):
        """Instrument SQLAlchemy for database queries"""
        SQLAlchemyInstrumentor().instrument(
            engine=engine,
            service=f"{os.environ.get('SERVICE_NAME', 'python-service')}-db",
        )
        
    def _flask_request_hook(self, span, environ):
        """Add custom attributes to Flask request spans"""
        if span and span.is_recording():
            # Extract headers
            headers = environ.get("werkzeug.request").headers
            
            # Add business context
            user_id = headers.get("X-User-ID")
            tenant_id = headers.get("X-Tenant-ID")
            request_id = headers.get("X-Request-ID")
            
            if user_id:
                span.set_attribute("user.id", user_id)
            if tenant_id:
                span.set_attribute("tenant.id", tenant_id)
            if request_id:
                span.set_attribute("request.id", request_id)
                
            # Add to baggage for propagation
            if user_id and tenant_id:
                baggage.set_baggage("user.id", user_id)
                baggage.set_baggage("tenant.id", tenant_id)
                
    def _flask_response_hook(self, span, status, response_headers):
        """Add custom attributes to Flask response spans"""
        if span and span.is_recording():
            span.set_attribute("http.response.status_code", status)
            
    def _requests_span_callback(self, span, response):
        """Add custom attributes to outgoing HTTP request spans"""
        if span and span.is_recording():
            if response:
                span.set_attribute("http.response.status_code", response.status_code)
                
    def trace_method(self, name: str = None, kind: trace.SpanKind = trace.SpanKind.INTERNAL):
        """Decorator for tracing methods"""
        def decorator(func):
            @wraps(func)
            def wrapper(*args, **kwargs):
                span_name = name or f"{func.__module__}.{func.__name__}"
                with self.tracer.start_as_current_span(
                    span_name,
                    kind=kind,
                    attributes={
                        "code.function": func.__name__,
                        "code.namespace": func.__module__,
                    }
                ) as span:
                    try:
                        result = func(*args, **kwargs)
                        span.set_status(trace.Status(trace.StatusCode.OK))
                        return result
                    except Exception as e:
                        span.record_exception(e)
                        span.set_status(
                            trace.Status(trace.StatusCode.ERROR, str(e))
                        )
                        raise
            return wrapper
        return decorator
    
    def get_current_trace_context(self) -> Dict[str, str]:
        """Get current trace context for logging"""
        span = trace.get_current_span()
        if span.is_recording():
            span_context = span.get_span_context()
            return {
                "trace_id": format(span_context.trace_id, "032x"),
                "span_id": format(span_context.span_id, "016x"),
                "trace_flags": format(span_context.trace_flags, "02x"),
            }
        return {}

# Singleton instance
tracing = PythonServiceTracing()

# Flask application example
from flask import Flask, request, jsonify
import requests

app = Flask(__name__)

# Initialize tracing
tracing.initialize(
    service_name="python-analytics-service",
    service_version="1.0.0",
    environment=os.environ.get("ENVIRONMENT", "development"),
    otlp_endpoint=os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:4317"),
    sampling_rate=float(os.environ.get("OTEL_SAMPLING_RATE", "1.0")),
)

# Instrument Flask
tracing.instrument_flask(app)
tracing.instrument_requests()

@app.route("/analyze", methods=["POST"])
@tracing.trace_method("analyze_link_data")
def analyze_link():
    """Example endpoint with custom tracing"""
    data = request.json
    
    # Get current span to add custom attributes
    span = trace.get_current_span()
    span.set_attribute("analysis.type", data.get("type", "unknown"))
    span.set_attribute("analysis.link_id", data.get("link_id"))
    
    # Log with trace context
    trace_context = tracing.get_current_trace_context()
    logger.info(
        "Analyzing link data",
        extra={
            **trace_context,
            "link_id": data.get("link_id"),
            "user_id": request.headers.get("X-User-ID"),
        }
    )
    
    # Make downstream call (automatically traced)
    response = requests.get(
        f"http://url-api:8080/links/{data['link_id']}",
        headers={
            "X-User-ID": request.headers.get("X-User-ID"),
            "X-Tenant-ID": request.headers.get("X-Tenant-ID"),
        }
    )
    
    return jsonify({"status": "analyzed", "link": response.json()})
```

## Kafka/Async Messaging

### Kafka Producer with Trace Context

```java
// KafkaProducerWithTracing.java
package com.example.kafka;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Component
public class KafkaProducerWithTracing {
    
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    
    @Autowired
    private Tracer tracer;
    
    @Autowired
    private OpenTelemetry openTelemetry;
    
    private final TextMapSetter<Headers> setter = (carrier, key, value) -> {
        if (carrier != null) {
            carrier.add(key, value.getBytes(StandardCharsets.UTF_8));
        }
    };
    
    public CompletableFuture<SendResult<String, Object>> sendWithTracing(
            String topic, 
            String key, 
            Object payload,
            String userId,
            String tenantId) {
        
        // Create producer span
        Span span = tracer.spanBuilder(String.format("kafka.send.%s", topic))
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute("messaging.system", "kafka")
            .setAttribute("messaging.destination", topic)
            .setAttribute("messaging.destination.kind", "topic")
            .setAttribute("messaging.message.id", key)
            .setAttribute("user.id", userId)
            .setAttribute("tenant.id", tenantId)
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            // Create producer record
            ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, payload);
            
            // Inject trace context into headers
            openTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), record.headers(), setter);
            
            // Add business context headers
            record.headers()
                .add("X-User-ID", userId.getBytes(StandardCharsets.UTF_8))
                .add("X-Tenant-ID", tenantId.getBytes(StandardCharsets.UTF_8));
            
            // Send message
            CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(record);
            
            // Add callback for span completion
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    span.recordException(ex);
                    span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, ex.getMessage());
                } else {
                    RecordMetadata metadata = result.getRecordMetadata();
                    span.setAttribute("messaging.kafka.partition", metadata.partition());
                    span.setAttribute("messaging.kafka.offset", metadata.offset());
                    span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
                }
                span.end();
            });
            
            return future;
            
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            span.end();
            throw e;
        }
    }
}
```

### Kafka Consumer with Trace Context Extraction

```java
// KafkaConsumerWithTracing.java
package com.example.kafka;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;

@Component
public class KafkaConsumerWithTracing {
    
    private static final Logger logger = LoggerFactory.getLogger(KafkaConsumerWithTracing.class);
    
    @Autowired
    private Tracer tracer;
    
    @Autowired
    private OpenTelemetry openTelemetry;
    
    private final TextMapGetter<Headers> getter = new TextMapGetter<Headers>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            return () -> new Iterator<String>() {
                private final Iterator<Header> it = carrier.iterator();
                
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }
                
                @Override
                public String next() {
                    return it.next().key();
                }
            };
        }
        
        @Override
        public String get(Headers carrier, String key) {
            Header header = carrier.lastHeader(key);
            return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
        }
    };
    
    @KafkaListener(topics = "${app.kafka.topics}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, Object> record) {
        // Extract trace context from headers
        Context extractedContext = openTelemetry.getPropagators().getTextMapPropagator()
            .extract(Context.current(), record.headers(), getter);
        
        // Create consumer span
        Span span = tracer.spanBuilder(String.format("kafka.consume.%s", record.topic()))
            .setParent(extractedContext)
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute("messaging.system", "kafka")
            .setAttribute("messaging.destination", record.topic())
            .setAttribute("messaging.destination.kind", "topic")
            .setAttribute("messaging.message.id", record.key())
            .setAttribute("messaging.kafka.partition", record.partition())
            .setAttribute("messaging.kafka.offset", record.offset())
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            // Extract business context from headers
            String userId = extractHeader(record.headers(), "X-User-ID");
            String tenantId = extractHeader(record.headers(), "X-Tenant-ID");
            
            // Set MDC for logging
            MDC.put("traceId", span.getSpanContext().getTraceId());
            MDC.put("spanId", span.getSpanContext().getSpanId());
            MDC.put("userId", userId);
            MDC.put("tenantId", tenantId);
            
            // Add attributes to span
            if (userId != null) span.setAttribute("user.id", userId);
            if (tenantId != null) span.setAttribute("tenant.id", tenantId);
            
            logger.info("Processing message from topic: {}", record.topic());
            
            // Process the message
            processMessage(record.value());
            
            span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
            
        } catch (Exception e) {
            logger.error("Error processing message", e);
            span.recordException(e);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            MDC.clear();
            span.end();
        }
    }
    
    private String extractHeader(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
    
    private void processMessage(Object message) {
        // Business logic here
    }
}
```

## Database & CDC

### Outbox Table with Trace Context

```sql
-- Outbox table schema with trace context
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    
    -- Trace context columns for CDC propagation
    trace_id VARCHAR(32),
    parent_span_id VARCHAR(16),
    trace_flags VARCHAR(2) DEFAULT '01',
    
    -- Business context columns
    user_id VARCHAR(255),
    tenant_id VARCHAR(255),
    request_id VARCHAR(255),
    transaction_type VARCHAR(100),
    
    -- Metadata
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    
    INDEX idx_unprocessed (processed_at) WHERE processed_at IS NULL,
    INDEX idx_trace_id (trace_id),
    INDEX idx_tenant_id (tenant_id)
);
```

### Debezium Connector Configuration

```json
{
  "name": "postgres-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "postgres",
    "database.port": "5432",
    "database.user": "debezium",
    "database.password": "debezium",
    "database.dbname": "application_db",
    "database.server.name": "postgres",
    "table.include.list": "public.outbox_events",
    
    "transforms": "unwrap,addHeaders,route",
    
    "transforms.unwrap.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.unwrap.table.field.event.id": "id",
    "transforms.unwrap.table.field.event.key": "aggregate_id",
    "transforms.unwrap.table.field.event.payload": "payload",
    "transforms.unwrap.table.field.event.payload.id": "aggregate_id",
    "transforms.unwrap.route.topic.replacement": "${routedByValue}",
    
    "transforms.addHeaders.type": "org.apache.kafka.connect.transforms.HeaderFrom$Value",
    "transforms.addHeaders.fields": "trace_id,parent_span_id,trace_flags,user_id,tenant_id,request_id,transaction_type",
    "transforms.addHeaders.headers": "trace_id,parent_span_id,trace_flags,X-User-ID,X-Tenant-ID,X-Request-ID,X-Transaction-Type",
    
    "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
    "transforms.route.regex": "([^.]+)\\.([^.]+)\\.([^.]+)",
    "transforms.route.replacement": "$3-events",
    
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false"
  }
}
```

## Common Patterns

### 1. Trace Context Preservation in Async Operations

```java
// AsyncWithTraceContext.java
import io.opentelemetry.context.Context;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class AsyncWithTraceContext {
    
    public CompletableFuture<String> asyncOperationWithContext(String input) {
        // Capture current context
        Context context = Context.current();
        
        return CompletableFuture.supplyAsync(() -> {
            // Restore context in async thread
            try (var scope = context.makeCurrent()) {
                // This will have the same trace context
                return processAsync(input);
            }
        });
    }
    
    // For executors
    public Executor wrapExecutor(Executor executor) {
        return (runnable) -> {
            Context context = Context.current();
            executor.execute(() -> {
                try (var scope = context.makeCurrent()) {
                    runnable.run();
                }
            });
        };
    }
}
```

### 2. Sampling Strategy

```yaml
# application.yml - Sampling configuration
otel:
  traces:
    sampler:
      # Always sample errors
      error_rate: 1.0
      # Sample 10% of normal traffic
      default_rate: 0.1
      # Sample 100% of specific endpoints
      endpoints:
        - path: /api/critical/*
          rate: 1.0
        - path: /health
          rate: 0.0  # Never sample health checks
```

### 3. Custom Span Attributes

```java
// StandardSpanAttributes.java
public class StandardSpanAttributes {
    // Business attributes
    public static final String USER_ID = "user.id";
    public static final String TENANT_ID = "tenant.id";
    public static final String REQUEST_ID = "request.id";
    public static final String TRANSACTION_TYPE = "transaction.type";
    
    // Technical attributes
    public static final String SERVICE_LAYER = "service.layer";
    public static final String DATABASE_OPERATION = "db.operation";
    public static final String CACHE_HIT = "cache.hit";
    public static final String RETRY_COUNT = "retry.count";
    
    public static void addStandardAttributes(Span span, HttpServletRequest request) {
        span.setAttribute(USER_ID, extractUserId(request));
        span.setAttribute(TENANT_ID, extractTenantId(request));
        span.setAttribute(REQUEST_ID, extractRequestId(request));
        span.setAttribute(TRANSACTION_TYPE, deriveTransactionType(request));
    }
}
```

## Troubleshooting

### Common Issues and Solutions

1. **Missing Traces**
   - Verify OTLP endpoint connectivity
   - Check sampling configuration
   - Ensure propagator is configured correctly
   - Verify trace context headers are present

2. **Broken Trace Chains**
   - Check context propagation in async boundaries
   - Verify W3C traceparent format
   - Ensure all services use same propagator
   - Check for context loss in thread pools

3. **Performance Impact**
   - Use batch processors
   - Configure appropriate sampling rates
   - Limit span attributes
   - Use async exporters

4. **Dependency Conflicts (Java)**
   ```xml
   <!-- Use dependency management to avoid conflicts -->
   <dependencyManagement>
       <dependencies>
           <dependency>
               <groupId>io.opentelemetry</groupId>
               <artifactId>opentelemetry-bom</artifactId>
               <version>${opentelemetry.version}</version>
               <type>pom</type>
               <scope>import</scope>
           </dependency>
       </dependencies>
   </dependencyManagement>
   ```

### Debug Logging

```yaml
# Enable debug logging for OpenTelemetry
logging:
  level:
    io.opentelemetry: DEBUG
    io.opentelemetry.exporter.otlp: TRACE
```

### Trace Context Validation

```bash
# Validate W3C traceparent format
echo "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" | \
  grep -E "^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"

# Extract trace ID from traceparent
traceparent="00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
trace_id=$(echo $traceparent | cut -d'-' -f2)
echo "Trace ID: $trace_id"
```

## Best Practices

1. **Use Explicit Instrumentation**: Better control and debugging than auto-instrumentation
2. **Standardize Span Names**: Use consistent naming conventions across services
3. **Add Business Context**: Include user, tenant, and transaction information
4. **Handle Errors Properly**: Record exceptions and set appropriate span status
5. **Batch Operations**: Use batch processors for better performance
6. **Sample Intelligently**: Different sampling rates for different scenarios
7. **Test Trace Propagation**: Include trace validation in integration tests
8. **Monitor Overhead**: Track the performance impact of tracing

## Adoption Strategy: Why Not Libraries Yet?

### The Case Against Premature Abstraction

While it might be tempting to extract these patterns into reusable libraries immediately, **we strongly recommend against it at this stage**. Here's why:

#### The Rule of Three
- **First implementation** ✅: Reference implementation (otel-shortener-demo)
- **Second implementation** ⏳: Teams adapt patterns, discover variations
- **Third implementation** ⏳: Patterns stabilize, abstractions become clear

We're only at stage one. Creating libraries now risks building the wrong abstractions.

#### Unknown Requirements
Each team/service will have unique needs:
- Different sampling strategies (high-volume vs. critical services)
- Various async patterns (RabbitMQ, SQS, Pub/Sub, Kinesis)
- Security constraints (PII filtering, encryption, compliance)
- Performance profiles (batch sizes, export intervals, cardinality)
- Framework versions and compatibility requirements

### Recommended Incremental Approach

#### Phase 1: Reference Implementation (Months 1-3)
**Current Stage - Start Here**
- Teams study this guide and the demo application
- Copy and adapt patterns to their specific needs
- Document what works and what doesn't
- Share learnings across teams

#### Phase 2: Pattern Emergence (Months 4-6)
**After 2-3 Pilot Projects**
- Identify truly common patterns across implementations
- Document variations and customizations needed
- Create shared code snippets and templates
- Build organizational expertise

#### Phase 3: Library Extraction (Months 7+)
**With Proven Patterns**
- Extract only battle-tested abstractions
- Create thin, composable libraries
- Provide escape hatches for customization
- Maintain versioning carefully

### What to Use Instead of Libraries

#### 1. Template Repositories
Create starter templates that teams can fork and modify:
```
otel-java-service-template/
otel-nodejs-service-template/
otel-python-service-template/
```

#### 2. Code Snippet Collection
Maintain a searchable collection of proven patterns:
- Kafka consumer with trace extraction
- RestTemplate with context propagation
- Outbox pattern with CDC
- Circuit breaker with tracing
- Batch job trace initialization

#### 3. Decision Matrix
| Scenario | Pattern to Use | Reference Code |
|----------|---------------|----------------|
| Sync HTTP calls | W3C propagation with interceptors | `TracingRestTemplateConfig.java` |
| Kafka messaging | Manual header injection/extraction | `KafkaConsumerWithTracing.java` |
| Background jobs | Context capture and restore | `AsyncWithTraceContext.java` |
| High-volume endpoints | Sampling with 0.1-1% rate | Sampling configuration |
| Database operations | Span per query with attributes | JDBC instrumentation |

### When to Create Libraries

Wait for these signals before extracting libraries:
- ✅ **3+ successful implementations** using these patterns
- ✅ **Minimal variations** between implementations (< 20% custom code)
- ✅ **Teams requesting** shared libraries (pull vs. push)
- ✅ **Dedicated maintainers** available for library lifecycle
- ✅ **Clear understanding** of the 80/20 rule (common vs. unique)

### Benefits of Waiting

1. **Deeper Understanding**: Teams learn OpenTelemetry internals, not just library APIs
2. **Flexibility**: Each team can optimize for their specific needs
3. **Reduced Risk**: Avoid maintaining libraries that don't fit real requirements
4. **Better Abstractions**: Libraries based on proven patterns, not assumptions
5. **Team Buy-in**: Engineers who understand the patterns will better adopt libraries

### The Cost of Premature Libraries

Creating libraries too early often leads to:
- **Configuration explosion** trying to satisfy every use case
- **Breaking changes** as requirements emerge
- **Shadow implementations** when the library doesn't fit
- **Maintenance burden** without clear value
- **Slower adoption** due to abstraction complexity

### Our Recommendation

**Use this guide and reference implementation as your starting point**:

1. **Study** the reference implementation thoroughly
2. **Copy** the relevant patterns to your service
3. **Adapt** them to your specific requirements
4. **Document** what you changed and why
5. **Share** your learnings with other teams
6. **Wait** until patterns stabilize before creating libraries

After 2-3 successful pilots, you'll have the experience needed to extract the right abstractions that actually accelerate adoption rather than complicate it.

> **Remember**: It's much easier to extract abstractions from working code than to predict the right abstractions upfront. Let the patterns emerge from real usage.

## Conclusion

This guide provides production-ready patterns for implementing OpenTelemetry across your entire stack. The key to successful adoption is:

1. Start with explicit instrumentation for better control
2. Ensure trace context propagation at all boundaries
3. Add business context for meaningful observability
4. Test thoroughly, especially async boundaries
5. Monitor performance impact and adjust sampling
6. **Resist premature abstraction** - let patterns emerge from real usage

Remember: The goal is not to trace everything, but to trace what matters for understanding your system's behavior and quickly resolving issues.