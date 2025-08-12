# Design Specification: E2E Traceability Enhancements

## 1. Executive Summary

This document presents the technical design for enhancing the otel-shortener-demo with enterprise-grade distributed tracing capabilities. The design focuses on Java-based implementations for background job processing and WebSocket support, while integrating NGINX, Kong, and Redis for complete observability.

## 2. Architecture Overview

### 2.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         Internet                             │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    NGINX (Edge Layer)                        │
│  • SSL Termination  • Load Balancing  • Static Caching       │
│  • OpenTelemetry Module  • Rate Limiting                     │
└──────────────────────────┬──────────────────────────────────┘
                           │ traceparent
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  Kong API Gateway                            │
│  • Dynamic Routing  • Authentication  • Circuit Breaker      │
│  • Request Transform  • OpenTelemetry Plugin                 │
└──────────────────────────┬──────────────────────────────────┘
                           │ traceparent + baggage
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                      BFF (Node.js)                           │
│  • API Aggregation  • Session Management  • Redis Cache      │
└─────────┬───────────────────────────────────┬───────────────┘
          │                                   │
          ▼                                   ▼
┌──────────────────┐                ┌──────────────────┐
│   URL API (Java) │                │ Redirect Service │
│   Spring Boot    │                │   Spring WebFlux │
└────────┬─────────┘                └────────┬─────────┘
         │                                    │
         ▼                                    ▼
┌─────────────────────────────────────────────────────────────┐
│                     PostgreSQL                               │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│                      Kafka                                   │
└──────────────────────────┬──────────────────────────────────┘
                           │
         ┌─────────────────┼─────────────────┐
         ▼                 ▼                 ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ Analytics API│  │Job Scheduler │  │WebSocket Srv │
│    (Java)    │  │   (Java)     │  │   (Java)     │
└──────────────┘  └──────────────┘  └──────────────┘

┌─────────────────────────────────────────────────────────────┐
│              OpenTelemetry Collector                         │
│  • Receive  • Process  • Export to Jaeger                    │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Trace Flow Architecture

```yaml
Trace Initiation Points:
  1. Browser → NGINX (Root span)
  2. Scheduled Jobs (New root spans)
  3. WebSocket Connections (Continued traces)
  4. Webhook Receivers (New root spans)

Trace Propagation:
  - HTTP: W3C traceparent header
  - Kafka: Headers with traceparent
  - WebSocket: Initial handshake + message metadata
  - Redis: Span context in cache metadata
  - Database: SQL comments with trace context
```

## 3. Component Design

### 3.1 NGINX with OpenTelemetry

#### 3.1.1 Module Integration

```nginx
# nginx.conf - OpenTelemetry Configuration
load_module modules/ngx_http_opentelemetry_module.so;

http {
    opentelemetry on;
    opentelemetry_config /etc/nginx/otel-nginx.toml;
    
    # Trace context propagation
    opentelemetry_propagate;
    
    # Custom service name
    opentelemetry_attribute "service.name" "nginx-edge";
    
    # Resource attributes
    opentelemetry_attribute "deployment.environment" "demo";
    opentelemetry_attribute "service.version" "1.0.0";
}
```

#### 3.1.2 OTEL Configuration

```toml
# otel-nginx.toml
exporter = "otlp"
processor = "batch"

[exporters.otlp]
# Endpoint for the OTLP exporter
endpoint = "otel-collector:4317"
protocol = "grpc"

[processors.batch]
max_queue_size = 2048
schedule_delay_millis = 5000
max_export_batch_size = 512

[service]
name = "nginx-edge"

[sampler]
name = "AlwaysOn"
```

#### 3.1.3 Docker Build Strategy

