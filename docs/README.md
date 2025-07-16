# Full-Stack Distributed Tracing Demo with OpenTelemetry and Standard Context

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

This project demonstrates **end-to-end distributed tracing** with **zero-code context propagation** in a modern, full-stack application. It features **OpenTelemetry Spring Boot Starter** for automatic instrumentation, **JWT-based context propagation**, and **RabbitMQ** for asynchronous messaging. Traces are collected using OpenTelemetry and visualized with Jaeger, with **complete trace context propagation** across HTTP requests and message queues, adhering to the W3C Trace Context standard.

## Architecture

The application consists of:
- **Frontend**: React SPA with OpenTelemetry Browser SDK  
- **BFF**: Node.js service with JWT context extraction and forwarding
- **Microservices**: Java Spring Boot services with zero-code OpenTelemetry instrumentation
- **Message Queue**: RabbitMQ with manual trace context propagation
- **Observability**: Jaeger for trace visualization

```mermaid
graph TD;
    subgraph "Browser"
        SPA[spa-app<br/>OpenTelemetry Browser SDK]
    end

    subgraph "Docker Network - Zero-Code Instrumentation"
        BFF[bff-service<br/>JWT Context Extraction]
        ProductService[product-service<br/>OpenTelemetry Spring Boot Starter]
        PriceService[price-service<br/>OpenTelemetry Spring Boot Starter]
        RabbitMQ[(RabbitMQ<br/>Manual Context Propagation)]
        AuditService[audit-service<br/>OpenTelemetry Spring Boot Starter]
        Jaeger[Jaeger UI<br/>Trace Visualization]
    end

    SPA -- "HTTP + JWT Token" --> BFF;
    BFF -- "HTTP + JWT Forwarding" --> ProductService;
    ProductService -- "HTTP + Zero-Code JWT Forwarding<br/>(RestTemplate & WebClient)" --> PriceService;
    ProductService -- "RabbitMQ Message<br/>(Manual Trace Context)" --> RabbitMQ;
    RabbitMQ -- "Message Consumption<br/>(Manual Trace Context)" --> AuditService;

    SPA -- "Exports Traces" --> BFF;
    BFF -- "Exports & Forwards Traces" --> Jaeger;
    ProductService -- "Exports Traces" --> Jaeger;
    PriceService -- "Exports Traces" --> Jaeger;
    AuditService -- "Exports Traces" --> Jaeger;
```

## Features

### 🎯 **Core Application Features**
- **Full-stack application** with React, Node.js (Express), and Java (Spring Boot)
- **Zero-code OpenTelemetry instrumentation** with OpenTelemetry Spring Boot Starter
- **JWT-based context propagation** across all HTTP services
- **Standard context library** for consistent field definitions
- **RabbitMQ message queuing** with manual trace context propagation
- **End-to-end distributed tracing** from browser to message consumers
- **W3C Trace Context standard** compliance for cross-service tracing

### 🚀 **Technical Features**
- **OpenTelemetry Spring Boot Starter** for automatic HTTP client instrumentation
- **Zero-code JWT forwarding** via WebClient and RestTemplate customizers
- **Standard Context Filter Library** for consistent context extraction
- **Hybrid instrumentation** (automatic HTTP + manual messaging)
- **Context-aware structured logging** with automatic field injection
- **Containerized deployment** using Docker Compose
- **Enterprise-ready patterns** with shared library architecture

## 🏗️ **Zero-Code Architecture**

### **HTTP Client Instrumentation** ✅
- **WebClient**: Automatic JWT forwarding via `StandardContextWebClientCustomizer`
- **RestTemplate**: Automatic JWT forwarding via `StandardContextRestTemplateInterceptor`  
- **OpenTelemetry**: Automatic traceparent propagation via Spring Boot Starter

### **Context Propagation Pattern** 🎯
1. **JWT Token**: Contains all user/tenant context (primary source)
2. **Traceparent**: W3C trace context (automatic via OpenTelemetry)
3. **Local Extraction**: Each service extracts context from received JWT
4. **Zero Manual Headers**: No manual context header manipulation

### **Standard Context Fields** 📋
- `tenant.id`, `tenant.name` - Multi-tenancy support
- `user.id`, `user.roles` - User authentication  
- `request.id` - Request correlation
- `traceparent` - W3C trace context
- `service.name` - Service identification

## Technologies Used

- **Frontend**: React, OpenTelemetry Browser SDK
- **Backend**: Node.js, Express, Java 17, Spring Boot 3
- **Instrumentation**: OpenTelemetry Spring Boot Starter 2.10.0
- **Context Library**: Custom Spring Boot Starter for context propagation
- **Message Queue**: RabbitMQ with manual trace context propagation
- **Build**: Maven, npm
- **Containerization**: Docker & Docker Compose
- **Observability**: OpenTelemetry, Jaeger UI

## Prerequisites

Before you begin, ensure you have the following installed on your system:

-   Docker
-   Docker Compose

## Getting Started

Follow these steps to get the application up and running.

