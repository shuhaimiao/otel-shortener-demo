# OpenTelemetry Context Propagation Through RabbitMQ

This document provides a comprehensive technical deep-dive into how **distributed trace context propagation** is implemented across RabbitMQ message boundaries in this demo application.

## 🎯 **Overview**

Our implementation enables **end-to-end distributed tracing** across asynchronous message queues by manually implementing the **W3C Trace Context standard** for RabbitMQ. This ensures that spans created on the consumer side are properly linked to the producer's trace, maintaining trace continuity across service boundaries.

### **High-Level Architecture**
```
HTTP Request → Product Service → RabbitMQ → Audit Service
     ↓              ↓              ↓            ↓
 Trace ID: A    Trace ID: A   {traceparent}  Trace ID: A
 Span ID: 1     Span ID: 2    header: A-2    Span ID: 3 (parent: 2)
```

---

## 🚀 **Producer Side Implementation**

### **File**: `product-service/src/main/java/com/example/opentelemetry/product/service/TracingMessagePublisher.java`

### **1. Context Injection Mechanism**

#### **TextMapSetter Definition**
```java
// Defines HOW to inject trace context into RabbitMQ message headers
private static final TextMapSetter<MessageProperties> SETTER = (carrier, key, value) -> {
    if (carrier != null) {
        carrier.setHeader(key, value);  // Inject into RabbitMQ MessageProperties
    }
};
```

**Purpose**: The `TextMapSetter` is a functional interface that tells OpenTelemetry's propagator **where to write** trace context data. In our case, it writes to RabbitMQ message headers.

#### **Span Creation & Context Injection**
```java
public void publishAuditMessage(String messageContent) {
    // 1. Create a PRODUCER span with messaging attributes
    Span span = tracer.spanBuilder("audit.queue send")
            .setSpanKind(SpanKind.PRODUCER)                    // OpenTelemetry semantic convention
            .setAttribute("messaging.system", "rabbitmq")      // Required messaging attribute
            .setAttribute("messaging.destination", AUDIT_QUEUE) // Target queue name
            .setAttribute("messaging.destination_kind", "queue") // Destination type
            .startSpan();

    try (var scope = span.makeCurrent()) {
        // 2. Make span active in current thread context
        // This ensures inject() captures the correct trace context
        
        // 3. Create RabbitMQ message properties
        MessageProperties messageProperties = new MessageProperties();
        
        // 4. INJECT current trace context into message headers
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), messageProperties, SETTER);
        
        // 5. At this point, messageProperties.headers contains trace context
        // Example: {traceparent=00-traceId-spanId-flags}
        
        // 6. Create and send message with trace headers
        Message message = MessageBuilder.withBody(messageContent.getBytes())
                .andProperties(messageProperties)
                .build();
                
        rabbitTemplate.send(AUDIT_QUEUE, message);
    }
}
```

### **2. What Gets Injected**

The **W3C Trace Context Propagator** injects a `traceparent` header following the standard format:

```
Header Key: traceparent
Header Value: 00-<trace-id>-<parent-span-id>-<trace-flags>

Example:
traceparent: 00-bca617cf49b86cc970b33b0ba8719922-8c0d6644244a0238-01
```

**Field Breakdown**:
- **`00`**: Version field (always `00` for current W3C standard)
- **`bca617cf...`**: **Trace ID** (128-bit hex, identifies the entire distributed trace)
- **`8c0d6644...`**: **Parent Span ID** (64-bit hex, identifies the current producer span)
- **`01`**: **Trace Flags** (8-bit, indicates sampling decision: `01` = sampled, `00` = not sampled)

### **3. Producer Span Attributes**

Following OpenTelemetry semantic conventions for messaging:

```java
.setAttribute("messaging.system", "rabbitmq")           // Required: messaging system name
.setAttribute("messaging.destination", "audit.queue")   // Required: destination name  
.setAttribute("messaging.destination_kind", "queue")    // Optional: destination type
.setAttribute("messaging.operation", "publish")         // Optional: operation type
```

---

## 📥 **Consumer Side Implementation**

### **File**: `audit-service/src/main/java/com/example/opentelemetry/audit/service/TracingMessageConsumer.java`

### **1. Context Extraction Mechanism**