```dockerfile
# nginx/Dockerfile
FROM nginx:1.25-alpine AS builder

# Install build dependencies
RUN apk add --no-cache \
    git build-base cmake \
    linux-headers pcre-dev \
    zlib-dev openssl-dev \
    protobuf-dev c-ares-dev

# Build OpenTelemetry C++ SDK
WORKDIR /tmp
RUN git clone --recurse-submodules -b v1.13.0 \
    https://github.com/open-telemetry/opentelemetry-cpp.git && \
    cd opentelemetry-cpp && \
    mkdir build && cd build && \
    cmake -DBUILD_TESTING=OFF \
          -DWITH_OTLP_GRPC=ON \
          -DWITH_OTLP_HTTP=OFF .. && \
    make -j$(nproc) install

# Build NGINX with OpenTelemetry module
RUN git clone --shallow-submodules --depth 1 \
    https://github.com/open-telemetry/opentelemetry-cpp-contrib.git && \
    cd /tmp && \
    wget https://nginx.org/download/nginx-1.25.3.tar.gz && \
    tar -xzvf nginx-1.25.3.tar.gz && \
    cd nginx-1.25.3 && \
    ./configure --with-compat \
                --add-dynamic-module=/tmp/opentelemetry-cpp-contrib/instrumentation/nginx \
                --prefix=/etc/nginx \
                --sbin-path=/usr/sbin/nginx && \
    make modules

FROM nginx:1.25-alpine
COPY --from=builder /tmp/nginx-1.25.3/objs/ngx_http_opentelemetry_module.so \
                    /usr/lib/nginx/modules/
COPY nginx.conf /etc/nginx/nginx.conf
COPY otel-nginx.toml /etc/nginx/otel-nginx.toml
```

### 3.2 Kong API Gateway

#### 3.2.1 Plugin Configuration

```lua
-- kong/plugins/opentelemetry/handler.lua
local opentelemetry = require("kong.plugins.opentelemetry")

function OpenTelemetryHandler:access(conf)
    -- Extract trace context from incoming request
    local traceparent = kong.request.get_header("traceparent")
    local tracestate = kong.request.get_header("tracestate")
    
    -- Create or continue trace
    local span = opentelemetry.start_span("kong.request", {
        kind = opentelemetry.span_kind.SERVER,
        attributes = {
            ["http.method"] = kong.request.get_method(),
            ["http.url"] = kong.request.get_path(),
            ["http.host"] = kong.request.get_host(),
            ["kong.route"] = kong.router.get_route().name,
            ["kong.service"] = kong.router.get_service().name
        }
    })
    
    -- Store span in context for other plugins
    kong.ctx.shared.opentelemetry_span = span
end

function OpenTelemetryHandler:header_filter(conf)
    local span = kong.ctx.shared.opentelemetry_span
    if span then
        span:set_attribute("http.status_code", kong.response.get_status())
    end
end
```

#### 3.2.2 Declarative Configuration

```yaml
# kong/config.yml
_format_version: "3.0"
_transform: true

services:
  - name: bff-service
    url: http://bff:3001
    routes:
      - name: api-route
        paths: ["/api"]
        strip_path: false
    plugins:
      - name: opentelemetry
        config:
          endpoint: "http://otel-collector:4317"
          resource_attributes:
            service.name: "kong-gateway"
            service.version: "1.0.0"
          batch_span_count: 200
          batch_flush_delay: 2
          
      - name: rate-limiting
        config:
          minute: 60
          policy: local
          fault_tolerant: true
          hide_client_headers: false
          
      - name: correlation-id
        config:
          header_name: "X-Correlation-ID"
          generator: "uuid"
          echo_downstream: true
```

### 3.3 Redis Caching Layer

#### 3.3.1 Java Redis Client with Tracing

```java
// shared-libraries/redis-tracing/src/main/java/com/demo/redis/TracedRedisTemplate.java
package com.demo.redis;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TracedRedisTemplate<K, V> {
    private final RedisTemplate<K, V> redisTemplate;
    private final Tracer tracer;
    
    public TracedRedisTemplate(RedisTemplate<K, V> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.tracer = GlobalOpenTelemetry.getTracer("redis-cache");
    }
    
    public V getWithCache(K key, Supplier<V> dataFetcher, long ttlSeconds) {
        Span span = tracer.spanBuilder("cache.get_with_fallback")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("cache.key", key.toString())
            .setAttribute("cache.operation", "get_with_fallback")
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            // Try cache first
            V cached = redisTemplate.opsForValue().get(key);
            
            if (cached != null) {
                span.setAttribute("cache.hit", true);
                span.setAttribute("cache.source", "redis");
                return cached;
            }
            
            // Cache miss - fetch from source
            span.setAttribute("cache.hit", false);
            
            Span fetchSpan = tracer.spanBuilder("cache.fetch_source")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
            
            V data;
            try (Scope fetchScope = fetchSpan.makeCurrent()) {
                data = dataFetcher.get();
            } finally {
                fetchSpan.end();
            }
            
            // Store in cache
            Span storeSpan = tracer.spanBuilder("cache.store")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("cache.ttl", ttlSeconds)
                .startSpan();
            
            try (Scope storeScope = storeSpan.makeCurrent()) {
                redisTemplate.opsForValue().set(key, data, ttlSeconds, TimeUnit.SECONDS);
            } finally {
                storeSpan.end();
            }
            
            return data;
            
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
    
    public void evict(K key) {
        Span span = tracer.spanBuilder("cache.evict")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("cache.key", key.toString())
            .setAttribute("cache.operation", "evict")
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            Boolean result = redisTemplate.delete(key);
            span.setAttribute("cache.evict.success", result != null && result);
        } finally {
            span.end();
        }
    }
}
```