### 1. Clone the Repository

```bash
git clone https://github.com/shuhaimiao/open-telemetry-example.git
cd open-telemetry-example
```

### 2. Start the Application Stack

Use Docker Compose to build the Docker images and start all services and infrastructure.

```bash
docker-compose up --build -d
```

This command builds the images and starts the containers in detached mode.

### 3. Wait for Services to Start

All services need time to initialize. Wait about 30 seconds for:
- RabbitMQ to fully start
- All Spring Boot services to connect to RabbitMQ
- Jaeger to be ready for trace collection

## Usage

Once the application stack is running, you can interact with the services and observe distributed traces.

### Accessing the Application

-   **React SPA**: [http://localhost:8000](http://localhost:8000)
-   **BFF Service**: [http://localhost:3000](http://localhost:3000)
-   **Product Service**: [http://localhost:8080](http://localhost:8080)
-   **Price Service**: [http://localhost:8081](http://localhost:8081)
-   **Jaeger UI**: [http://localhost:16686](http://localhost:16686)
-   **RabbitMQ Management UI**: [http://localhost:15672](http://localhost:15672) (guest/guest)

### Testing Zero-Code Context Propagation

#### 1. Test Complete JWT Context Flow
```bash
# Test with JWT token containing full context
curl -X GET "http://localhost:8080/webflux/product/1" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJqb2huLmRvZSIsInRlbmFudF9pZCI6ImFjbWUtY29ycCIsInRlbmFudF9uYW1lIjoiQUNNRSBDb3Jwb3JhdGlvbiIsInVzZXJfaWQiOiJqb2huLmRvZSIsInVzZXJfcm9sZXMiOiJ1c2VyLHByZW1pdW0iLCJyZXF1ZXN0X2lkIjoic3BhLTE3NTEzOTY2ODkiLCJyZXF1ZXN0X2NvbnRleHQiOiJ3ZWItdWkiLCJpYXQiOjE1MTYyMzkwMjJ9.8VLX5t2EvFbO3NKSZ-AKWCbmGlbxLaOOCtLJ0n9lT2M" \
  -H "Content-Type: application/json"
```

#### 2. Test via BFF Service (JWT Forwarding)
```bash
# Test through BFF service with JWT extraction and forwarding
curl http://localhost:3000/api/product/1
```

#### 3. Test WebClient Zero-Code Propagation
```bash
# Test WebFlux endpoint (uses WebClient with zero-code JWT forwarding)
curl "http://localhost:8080/webflux/product/1" \
  -H "Authorization: Bearer [JWT_TOKEN]"
```

#### 4. Test RestTemplate Zero-Code Propagation  
```bash
# Test MVC endpoint (uses RestTemplate with zero-code JWT forwarding)
curl "http://localhost:8080/product/1" \
  -H "Authorization: Bearer [JWT_TOKEN]"
```

### Expected Zero-Code Context Flow

When you make a request with a JWT token, the following happens automatically:

1. **Request with JWT** → BFF extracts context from JWT claims
2. **BFF** → Forwards JWT to Product Service (automatic header forwarding)
3. **Product Service** → Extracts context locally from JWT + forwards to Price Service  
4. **Price Service** → Receives JWT and extracts same context locally
5. **Product Service** → RabbitMQ message with manual trace context injection
6. **Audit Service** → Consumes message with trace context + JWT extraction

**Context Propagation Pattern**:
- ✅ **JWT Token**: User/tenant context (`tenant.id`, `user.id`, `user.roles`)
- ✅ **Traceparent**: OpenTelemetry trace context (automatic)
- ✅ **Local Extraction**: Each service extracts context from received JWT
- ✅ **Zero Manual Headers**: No manual context headers needed

### Viewing Traces in Jaeger

1. Open [Jaeger UI](http://localhost:16686)
2. Select **"spa-app"** service in the dropdown
3. Click **"Find Traces"**
4. Click on any trace to see the complete flow:
   - Frontend spans
   - HTTP service calls  
   - **RabbitMQ producer span** (`audit.queue send`)
   - **RabbitMQ consumer span** (`audit.queue receive`)
   - Message processing spans

### Monitoring RabbitMQ

1. Open [RabbitMQ Management UI](http://localhost:15672)
2. Login with **guest/guest**
3. Check the **"audit.queue"** for message statistics
4. View message rates and queue depth

### Viewing Aggregated Logs

All services are configured to write structured logs. You can view logs from specific services:

```bash
# View product service logs (shows trace context injection)
docker logs distributed-tracing-demo-product-service-1

# View audit service logs (shows trace context extraction)  
docker logs distributed-tracing-demo-audit-service-1

# View all logs from a specific service
docker logs -f distributed-tracing-demo-[service-name]-1
```

Look for **zero-code context propagation** in the logs:

**Product Service** (JWT extraction + zero-code forwarding):
```json
{
  "message": "Fetching Price Details using WebClient (zero-code JWT + traceparent propagation)",
  "tenant.id": "acme-corp",
  "user.id": "john.doe", 
  "user.roles": "user,premium",
  "trace.id": "245628baa857a02e5a00ee3302ca77af"
}
```

**Price Service** (receiving forwarded JWT):
```json
{
  "message": "Getting Price details for Product Id 1 using WebFlux",
  "tenant.id": "acme-corp",
  "tenant.name": "ACME Corporation",
  "user.id": "john.doe",
  "user.roles": "user,premium", 
  "traceparent": "00-245628baa857a02e5a00ee3302ca77af-e2e51b156245bcce-01"
}
```

## Zero-Code Context Propagation Implementation

This demo implements **complete zero-code context propagation** across HTTP requests using OpenTelemetry Spring Boot Starter and a custom Standard Context Filter Library.

### 📚 **Technical Deep-Dive**

For comprehensive documentation, see:
- **[CONTEXT_FILTER_LIBRARY.md](./CONTEXT_FILTER_LIBRARY.md)** - Standard Context Library documentation
- **[CONTEXT_PROPAGATION.md](./CONTEXT_PROPAGATION.md)** - RabbitMQ manual trace context

### **🎯 Zero-Code Architecture**

- **OpenTelemetry Spring Boot Starter** for automatic HTTP client instrumentation
- **JWT-only propagation** with local context extraction on each service
- **WebClient & RestTemplate customizers** for automatic JWT forwarding
- **Standard Context Filter Library** for consistent context field definitions
- **W3C Trace Context** automatic propagation via OpenTelemetry
- **Manual RabbitMQ propagation** using TextMapSetter/Getter pattern

### **🔧 Zero-Code HTTP Client Patterns**

This demo demonstrates **zero-code context propagation** with both HTTP client approaches:

#### **RestTemplate**: Zero-Code JWT Forwarding ✅
```java
// PriceClient.java - No manual headers needed!
public Price getPrice(long productId) {
    String url = String.format("%s/price/%d", baseUrl, productId);
    return restTemplate.getForObject(url, Price.class);  // JWT forwarded automatically
}
```
- **Auto-Configuration**: `StandardContextRestTemplateInterceptor` registered automatically
- **JWT Forwarding**: Authorization header forwarded to all outgoing calls
- **OpenTelemetry**: Traceparent propagated automatically via Spring Boot Starter

#### **WebClient**: Zero-Code JWT Forwarding ✅  
```java
// PriceWebClient.java - No manual headers needed!
public Mono<Price> getPrice(long productId) {
    return webClient.get()
        .uri("/webflux/price/{id}", productId)  // JWT forwarded automatically
        .retrieve()
        .bodyToMono(Price.class);
}
```
- **Auto-Configuration**: `StandardContextWebClientCustomizer` registered automatically  
- **JWT Forwarding**: Authorization header forwarded to all outgoing calls
- **OpenTelemetry**: Traceparent propagated automatically via Spring Boot Starter

### **🎯 Architecture Benefits**

- ✅ **Zero Boilerplate**: No manual header manipulation required
- ✅ **Consistent Pattern**: Same JWT forwarding across RestTemplate and WebClient
- ✅ **Local Context Extraction**: Each service extracts context from received JWT
- ✅ **OpenTelemetry Compliant**: W3C trace context propagated automatically
- ✅ **Enterprise Ready**: Shared library pattern for consistency across teams

### **🗂️ Key Implementation Files**

**Standard Context Filter Library**:
- `StandardContext.java` - Context field definitions and configuration
- `StandardContextWebClientCustomizer.java` - Zero-code WebClient JWT forwarding
- `StandardContextRestTemplateInterceptor.java` - Zero-code RestTemplate JWT forwarding  
- `StandardContextAutoConfiguration.java` - Spring Boot auto-configuration

**OpenTelemetry Configuration**:
- **OpenTelemetry Spring Boot Starter** - Automatic HTTP client instrumentation (replaces manual config)
- `TracingMessagePublisher.java` - Manual RabbitMQ producer context injection
- `TracingMessageConsumer.java` - Manual RabbitMQ consumer context extraction

**Service Implementations**:
- `PriceWebClient.java` - Clean WebClient implementation (zero manual headers)
- `PriceClient.java` - Clean RestTemplate implementation (zero manual headers)

## Troubleshooting

### Common Issues

1. **Services not starting**: Wait longer for RabbitMQ initialization
2. **Missing traces**: Check that all services are connected to Jaeger
3. **Broken trace chains**: Verify trace context headers in logs
4. **RabbitMQ connection errors**: Ensure RabbitMQ is fully started before services

### Debugging Commands

```bash
# Check service health
docker-compose ps

# View service logs
docker logs [container-name]

# Rebuild specific service
docker-compose build --no-cache [service-name]
docker-compose restart [service-name]

# Check RabbitMQ queue status
curl -u guest:guest http://localhost:15672/api/queues
```

### Stopping the Application

To stop and remove the containers, use the following command:

```bash
docker-compose down
```

To remove volumes as well:

```bash
docker-compose down -v
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test the distributed tracing flow
5. Submit a pull request

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