#### **TextMapGetter Definition**
```java
// Defines HOW to extract trace context from RabbitMQ message headers
private static final TextMapGetter<Message> GETTER = new TextMapGetter<Message>() {
    @Override
    public Iterable<String> keys(Message carrier) {
        // Return all available header keys from the RabbitMQ message
        return carrier.getMessageProperties().getHeaders().keySet().stream()
                .map(Object::toString)
                .toList();
    }

    @Override
    public String get(Message carrier, String key) {
        // Extract specific header value (e.g., "traceparent")
        Object value = carrier.getMessageProperties().getHeaders().get(key);
        return value != null ? value.toString() : null;
    }
};
```

**Purpose**: The `TextMapGetter` tells OpenTelemetry's propagator **how to read** trace context data from RabbitMQ message headers.

#### **Context Extraction & Span Linking**
```java
@RabbitListener(queues = RabbitConfig.AUDIT_QUEUE)
public void handleAuditMessage(Message message) {
    // 1. Extract trace context from incoming message headers
    Context extractedContext = openTelemetry.getPropagators().getTextMapPropagator()
            .extract(Context.current(), message, GETTER);
    
    // 2. Create CONSUMER span with extracted context as parent
    Span span = tracer.spanBuilder("audit.queue receive")
            .setParent(extractedContext)        // 🎯 CRITICAL: Links to producer's trace
            .setSpanKind(SpanKind.CONSUMER)     // OpenTelemetry semantic convention
            .setAttribute("messaging.system", "rabbitmq")
            .setAttribute("messaging.destination", RabbitConfig.AUDIT_QUEUE)
            .setAttribute("messaging.operation", "receive")
            .startSpan();

    try (var scope = span.makeCurrent()) {
        // 3. Process message within the linked trace context
        String messageBody = new String(message.getBody());
        
        // 4. All subsequent operations inherit this trace context
        // Any child spans created here will be part of the same trace
        processMessage(messageBody);
    } finally {
        span.end();
    }
}
```

### **2. Context Linking Process**

#### **Before Context Extraction**
```java
// Consumer thread has no trace context initially
Context.current() = {}  // Empty context, no active trace
```

#### **After Context Extraction**
```java
// Extracted context contains producer's trace information
extractedContext = {
    traceId: "bca617cf49b86cc970b33b0ba8719922",     // SAME as producer
    parentSpanContext: {
        spanId: "8c0d6644244a0238",                  // Producer's span ID
        traceFlags: "01",                            // Sampling decision
        remote: true                                 // Indicates cross-process boundary
    }
}
```

#### **Span Relationship Creation**
```java
// Consumer span becomes CHILD of producer span
span.setParent(extractedContext)

// Result: Hierarchical trace structure
// Producer Span (spanId: 8c0d6644244a0238)
//   └── Consumer Span (spanId: fa6cb1d79fd43d31, parentSpanId: 8c0d6644244a0238)
```

---

## 🧩 **Propagator Configuration**

### **OpenTelemetry SDK Setup**

Both producer and consumer services use identical propagator configuration:

```java
// File: */config/OpenTelemetryConfig.java
@Bean
public OpenTelemetry openTelemetry() {
    return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(
                    TextMapPropagator.composite(
                            W3CTraceContextPropagator.getInstance(),    // Handles 'traceparent' header
                            W3CBaggagePropagator.getInstance())))       // Handles 'baggage' header
            .build();
}
```

### **Propagator Details**

#### **W3CTraceContextPropagator**
- **Responsibility**: Manages `traceparent` and `tracestate` headers
- **Format**: W3C Trace Context specification compliant
- **Cross-Service**: Works across different technology stacks

#### **W3CBaggagePropagator**  
- **Responsibility**: Manages `baggage` header for custom key-value pairs
- **Use Case**: Passing business context (user ID, tenant ID, etc.)
- **Format**: W3C Baggage specification compliant

### **Why Composite Propagator?**
```java
TextMapPropagator.composite(
    W3CTraceContextPropagator.getInstance(),    // Core trace linking
    W3CBaggagePropagator.getInstance()          // Additional business context
)
```

The composite propagator ensures **both** trace context **and** baggage are propagated, providing complete context transmission.

---

## 🔍 **Step-by-Step Trace Flow**