#### 3.3.2 Redis Configuration

```java
// redis-config/src/main/java/com/demo/config/RedisConfig.java
@Configuration
@EnableCaching
public class RedisConfig {
    
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName("redis-master");
        config.setPort(6379);
        config.setPassword("redis123");
        
        // Enable OpenTelemetry tracing for Lettuce
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .clientResources(ClientResources.builder()
                .tracing(OpenTelemetryTracing.create(GlobalOpenTelemetry.get()))
                .build())
            .build();
        
        return new LettuceConnectionFactory(config, clientConfig);
    }
    
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(15))
            .serializeKeysWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer()));
        
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .transactionAware()
            .build();
    }
}
```

### 3.4 Job Scheduler Service (Java/Spring Boot with Quartz)

#### 3.4.1 Service Architecture

```java
// job-scheduler/src/main/java/com/demo/scheduler/JobSchedulerApplication.java
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class JobSchedulerApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobSchedulerApplication.class, args);
    }
}
```

#### 3.4.2 Quartz Configuration with OpenTelemetry

```java
// job-scheduler/src/main/java/com/demo/scheduler/config/QuartzConfig.java
@Configuration
public class QuartzConfig {
    
    @Bean
    public SchedulerFactoryBean schedulerFactoryBean(DataSource dataSource) {
        SchedulerFactoryBean factory = new SchedulerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setQuartzProperties(quartzProperties());
        factory.setJobFactory(autowiringSpringBeanJobFactory());
        factory.setWaitForJobsToCompleteOnShutdown(true);
        factory.setOverwriteExistingJobs(true);
        
        // Add OpenTelemetry job listener
        factory.setGlobalJobListeners(openTelemetryJobListener());
        
        return factory;
    }
    
    @Bean
    public Properties quartzProperties() {
        Properties properties = new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName", "JobScheduler");
        properties.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        properties.setProperty("org.quartz.jobStore.class", 
            "org.quartz.impl.jdbcjobstore.JobStoreTX");
        properties.setProperty("org.quartz.jobStore.driverDelegateClass",
            "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
        properties.setProperty("org.quartz.jobStore.tablePrefix", "QRTZ_");
        properties.setProperty("org.quartz.jobStore.isClustered", "true");
        properties.setProperty("org.quartz.jobStore.clusterCheckinInterval", "20000");
        properties.setProperty("org.quartz.threadPool.threadCount", "10");
        return properties;
    }
    
    @Bean
    public OpenTelemetryJobListener openTelemetryJobListener() {
        return new OpenTelemetryJobListener();
    }
}
```

#### 3.4.3 OpenTelemetry Job Listener

```java
// job-scheduler/src/main/java/com/demo/scheduler/tracing/OpenTelemetryJobListener.java
public class OpenTelemetryJobListener implements JobListener {
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("job-scheduler");
    private final Map<String, Span> jobSpans = new ConcurrentHashMap<>();
    
    @Override
    public String getName() {
        return "OpenTelemetryJobListener";
    }
    
    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
        JobDetail jobDetail = context.getJobDetail();
        
        // Extract trace context if provided in job data
        String traceParent = jobDetail.getJobDataMap().getString("traceparent");
        Context parentContext = Context.current();
        
        if (traceParent != null) {
            parentContext = W3CTraceContextPropagator.getInstance()
                .extract(Context.current(), 
                    Collections.singletonMap("traceparent", traceParent),
                    TextMapGetter.defaultGetter());
        }
        
        // Create job execution span
        Span span = tracer.spanBuilder("job.execute")
            .setParent(parentContext)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("job.name", jobDetail.getKey().getName())
            .setAttribute("job.group", jobDetail.getKey().getGroup())
            .setAttribute("job.class", jobDetail.getJobClass().getName())
            .setAttribute("job.scheduled_fire_time", 
                context.getScheduledFireTime().getTime())
            .setAttribute("job.fire_time", context.getFireTime().getTime())
            .startSpan();
        
        // Store span for later
        jobSpans.put(context.getFireInstanceId(), span);
        
        // Make span current for the job execution
        span.makeCurrent();
    }
    
    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException exception) {
        Span span = jobSpans.remove(context.getFireInstanceId());
        
        if (span != null) {
            if (exception != null) {
                span.recordException(exception);
                span.setStatus(StatusCode.ERROR, exception.getMessage());
            } else {
                span.setStatus(StatusCode.OK);
            }
            
            // Add execution metrics
            span.setAttribute("job.execution_time_ms", context.getJobRunTime());
            span.setAttribute("job.next_fire_time", 
                context.getNextFireTime() != null ? 
                context.getNextFireTime().getTime() : 0);
            
            span.end();
        }
    }
    
    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        Span span = jobSpans.remove(context.getFireInstanceId());
        
        if (span != null) {
            span.setAttribute("job.vetoed", true);
            span.setStatus(StatusCode.CANCELLED, "Job execution vetoed");
            span.end();
        }
    }
}
```

#### 3.4.4 Sample Jobs

```java
// job-scheduler/src/main/java/com/demo/scheduler/jobs/LinkExpirationJob.java
@Component
@DisallowConcurrentExecution
public class LinkExpirationJob implements Job {
    private static final Logger logger = LoggerFactory.getLogger(LinkExpirationJob.class);
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("job-scheduler");
    
    @Autowired
    private LinkRepository linkRepository;
    
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Span span = Span.current(); // Get span from listener
        
        try {
            // Find expired links
            Span querySpan = tracer.spanBuilder("job.query_expired_links")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
            
            List<Link> expiredLinks;
            try (Scope scope = querySpan.makeCurrent()) {
                expiredLinks = linkRepository.findExpiredLinks(LocalDateTime.now());
                querySpan.setAttribute("expired.count", expiredLinks.size());
            } finally {
                querySpan.end();
            }
            
            // Process each expired link
            for (Link link : expiredLinks) {
                Span processSpan = tracer.spanBuilder("job.process_expired_link")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("link.short_code", link.getShortCode())
                    .setAttribute("link.created_at", link.getCreatedAt().toString())
                    .startSpan();
                
                try (Scope scope = processSpan.makeCurrent()) {
                    // Mark as expired
                    link.setStatus("EXPIRED");
                    linkRepository.save(link);
                    
                    // Send event to Kafka with trace context
                    ProducerRecord<String, Object> record = new ProducerRecord<>(
                        "link-events",
                        link.getShortCode(),
                        new LinkExpiredEvent(link.getShortCode(), link.getUserId())
                    );
                    
                    // Inject trace context into Kafka headers
                    W3CTraceContextPropagator.getInstance().inject(
                        Context.current(),
                        record.headers(),
                        (headers, key, value) -> 
                            headers.add(key, value.getBytes(StandardCharsets.UTF_8))
                    );
                    
                    kafkaTemplate.send(record);
                    
                } finally {
                    processSpan.end();
                }
            }
            
            span.setAttribute("job.links_expired", expiredLinks.size());
            logger.info("Expired {} links", expiredLinks.size());
            
        } catch (Exception e) {
            logger.error("Error executing link expiration job", e);
            throw new JobExecutionException(e);
        }
    }
}

// job-scheduler/src/main/java/com/demo/scheduler/jobs/AnalyticsAggregationJob.java
@Component
@DisallowConcurrentExecution
public class AnalyticsAggregationJob implements Job {
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("job-scheduler");
    
    @Autowired
    private ClickRepository clickRepository;
    
    @Autowired
    private TracedRedisTemplate<String, AnalyticsReport> redisTemplate;
    
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Span span = Span.current();
        
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusHours(1);
        
        span.setAttribute("analytics.start_time", startTime.toString());
        span.setAttribute("analytics.end_time", endTime.toString());
        
        // Aggregate click data
        Span aggregateSpan = tracer.spanBuilder("job.aggregate_clicks")
            .setSpanKind(SpanKind.CLIENT)
            .startSpan();
        
        AnalyticsReport report;
        try (Scope scope = aggregateSpan.makeCurrent()) {
            report = AnalyticsReport.builder()
                .period(startTime + " to " + endTime)
                .totalClicks(clickRepository.countByClickedAtBetween(startTime, endTime))
                .uniqueUsers(clickRepository.countDistinctUsersByClickedAtBetween(startTime, endTime))
                .topLinks(clickRepository.findTopLinksByClickedAtBetween(startTime, endTime, 10))
                .clicksByHour(clickRepository.groupByHour(startTime, endTime))
                .build();
            
            aggregateSpan.setAttribute("analytics.total_clicks", report.getTotalClicks());
            aggregateSpan.setAttribute("analytics.unique_users", report.getUniqueUsers());
        } finally {
            aggregateSpan.end();
        }
        
        // Store in Redis
        String cacheKey = "analytics:hourly:" + endTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        redisTemplate.set(cacheKey, report, 86400); // 24 hour TTL
        
        span.setAttribute("job.report_cached", true);
        span.setAttribute("job.cache_key", cacheKey);
    }
}
```