### **Complete Request Lifecycle**

1. **HTTP Request Arrives**
   ```
   Trace ID: bca617cf49b86cc970b33b0ba8719922
   Span ID:  fa90b738130bc3d0 (HTTP span)
   ```

2. **Product Service Processing**
   ```
   Trace ID: bca617cf49b86cc970b33b0ba8719922  ← Same trace
   Span ID:  1a2b3c4d5e6f7890 (business logic span, parent: fa90b738130bc3d0)
   ```

3. **Message Publication**
   ```
   Trace ID: bca617cf49b86cc970b33b0ba8719922  ← Same trace
   Span ID:  8c0d6644244a0238 (producer span, parent: 1a2b3c4d5e6f7890)
   
   Message Headers: {
     traceparent: "00-bca617cf49b86cc970b33b0ba8719922-8c0d6644244a0238-01"
   }
   ```

4. **Message Consumption**
   ```
   Trace ID: bca617cf49b86cc970b33b0ba8719922  ← Same trace
   Span ID:  fa6cb1d79fd43d31 (consumer span, parent: 8c0d6644244a0238)
   ```

5. **Message Processing**
   ```
   Trace ID: bca617cf49b86cc970b33b0ba8719922  ← Same trace  
   Span ID:  9fab00975958defc (processing span, parent: fa6cb1d79fd43d31)
   ```

### **Jaeger UI Representation**
```
Trace: bca617cf49b86cc970b33b0ba8719922
│
├── spa-app: HTTP GET [fa90b738130bc3d0]
│   └── product-service: price-check [1a2b3c4d5e6f7890]
│       ├── product-service: audit.queue send [8c0d6644244a0238]
│       └── audit-service: audit.queue receive [fa6cb1d79fd43d31]
│           └── audit-service: message processing [9fab00975958defc]
```

---

## 🧪 **Debugging Context Propagation**

### **Producer Debug Logs**
```log
INFO  [product-service] Publishing audit message with trace context: Product 1 (Laptop) price checked: $1200.50
INFO  [product-service] Injected trace headers: {traceparent=00-bca617cf49b86cc970b33b0ba8719922-8c0d6644244a0238-01}
INFO  [product-service] Audit message published successfully with trace context
```

### **Consumer Debug Logs**
```log
INFO  [audit-service] Received message headers: {traceparent=00-bca617cf49b86cc970b33b0ba8719922-6a290da769872546-01}
INFO  [audit-service] Extracted context: {traceId=bca617cf49b86cc970b33b0ba8719922, spanId=6a290da769872546}
INFO  [audit-service] Received audit message with trace context: Product 1 (Laptop) price checked: $1200.50
```

### **Success Validation Checklist**

✅ **Same Trace ID**: Producer and consumer logs show identical trace IDs  
✅ **Headers Present**: `traceparent` header exists in message  
✅ **Context Extraction**: Consumer successfully extracts trace context  
✅ **Span Linking**: Consumer span shows producer span as parent in Jaeger  
✅ **Continuous Timeline**: Spans appear in chronological order  

### **Common Issues & Solutions**

| Issue | Symptom | Solution |
|-------|---------|----------|
| **Missing Headers** | No `traceparent` in consumer logs | Check producer's `SETTER` implementation |
| **Different Trace IDs** | Producer/consumer have different trace IDs | Verify propagator configuration matches |
| **Broken Span Chain** | Spans exist but not linked in Jaeger | Ensure `.setParent(extractedContext)` is called |
| **No Consumer Spans** | Only producer spans visible | Check consumer's `GETTER` implementation |

---

## 🔄 **Critical Concept: `span.makeCurrent()`**

### **The Context Activation Mechanism**

**`span.makeCurrent()`** is a **fundamental OpenTelemetry concept** that activates a span in the current thread's execution context. This is **essential for proper trace propagation** because:

```java
try (var scope = span.makeCurrent()) {
    // span becomes "active" - Context.current() now contains this span
    // inject() will capture THIS span's context for propagation
    propagator.inject(Context.current(), headers, setter);
}
// Scope closes - previous context restored automatically
```

**Without `makeCurrent()`**: Context propagation **fails** because `Context.current()` would not contain the span we want to propagate.