#### 3.4.5 Job Scheduling Configuration

```java
// job-scheduler/src/main/java/com/demo/scheduler/config/JobSchedulingConfig.java
@Configuration
public class JobSchedulingConfig {
    
    @Autowired
    private Scheduler scheduler;
    
    @PostConstruct
    public void scheduleJobs() throws SchedulerException {
        // Link Expiration Job - Every 30 minutes
        JobDetail linkExpirationJob = JobBuilder.newJob(LinkExpirationJob.class)
            .withIdentity("linkExpirationJob", "maintenance")
            .withDescription("Clean up expired short links")
            .storeDurably()
            .build();
        
        CronTrigger linkExpirationTrigger = TriggerBuilder.newTrigger()
            .withIdentity("linkExpirationTrigger", "maintenance")
            .withSchedule(CronScheduleBuilder.cronSchedule("0 */30 * * * ?"))
            .build();
        
        scheduler.scheduleJob(linkExpirationJob, linkExpirationTrigger);
        
        // Analytics Aggregation Job - Every hour
        JobDetail analyticsJob = JobBuilder.newJob(AnalyticsAggregationJob.class)
            .withIdentity("analyticsAggregationJob", "analytics")
            .withDescription("Aggregate hourly analytics")
            .storeDurably()
            .build();
        
        CronTrigger analyticsTrigger = TriggerBuilder.newTrigger()
            .withIdentity("analyticsTrigger", "analytics")
            .withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?"))
            .build();
        
        scheduler.scheduleJob(analyticsJob, analyticsTrigger);
        
        // Cache Warming Job - Every 15 minutes
        JobDetail cacheWarmingJob = JobBuilder.newJob(CacheWarmingJob.class)
            .withIdentity("cacheWarmingJob", "performance")
            .withDescription("Pre-populate cache with popular links")
            .storeDurably()
            .build();
        
        CronTrigger cacheWarmingTrigger = TriggerBuilder.newTrigger()
            .withIdentity("cacheWarmingTrigger", "performance")
            .withSchedule(CronScheduleBuilder.cronSchedule("0 */15 * * * ?"))
            .build();
        
        scheduler.scheduleJob(cacheWarmingJob, cacheWarmingTrigger);
    }
}
```

### 3.5 WebSocket Service (Java/Spring Boot)

#### 3.5.1 WebSocket Configuration

```java
// websocket-service/src/main/java/com/demo/websocket/config/WebSocketConfig.java
@Configuration
@EnableWebSocket
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOrigins("*")
            .addInterceptors(new TracingHandshakeInterceptor())
            .withSockJS();
    }
    
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
    
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new TracingChannelInterceptor());
    }
    
    @Bean
    public WebSocketTraceManager traceManager() {
        return new WebSocketTraceManager();
    }
}
```

#### 3.5.2 Trace Context Propagation

```java
// websocket-service/src/main/java/com/demo/websocket/tracing/TracingHandshakeInterceptor.java
public class TracingHandshakeInterceptor implements HandshakeInterceptor {
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("websocket-service");
    
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // Extract trace context from HTTP headers
        String traceParent = request.getHeaders().getFirst("traceparent");
        String traceState = request.getHeaders().getFirst("tracestate");
        
        Context parentContext = Context.current();
        if (traceParent != null) {
            Map<String, String> carrier = new HashMap<>();
            carrier.put("traceparent", traceParent);
            if (traceState != null) {
                carrier.put("tracestate", traceState);
            }
            
            parentContext = W3CTraceContextPropagator.getInstance()
                .extract(parentContext, carrier, TextMapGetter.defaultGetter());
        }
        
        // Create connection span
        Span connectionSpan = tracer.spanBuilder("websocket.connect")
            .setParent(parentContext)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute("websocket.endpoint", request.getURI().getPath())
            .setAttribute("websocket.protocol", "STOMP")
            .setAttribute("client.address", request.getRemoteAddress().toString())
            .startSpan();
        
        // Store in session attributes
        attributes.put("parentContext", parentContext);
        attributes.put("connectionSpan", connectionSpan);
        attributes.put("traceParent", traceParent);
        
        return true;
    }
    
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // Log any handshake errors
        if (exception != null) {
            Span span = (Span) ((WebSocketSession) wsHandler).getAttributes().get("connectionSpan");
            if (span != null) {
                span.recordException(exception);
                span.setStatus(StatusCode.ERROR);
            }
        }
    }
}

// websocket-service/src/main/java/com/demo/websocket/tracing/TracingChannelInterceptor.java
public class TracingChannelInterceptor implements ChannelInterceptor {
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("websocket-service");
    
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        
        // Get parent context from session
        Context parentContext = (Context) accessor.getSessionAttributes().get("parentContext");
        if (parentContext == null) {
            parentContext = Context.current();
        }
        
        // Create message span
        Span messageSpan = tracer.spanBuilder("websocket.message")
            .setParent(parentContext)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("websocket.command", accessor.getCommand().toString())
            .setAttribute("websocket.destination", accessor.getDestination())
            .setAttribute("websocket.message.id", accessor.getMessageId())
            .startSpan();
        
        // Store span in message headers for correlation
        accessor.setHeader("spanId", messageSpan.getSpanContext().getSpanId());
        accessor.setHeader("traceId", messageSpan.getSpanContext().getTraceId());
        
        // Make span current
        messageSpan.makeCurrent();
        
        return message;
    }
    
    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, 
                                   boolean sent, Exception ex) {
        // End the message span
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            if (ex != null) {
                currentSpan.recordException(ex);
                currentSpan.setStatus(StatusCode.ERROR);
            } else {
                currentSpan.setAttribute("websocket.message.sent", sent);
                currentSpan.setStatus(StatusCode.OK);
            }
            currentSpan.end();
        }
    }
}
```

#### 3.5.3 WebSocket Controllers

```java
// websocket-service/src/main/java/com/demo/websocket/controller/NotificationController.java
@Controller
public class NotificationController {
    private final SimpMessagingTemplate messagingTemplate;
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("websocket-service");
    
    @MessageMapping("/subscribe")
    @SendToUser("/queue/subscribed")
    public SubscriptionResponse subscribe(@Payload SubscriptionRequest request, 
                                         SimpMessageHeaderAccessor headerAccessor) {
        Span span = Span.current();
        span.setAttribute("subscription.type", request.getType());
        span.setAttribute("subscription.user", headerAccessor.getUser().getName());
        
        // Store subscription
        WebSocketSessionManager.addSubscription(
            headerAccessor.getSessionId(),
            request.getType(),
            headerAccessor.getUser().getName()
        );
        
        return new SubscriptionResponse("Subscribed to " + request.getType());
    }
    
    @EventListener
    public void handleLinkClickEvent(LinkClickedEvent event) {
        // Create span for event processing
        Span span = tracer.spanBuilder("websocket.broadcast")
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute("event.type", "link_clicked")
            .setAttribute("event.link", event.getShortCode())
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            // Extract trace context from event
            Context eventContext = W3CTraceContextPropagator.getInstance()
                .extract(Context.current(), event.getTraceContext(), 
                    TextMapGetter.defaultGetter());
            
            // Create notification
            ClickNotification notification = ClickNotification.builder()
                .shortCode(event.getShortCode())
                .clickedAt(event.getClickedAt())
                .userAgent(event.getUserAgent())
                .traceId(span.getSpanContext().getTraceId())
                .build();
            
            // Send to subscribed users
            Set<String> subscribers = WebSocketSessionManager
                .getSubscribers("link_clicks", event.getUserId());
            
            span.setAttribute("broadcast.subscribers", subscribers.size());
            
            for (String sessionId : subscribers) {
                messagingTemplate.convertAndSendToUser(
                    sessionId,
                    "/queue/notifications",
                    notification
                );
            }
        } finally {
            span.end();
        }
    }
}
```