### **Manual vs. Automatic Implementation**

**Our Demo**: Uses **manual instrumentation** with explicit `makeCurrent()` calls for educational purposes and full control.

**Production Note**: Many modern message queue frameworks (Spring Cloud Stream, Kafka Streams, newer versions of Spring AMQP) provide **automatic instrumentation** that handles `makeCurrent()` internally, enabling **zero-code distributed tracing**. However, understanding the manual approach is crucial for:

- **Custom protocols** not automatically supported
- **Legacy systems** requiring manual integration  
- **Debugging** when automatic instrumentation fails
- **Advanced use cases** requiring custom span attributes or sampling

**Framework Examples with Automatic Support**:
- **Spring Cloud Stream** with OpenTelemetry
- **Apache Kafka** with OpenTelemetry Java Agent
- **AWS SQS/SNS** with OpenTelemetry instrumentation
- **Google Cloud Pub/Sub** with OpenTelemetry libraries

---

## 🏗️ **Implementation Patterns**

### **1. Error Handling Pattern**
```java
try (var scope = span.makeCurrent()) {
    // Business logic
    span.setStatus(StatusCode.OK);
} catch (Exception e) {
    span.setStatus(StatusCode.ERROR, e.getMessage());
    span.recordException(e);  // Adds exception details to span
    throw e;  // Re-throw to maintain application behavior
} finally {
    span.end();  // Always end span
}
```

### **2. Nested Span Pattern**
```java
// Consumer can create child spans for different processing phases
try (var scope = consumerSpan.makeCurrent()) {
    
    // Child span for validation
    Span validationSpan = tracer.spanBuilder("message.validation").startSpan();
    try (var validationScope = validationSpan.makeCurrent()) {
        validateMessage(message);
    } finally {
        validationSpan.end();
    }
    
    // Child span for processing  
    Span processingSpan = tracer.spanBuilder("message.processing").startSpan();
    try (var processingScope = processingSpan.makeCurrent()) {
        processMessage(message);
    } finally {
        processingSpan.end();
    }
}
```

### **3. Attribute Enrichment Pattern**
```java
// Add business context to spans
span.setAttribute("message.size", message.getBody().length);
span.setAttribute("queue.name", queueName);
span.setAttribute("correlation.id", correlationId);
span.setAttribute("user.id", userId);
```

---

## 🎖️ **Why This Implementation Works**

### **1. Standards Compliance**
- **W3C Trace Context**: Industry standard for trace propagation
- **OpenTelemetry Conventions**: Follows semantic conventions for messaging
- **Cross-Platform**: Works with other W3C-compliant systems

### **2. Reliability Features**
- **Graceful Degradation**: Works even if headers are missing or malformed
- **Error Isolation**: Tracing failures don't break business logic
- **Resource Management**: Proper span lifecycle management

### **3. Observability Benefits**
- **End-to-End Visibility**: Complete request flow across async boundaries
- **Performance Analysis**: Identify bottlenecks in message processing
- **Error Correlation**: Link errors across services and message queues

### **4. Operational Advantages**
- **Debugging**: Trace requests through complex async flows
- **Monitoring**: Detect message queue performance issues
- **Alerting**: Set up alerts on trace errors or latency

---

## 🚀 **Advanced Topics**

### **Baggage Propagation**
```java
// Producer side - add business context
Baggage baggage = Baggage.current()
    .toBuilder()
    .put("user.id", userId)
    .put("tenant.id", tenantId)
    .build();

try (var scope = baggage.makeCurrent()) {
    // Publish message - baggage automatically propagated
}

// Consumer side - access business context
String userId = Baggage.current().get("user.id");
String tenantId = Baggage.current().get("tenant.id");
```

### **Custom Propagation Headers**
```java
// Add custom headers alongside standard trace context
messageProperties.setHeader("x-correlation-id", correlationId);
messageProperties.setHeader("x-request-source", "web-app");
```

### **Sampling Considerations**
```java
// Respect sampling decisions from upstream
if (span.getSpanContext().isSampled()) {
    // Perform expensive operations only for sampled traces
    addDetailedAttributes(span);
}
```

---

This implementation provides a robust foundation for distributed tracing across message queue boundaries, enabling comprehensive observability in microservice architectures. 