### 3.6 Mock External Services

#### 3.6.1 Payment Gateway Mock

```java
// mock-services/payment-gateway/src/main/java/com/demo/mock/PaymentGatewayController.java
@RestController
@RequestMapping("/payment")
public class PaymentGatewayController {
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("payment-gateway");
    private final Random random = new Random();
    
    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> processPayment(@RequestBody PaymentRequest request) {
        Span span = Span.current();
        span.setAttribute("payment.amount", request.getAmount());
        span.setAttribute("payment.currency", request.getCurrency());
        span.setAttribute("payment.method", request.getMethod());
        
        // Simulate processing with variable latency
        int processingTime = 500 + random.nextInt(2000);
        span.setAttribute("payment.processing_time_ms", processingTime);
        
        try {
            Thread.sleep(processingTime);
            
            // Simulate 10% failure rate
            if (random.nextDouble() < 0.1) {
                throw new PaymentDeclinedException("Payment declined by issuer");
            }
            
            String transactionId = "txn_" + UUID.randomUUID();
            span.setAttribute("payment.transaction_id", transactionId);
            
            return ResponseEntity.ok(PaymentResponse.builder()
                .success(true)
                .transactionId(transactionId)
                .processingTimeMs(processingTime)
                .build());
            
        } catch (PaymentDeclinedException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            return ResponseEntity.badRequest()
                .body(PaymentResponse.builder()
                    .success(false)
                    .error(e.getMessage())
                    .build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            return ResponseEntity.status(500)
                .body(PaymentResponse.builder()
                    .success(false)
                    .error("Processing interrupted")
                    .build());
        }
    }
}
```

## 4. Data Flow Design

### 4.1 Trace Context Flow

```
1. Browser → NGINX
   - Browser generates or continues trace
   - Sends traceparent header
   - NGINX creates edge span

2. NGINX → Kong
   - NGINX forwards traceparent
   - Kong creates gateway span as child
   - Adds routing metadata

3. Kong → BFF/Services
   - Kong forwards enhanced context
   - Services auto-instrument via Java agent
   - Context includes baggage

4. Service → Redis
   - Service includes trace in cache operations
   - Redis client creates cache spans
   - Hit/miss recorded

5. Service → Kafka
   - Trace context in message headers
   - Consumer continues trace
   - Async boundary preserved

6. Service → Database
   - Trace ID in SQL comments
   - Query span with sanitized SQL
   - Execution time recorded

7. Job Scheduler
   - Creates new root spans for scheduled jobs
   - Can continue existing trace if triggered by event
   - Propagates context to downstream services

8. WebSocket
   - Initial connection includes traceparent
   - Each message can carry trace context
   - Broadcasts create child spans
```

### 4.2 Baggage Data Flow

```yaml
Baggage Items:
  user.id: "user123"
  tenant.id: "tenant456"
  session.id: "sess789"
  feature.flags: "feature1=true,feature2=false"
  api.version: "v2"
  client.type: "web"

Propagation:
  - HTTP: Baggage header
  - Kafka: Custom headers
  - WebSocket: STOMP headers
  - Jobs: JobDataMap
```

## 5. Deployment Architecture

### 5.1 Container Structure

```yaml
Container Dependencies:
  nginx:
    depends_on: [otel-collector]
    
  kong:
    depends_on: [kong-database, otel-collector]
    
  bff:
    depends_on: [redis-master, kong]
    
  services:
    depends_on: [postgres, kafka, redis-master]
    
  job-scheduler:
    depends_on: [postgres, kafka, redis-master]
    
  websocket-service:
    depends_on: [kafka, redis-master]
    
  otel-collector:
    depends_on: [jaeger]
```

### 5.2 Network Architecture

```yaml
Networks:
  edge-network:
    - nginx
    - kong
    
  application-network:
    - kong
    - bff
    - services
    - job-scheduler
    - websocket-service
    
  data-network:
    - services
    - postgres
    - redis
    - kafka
    
  observability-network:
    - all-services
    - otel-collector
    - jaeger
```

## 6. Configuration Management

### 6.1 Environment Variables

```yaml
Global Settings:
  OTEL_SERVICE_NAME: ${SERVICE_NAME}
  OTEL_SERVICE_VERSION: ${SERVICE_VERSION}
  OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
  OTEL_EXPORTER_OTLP_PROTOCOL: grpc
  OTEL_TRACES_EXPORTER: otlp
  OTEL_METRICS_EXPORTER: none
  OTEL_LOGS_EXPORTER: none
  OTEL_PROPAGATORS: tracecontext,baggage

Java Services:
  JAVA_TOOL_OPTIONS: -javaagent:/opentelemetry-javaagent.jar
  OTEL_INSTRUMENTATION_JDBC_ENABLED: true
  OTEL_INSTRUMENTATION_KAFKA_ENABLED: true
  OTEL_INSTRUMENTATION_REDIS_ENABLED: true
  OTEL_INSTRUMENTATION_SPRING_ENABLED: true

Sampling:
  OTEL_TRACES_SAMPLER: parentbased_traceidratio
  OTEL_TRACES_SAMPLER_ARG: 0.1  # 10% sampling
```

## 7. Security Considerations

### 7.1 Trace Data Security

```yaml
Sensitive Data Protection:
  - SQL queries sanitized (? placeholders)
  - Passwords never in traces
  - PII data excluded from attributes
  - API keys redacted

Access Control:
  - Jaeger UI behind authentication
  - Trace data retention: 7 days
  - No production data in traces
  
Network Security:
  - Internal services on private network
  - TLS for external communications
  - mTLS between services (future)
```

## 8. Performance Optimization

### 8.1 Sampling Strategy

```java
// Custom sampler for intelligent sampling
public class SmartSampler implements Sampler {
    private final Sampler alwaysOn = Sampler.alwaysOn();
    private final Sampler tenPercent = Sampler.traceIdRatioBased(0.1);
    
    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, 
                                       String name, SpanKind spanKind, 
                                       Attributes attributes, List<LinkData> parentLinks) {
        // Always sample errors
        if (attributes.get(AttributeKey.stringKey("error")) != null) {
            return alwaysOn.shouldSample(parentContext, traceId, name, 
                                        spanKind, attributes, parentLinks);
        }
        
        // Always sample slow requests
        Long duration = attributes.get(AttributeKey.longKey("duration_ms"));
        if (duration != null && duration > 1000) {
            return alwaysOn.shouldSample(parentContext, traceId, name, 
                                        spanKind, attributes, parentLinks);
        }
        
        // Sample 10% of normal traffic
        return tenPercent.shouldSample(parentContext, traceId, name, 
                                      spanKind, attributes, parentLinks);
    }
}
```

## 9. Monitoring & Alerting

### 9.1 Key Metrics

```yaml
Service Metrics:
  - Trace completion rate
  - Span creation rate
  - Context propagation success
  - Sampling effectiveness

Performance Metrics:
  - Instrumentation overhead
  - Collector processing time
  - Storage growth rate
  - Query performance

Business Metrics:
  - User journey completion
  - Error trace ratio
  - Slow transaction percentage
  - Service dependency health
```

## 10. Testing Strategy

### 10.1 Trace Validation Tests

```java
@Test
public void testEndToEndTracePropagation() {
    // Create initial trace
    String traceId = createTestTrace();
    
    // Make request through full stack
    Response response = makeRequestWithTrace(traceId);
    
    // Verify trace appears in all services
    assertTraceInService("nginx", traceId);
    assertTraceInService("kong", traceId);
    assertTraceInService("bff", traceId);
    assertTraceInService("url-api", traceId);
    
    // Verify async traces
    assertTraceInKafka(traceId);
    assertTraceInJob(traceId);
}
```

---

**Document Version**: 1.0  
**Last Updated**: 2024-12-12  
**Status**: Draft for Review  
**Owner**: Platform Team