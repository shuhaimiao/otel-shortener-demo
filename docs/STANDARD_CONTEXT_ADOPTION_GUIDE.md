# Standard Context Adoption Guide: Enterprise Implementation Patterns

## Table of Contents
1. [Introduction](#introduction)
2. [Architectural Decision: Context Establishment Strategy](#architectural-decision-context-establishment-strategy)
3. [Frontend Context Initialization](#frontend-context-initialization)
4. [BFF/API Gateway Context Establishment](#bffapi-gateway-context-establishment)
5. [Java Backend Services](#java-backend-services)
6. [Python Services](#python-services)
7. [Async Messaging (Kafka)](#async-messaging-kafka)
8. [Database & CDC Context](#database--cdc-context)
9. [Scheduled Jobs & System Context](#scheduled-jobs--system-context)
10. [Common Patterns](#common-patterns)
11. [Testing & Validation](#testing--validation)
12. [Troubleshooting](#troubleshooting)
13. [Adoption Strategy](#adoption-strategy)

## Introduction

This guide provides production-ready patterns for implementing standard business context propagation across distributed systems. While OpenTelemetry handles trace context, **standard context carries business-critical information** like user identity, tenant isolation, request correlation, and transaction types that are essential for:

- **Multi-tenancy isolation** - Ensuring data separation
- **Security auditing** - Tracking who did what
- **Business correlation** - Linking technical operations to business transactions
- **Debugging** - Understanding the business context of errors
- **Compliance** - Meeting regulatory requirements for audit trails

### Reference Implementation

The patterns in this guide are extracted from the **[otel-shortener-demo](https://github.com/smiao-icims/otel-shortener-demo)**, which demonstrates complete context propagation across:
- Frontend → BFF → Backend Services → Kafka → Analytics
- Synchronous HTTP with standard headers (X-User-ID, X-Tenant-ID, etc.)
- Asynchronous boundaries via Kafka with context preservation
- CDC (Change Data Capture) with Debezium maintaining context
- Scheduled jobs with system context

For detailed implementation, see:
- **[Context Propagation Solution](./CONTEXT_PROPAGATION_SOLUTION.md)** - Complete technical documentation
- **[Architecture Documentation](./ARCHITECTURE.md)** - System design and service descriptions
- **[Exploratory Test Guide](./EXPLORATORY_TEST_GUIDE.md)** - Verification procedures

### Core Principles

1. **Establish Once, Propagate Everywhere**: Context should be established at the edge and flow automatically
2. **Immutable Context**: Once established, context should not be modified by downstream services
3. **Zero Trust Between Services**: Services trust the context, not each other
4. **Fail Secure**: Missing context should fail the request, not default to unsafe values
5. **Performance First**: Use caching, avoid repeated validation, minimize overhead

### Context vs Trace: Why Both?

| Aspect | Trace Context (W3C) | Standard Context |
|--------|---------------------|------------------|
| **Purpose** | Technical correlation | Business correlation |
| **Content** | TraceID, SpanID, Flags | UserID, TenantID, RequestID, etc. |
| **Lifetime** | Per request trace | Can span multiple traces |
| **Security** | Public (can be exposed) | Sensitive (must be protected) |
| **Standard** | W3C Trace Context | Organization-specific |
| **Usage** | Observability tools | Application logic, authorization |

## Architectural Decision: Context Establishment Strategy

### The Critical Choice: Where to Establish Context?

This decision fundamentally affects your security model, performance, and system complexity.

#### Option 1: Edge Establishment (API Gateway/Load Balancer)

**Establish context at the entry point before requests reach services.**

**Advantages:**
- **Single Point of Control**: One place to manage authentication/authorization
- **Service Simplicity**: Backend services receive pre-validated context
- **Consistent Security**: Uniform context across all services
- **Performance**: JWT validation happens once, not per service
- **Audit Trail**: Complete visibility from entry point

**Disadvantages:**
- **Edge Complexity**: Gateway becomes critical security component
- **Vendor Lock-in**: Tied to gateway capabilities
- **Development Friction**: Local development needs gateway
- **Limited Flexibility**: Hard to have service-specific context

**Best For:**
- Enterprises with dedicated API gateway teams
- Microservices with 10+ services
- Strict security requirements
- When using commercial gateways (Kong, Apigee, AWS API Gateway)

#### Option 2: BFF Establishment (Recommended for Most)

**Establish context in Backend-for-Frontend layer.**

**Advantages:**
- **Full Control**: Complete ownership of context logic
- **Easy Development**: Works seamlessly in local environment
- **Flexible**: Different BFFs can have different context strategies
- **Cache Integration**: Natural place for Redis/Memcached
- **Framework Agnostic**: Works with any edge infrastructure

**Disadvantages:**
- **Multiple BFFs**: Need consistency across BFFs
- **Trust Boundary**: BFF becomes critical security component
- **Network Hop**: Additional latency if BFF is separate

**Best For:**
- Teams owning their full stack
- Applications with 1-3 BFFs
- Rapid development cycles
- When you need custom context logic

#### Option 3: Service-Level Establishment

**Each service validates and establishes its own context.**

**Advantages:**
- **True Zero Trust**: No service trusts another
- **Independent Services**: Can deploy/test in isolation
- **No Single Point of Failure**: Resilient to gateway/BFF issues

**Disadvantages:**
- **Performance Overhead**: JWT validation per service per request
- **Complex Configuration**: Each service needs auth config
- **Inconsistent Context**: Risk of services interpreting differently
- **Difficult Testing**: Need valid tokens for every service test

**Acceptable For:**
- High-security environments (banking, healthcare)
- Services exposed to external partners
- During migration from monolith
- When services are owned by different teams with no trust

### Our Recommendation: BFF Pattern with Caching

Based on real-world experience, **we recommend the BFF pattern** for most organizations:

```
[Browser] → [CDN/LB] → [BFF] → [Services]
                          ↓
                    [Redis Cache]
```

**Why BFF?**
1. **Balanced Complexity**: Simpler than gateway, more secure than per-service
2. **Developer Friendly**: Works locally without infrastructure
3. **Performance**: Cache context after first validation
4. **Flexibility**: Easy to customize per application needs

### Implementation Checklist

Regardless of where you establish context, ensure:

- [ ] **JWT Validation**: Verify signature and expiration
- [ ] **Claims Extraction**: Parse user, tenant, roles, permissions
- [ ] **Context Enrichment**: Add request ID, timestamp, service name
- [ ] **Caching Strategy**: Cache validated context (5-15 minutes TTL)
- [ ] **Header Standardization**: Use consistent X-* headers
- [ ] **MDC/Logging Integration**: Context available in all logs
- [ ] **Error Handling**: Clear errors for missing/invalid context
- [ ] **Monitoring**: Track context validation failures

## Frontend Context Initialization

### Browser/React Implementation

```typescript
// context/StandardContext.ts
export interface StandardContext {
  userId?: string;
  tenantId?: string;
  sessionId: string;
  deviceId: string;
  correlationId?: string;
}

class ContextManager {
  private context: StandardContext;
  
  constructor() {
    this.context = this.initializeContext();
  }
  
  private initializeContext(): StandardContext {
    // Get or create device ID (persists across sessions)
    let deviceId = localStorage.getItem('deviceId');
    if (!deviceId) {
      deviceId = `dev-${this.generateId()}`;
      localStorage.setItem('deviceId', deviceId);
    }
    
    // Get or create session ID (per browser session)
    let sessionId = sessionStorage.getItem('sessionId');
    if (!sessionId) {
      sessionId = `ses-${this.generateId()}`;
      sessionStorage.setItem('sessionId', sessionId);
    }
    
    return {
      deviceId,
      sessionId,
      userId: undefined,    // Set after authentication
      tenantId: undefined,  // Set after authentication
      correlationId: undefined
    };
  }
  
  setAuthContext(token: string) {
    // Parse JWT to extract claims (use proper library in production)
    const claims = this.parseJWT(token);
    this.context.userId = claims.sub || claims.user_id;
    this.context.tenantId = claims.tenant_id || claims.org_id;
    
    // Persist for session
    sessionStorage.setItem('userId', this.context.userId);
    sessionStorage.setItem('tenantId', this.context.tenantId);
  }
  
  createRequestContext(): StandardContext {
    return {
      ...this.context,
      correlationId: `req-${this.generateId()}`
    };
  }
  
  private generateId(): string {
    return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }
  
  private parseJWT(token: string): any {
    try {
      const base64Url = token.split('.')[1];
      const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(window.atob(base64));
    } catch {
      return {};
    }
  }
}

export const contextManager = new ContextManager();
```

### Axios Interceptor for Context Headers

```typescript
// api/client.ts
import axios, { AxiosInstance } from 'axios';
import { contextManager } from '../context/StandardContext';

class ApiClient {
  private client: AxiosInstance;
  
  constructor(baseURL: string) {
    this.client = axios.create({
      baseURL,
      timeout: 30000,
    });
    
    this.setupInterceptors();
  }
  
  private setupInterceptors() {
    // Request interceptor to add context headers
    this.client.interceptors.request.use(
      (config) => {
        const context = contextManager.createRequestContext();
        
        // Add standard context headers
        config.headers['X-Correlation-ID'] = context.correlationId;
        config.headers['X-Session-ID'] = context.sessionId;
        config.headers['X-Device-ID'] = context.deviceId;
        
        if (context.userId) {
          config.headers['X-User-ID'] = context.userId;
        }
        
        if (context.tenantId) {
          config.headers['X-Tenant-ID'] = context.tenantId;
        }
        
        // Add request timestamp for latency tracking
        config.headers['X-Request-Timestamp'] = new Date().toISOString();
        
        // Add client version for API compatibility
        config.headers['X-Client-Version'] = process.env.REACT_APP_VERSION || '1.0.0';
        
        console.log(`[${context.correlationId}] ${config.method?.toUpperCase()} ${config.url}`, {
          userId: context.userId,
          tenantId: context.tenantId
        });
        
        return config;
      },
      (error) => {
        console.error('Request interceptor error:', error);
        return Promise.reject(error);
      }
    );
    
    // Response interceptor for context correlation
    this.client.interceptors.response.use(
      (response) => {
        const correlationId = response.config.headers['X-Correlation-ID'];
        const serverTime = response.headers['x-server-timestamp'];
        
        if (serverTime) {
          const latency = Date.now() - new Date(response.config.headers['X-Request-Timestamp']).getTime();
          console.log(`[${correlationId}] Response received in ${latency}ms`);
        }
        
        return response;
      },
      (error) => {
        const correlationId = error.config?.headers?.['X-Correlation-ID'];
        console.error(`[${correlationId}] Request failed:`, error.message);
        
        // Add correlation ID to error for debugging
        if (error.response) {
          error.response.correlationId = correlationId;
        }
        
        return Promise.reject(error);
      }
    );
  }
  
  get api() {
    return this.client;
  }
}

export const apiClient = new ApiClient(process.env.REACT_APP_API_URL || 'http://localhost:3001');
```

### React Hook for Context

```typescript
// hooks/useStandardContext.ts
import { useEffect, useState } from 'react';
import { contextManager } from '../context/StandardContext';

export function useStandardContext() {
  const [context, setContext] = useState(contextManager.context);
  
  useEffect(() => {
    // Subscribe to context changes if using event-based updates
    const updateContext = () => setContext({ ...contextManager.context });
    
    // Listen for auth changes
    window.addEventListener('auth-changed', updateContext);
    
    return () => {
      window.removeEventListener('auth-changed', updateContext);
    };
  }, []);
  
  return {
    context,
    setAuthContext: (token: string) => {
      contextManager.setAuthContext(token);
      setContext({ ...contextManager.context });
      window.dispatchEvent(new Event('auth-changed'));
    },
    clearContext: () => {
      contextManager.clearContext();
      setContext({ ...contextManager.context });
      window.dispatchEvent(new Event('auth-changed'));
    }
  };
}

// Usage in component
function MyComponent() {
  const { context } = useStandardContext();
  
  return (
    <div>
      <p>User: {context.userId || 'Anonymous'}</p>
      <p>Tenant: {context.tenantId || 'None'}</p>
      <p>Session: {context.sessionId}</p>
    </div>
  );
}
```

## BFF/API Gateway Context Establishment

### Express Middleware Implementation

```javascript
// middleware/context.js
const jwt = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const redis = require('../config/redis');

/**
 * Context establishment middleware for BFF
 * Extracts, validates, and caches context from JWT
 */
class ContextMiddleware {
  constructor(options = {}) {
    this.options = {
      cacheTTL: options.cacheTTL || 300, // 5 minutes default
      requireAuth: options.requireAuth !== false,
      systemContext: options.systemContext || {},
      jwtSecret: options.jwtSecret || process.env.JWT_SECRET,
      jwtIssuer: options.jwtIssuer || process.env.JWT_ISSUER,
    };
  }
  
  async establish(req, res, next) {
    try {
      // Generate request ID if not present
      req.id = req.headers['x-correlation-id'] || uuidv4();
      
      // Extract bearer token
      const token = this.extractToken(req);
      
      if (!token && this.options.requireAuth) {
        return res.status(401).json({
          error: 'Authentication required',
          correlationId: req.id
        });
      }
      
      // Establish context
      let context;
      if (token) {
        // Check cache first
        const cacheKey = `context:${this.hashToken(token)}`;
        const cached = await redis.get(cacheKey);
        
        if (cached) {
          context = JSON.parse(cached);
          context.cached = true;
        } else {
          // Validate and extract from JWT
          context = await this.extractFromJWT(token);
          
          // Cache the context
          await redis.setex(
            cacheKey,
            this.options.cacheTTL,
            JSON.stringify(context)
          );
        }
      } else {
        // Anonymous context
        context = this.createAnonymousContext(req);
      }
      
      // Enrich context with request information
      context.requestId = req.id;
      context.timestamp = new Date().toISOString();
      context.serviceName = 'bff';
      context.clientIp = this.getClientIp(req);
      context.userAgent = req.headers['user-agent'];
      context.sessionId = req.headers['x-session-id'];
      context.deviceId = req.headers['x-device-id'];
      
      // Derive transaction type from route
      context.transactionType = this.deriveTransactionType(req);
      
      // Attach to request
      req.context = context;
      
      // Set response headers for correlation
      res.set('X-Correlation-ID', req.id);
      res.set('X-Server-Timestamp', context.timestamp);
      
      // Log context establishment
      console.log(`[${req.id}] Context established`, {
        userId: context.userId,
        tenantId: context.tenantId,
        cached: context.cached || false,
        transactionType: context.transactionType
      });
      
      next();
    } catch (error) {
      console.error(`[${req.id}] Context establishment failed:`, error);
      
      if (this.options.requireAuth) {
        return res.status(401).json({
          error: 'Invalid authentication',
          correlationId: req.id
        });
      }
      
      // Fall back to anonymous context
      req.context = this.createAnonymousContext(req);
      next();
    }
  }
  
  extractToken(req) {
    const authHeader = req.headers.authorization;
    if (authHeader && authHeader.startsWith('Bearer ')) {
      return authHeader.substring(7);
    }
    return null;
  }
  
  async extractFromJWT(token) {
    const decoded = jwt.verify(token, this.options.jwtSecret, {
      issuer: this.options.jwtIssuer,
      algorithms: ['RS256', 'HS256']
    });
    
    return {
      userId: decoded.sub || decoded.user_id,
      tenantId: decoded.tenant_id || decoded.org_id,
      email: decoded.email,
      groups: decoded.groups || [],
      permissions: decoded.permissions || [],
      scopes: decoded.scope ? decoded.scope.split(' ') : [],
      tokenExp: decoded.exp,
      tokenIat: decoded.iat,
    };
  }
  
  createAnonymousContext(req) {
    return {
      userId: 'anonymous',
      tenantId: 'public',
      email: null,
      groups: ['anonymous'],
      permissions: [],
      scopes: ['read:public'],
    };
  }
  
  deriveTransactionType(req) {
    const method = req.method;
    const path = req.path;
    
    // Define transaction type mappings
    const mappings = [
      { pattern: /^\/api\/links$/, method: 'POST', type: 'create-link' },
      { pattern: /^\/api\/links$/, method: 'GET', type: 'list-links' },
      { pattern: /^\/api\/links\/[\w-]+$/, method: 'GET', type: 'get-link' },
      { pattern: /^\/api\/links\/[\w-]+$/, method: 'DELETE', type: 'delete-link' },
      { pattern: /^\/api\/analytics/, method: 'GET', type: 'view-analytics' },
      { pattern: /^\/api\/auth\/login$/, method: 'POST', type: 'user-login' },
      { pattern: /^\/api\/auth\/logout$/, method: 'POST', type: 'user-logout' },
    ];
    
    for (const mapping of mappings) {
      if (mapping.pattern.test(path) && 
          (!mapping.method || mapping.method === method)) {
        return mapping.type;
      }
    }
    
    return `${method.toLowerCase()}-${path.split('/')[2] || 'unknown'}`;
  }
  
  getClientIp(req) {
    return req.headers['x-forwarded-for']?.split(',')[0] || 
           req.headers['x-real-ip'] || 
           req.connection.remoteAddress;
  }
  
  hashToken(token) {
    // Simple hash for cache key (use crypto in production)
    const crypto = require('crypto');
    return crypto.createHash('sha256').update(token).digest('hex');
  }
}

// Export middleware factory
module.exports = {
  contextMiddleware: (options) => {
    const middleware = new ContextMiddleware(options);
    return middleware.establish.bind(middleware);
  }
};
```

### Context Propagation to Downstream Services

```javascript
// middleware/propagate-context.js

/**
 * Middleware to propagate context to downstream service calls
 */
function propagateContext(req, res, next) {
  // Override http methods to automatically add context headers
  const originalRequest = req.app.locals.httpClient || require('axios');
  
  req.httpClient = {
    async get(url, config = {}) {
      return originalRequest.get(url, addContextHeaders(req, config));
    },
    
    async post(url, data, config = {}) {
      return originalRequest.post(url, data, addContextHeaders(req, config));
    },
    
    async put(url, data, config = {}) {
      return originalRequest.put(url, data, addContextHeaders(req, config));
    },
    
    async delete(url, config = {}) {
      return originalRequest.delete(url, addContextHeaders(req, config));
    },
    
    async patch(url, data, config = {}) {
      return originalRequest.patch(url, data, addContextHeaders(req, config));
    }
  };
  
  next();
}

function addContextHeaders(req, config) {
  const context = req.context || {};
  
  config.headers = {
    ...config.headers,
    // Identity context
    'X-User-ID': context.userId,
    'X-Tenant-ID': context.tenantId,
    'X-User-Email': context.email,
    'X-User-Groups': Array.isArray(context.groups) ? context.groups.join(',') : '',
    
    // Request context
    'X-Request-ID': context.requestId,
    'X-Correlation-ID': req.id,
    'X-Session-ID': context.sessionId,
    'X-Device-ID': context.deviceId,
    
    // Transaction context
    'X-Service-Name': context.serviceName,
    'X-Transaction-Type': context.transactionType,
    'X-Client-IP': context.clientIp,
    
    // Timestamp for distributed tracing
    'X-Request-Timestamp': context.timestamp,
    
    // Keep original authorization for service mesh scenarios
    'Authorization': req.headers.authorization
  };
  
  // Remove undefined headers
  Object.keys(config.headers).forEach(key => {
    if (config.headers[key] === undefined || config.headers[key] === null) {
      delete config.headers[key];
    }
  });
  
  return config;
}

module.exports = { propagateContext, addContextHeaders };
```

## Java Backend Services

### MDC Context Filter

```java
// MdcContextFilter.java
package com.example.common.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Filter to extract standard context headers and populate MDC for logging
 */
@Component
@Order(1)
public class MdcContextFilter implements Filter {
    
    private static final Logger logger = LoggerFactory.getLogger(MdcContextFilter.class);
    
    // Standard context headers
    private static final String HEADER_USER_ID = "X-User-ID";
    private static final String HEADER_TENANT_ID = "X-Tenant-ID";
    private static final String HEADER_REQUEST_ID = "X-Request-ID";
    private static final String HEADER_CORRELATION_ID = "X-Correlation-ID";
    private static final String HEADER_SESSION_ID = "X-Session-ID";
    private static final String HEADER_DEVICE_ID = "X-Device-ID";
    private static final String HEADER_USER_EMAIL = "X-User-Email";
    private static final String HEADER_USER_GROUPS = "X-User-Groups";
    private static final String HEADER_SERVICE_NAME = "X-Service-Name";
    private static final String HEADER_TRANSACTION_TYPE = "X-Transaction-Type";
    private static final String HEADER_CLIENT_IP = "X-Client-IP";
    
    // MDC keys
    private static final String MDC_USER_ID = "userId";
    private static final String MDC_TENANT_ID = "tenantId";
    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_SESSION_ID = "sessionId";
    private static final String MDC_DEVICE_ID = "deviceId";
    private static final String MDC_USER_EMAIL = "userEmail";
    private static final String MDC_USER_GROUPS = "userGroups";
    private static final String MDC_SERVICE = "service";
    private static final String MDC_TRANSACTION_TYPE = "transactionType";
    private static final String MDC_CLIENT_IP = "clientIp";
    private static final String MDC_ORIGIN_SERVICE = "originService";
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        try {
            // Extract and set context in MDC
            extractContextToMDC(httpRequest);
            
            // Add response headers for correlation
            httpResponse.setHeader(HEADER_CORRELATION_ID, MDC.get(MDC_CORRELATION_ID));
            httpResponse.setHeader("X-Server-Timestamp", String.valueOf(System.currentTimeMillis()));
            
            // Log the request with context
            logger.info("Processing request: {} {}", 
                httpRequest.getMethod(), 
                httpRequest.getRequestURI());
            
            // Continue chain
            chain.doFilter(request, response);
            
            // Log response
            logger.info("Request completed with status: {}", httpResponse.getStatus());
            
        } finally {
            // Clear MDC to prevent context leakage
            MDC.clear();
        }
    }
    
    private void extractContextToMDC(HttpServletRequest request) {
        // User identity
        setMDCValue(MDC_USER_ID, request.getHeader(HEADER_USER_ID), "anonymous");
        setMDCValue(MDC_TENANT_ID, request.getHeader(HEADER_TENANT_ID), "default");
        setMDCValue(MDC_USER_EMAIL, request.getHeader(HEADER_USER_EMAIL));
        setMDCValue(MDC_USER_GROUPS, request.getHeader(HEADER_USER_GROUPS));
        
        // Request correlation
        String correlationId = request.getHeader(HEADER_CORRELATION_ID);
        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_CORRELATION_ID, correlationId);
        
        setMDCValue(MDC_REQUEST_ID, request.getHeader(HEADER_REQUEST_ID), correlationId);
        setMDCValue(MDC_SESSION_ID, request.getHeader(HEADER_SESSION_ID));
        setMDCValue(MDC_DEVICE_ID, request.getHeader(HEADER_DEVICE_ID));
        
        // Service context
        MDC.put(MDC_SERVICE, getServiceName());
        setMDCValue(MDC_ORIGIN_SERVICE, request.getHeader(HEADER_SERVICE_NAME));
        setMDCValue(MDC_TRANSACTION_TYPE, request.getHeader(HEADER_TRANSACTION_TYPE));
        
        // Client information
        String clientIp = request.getHeader(HEADER_CLIENT_IP);
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = request.getRemoteAddr();
        }
        MDC.put(MDC_CLIENT_IP, clientIp);
        
        // Add request details
        MDC.put("method", request.getMethod());
        MDC.put("path", request.getRequestURI());
    }
    
    private void setMDCValue(String key, String value) {
        if (value != null && !value.isEmpty()) {
            MDC.put(key, value);
        }
    }
    
    private void setMDCValue(String key, String value, String defaultValue) {
        MDC.put(key, value != null && !value.isEmpty() ? value : defaultValue);
    }
    
    private String getServiceName() {
        // Get from environment or application properties
        String serviceName = System.getenv("SERVICE_NAME");
        if (serviceName == null || serviceName.isEmpty()) {
            serviceName = System.getProperty("spring.application.name", "unknown-service");
        }
        return serviceName;
    }
}
```

### Context Holder for Service Layer

```java
// StandardContext.java
package com.example.common.context;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable context object containing all standard context fields
 */
public final class StandardContext implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // Identity
    private final String userId;
    private final String tenantId;
    private final String userEmail;
    private final List<String> userGroups;
    private final List<String> permissions;
    
    // Request
    private final String requestId;
    private final String correlationId;
    private final String sessionId;
    private final String deviceId;
    
    // Transaction
    private final String serviceName;
    private final String originService;
    private final String transactionType;
    
    // Client
    private final String clientIp;
    private final String userAgent;
    
    // Timing
    private final Instant timestamp;
    
    // Additional attributes
    private final Map<String, Object> attributes;
    
    private StandardContext(Builder builder) {
        this.userId = builder.userId;
        this.tenantId = builder.tenantId;
        this.userEmail = builder.userEmail;
        this.userGroups = builder.userGroups;
        this.permissions = builder.permissions;
        this.requestId = builder.requestId;
        this.correlationId = builder.correlationId;
        this.sessionId = builder.sessionId;
        this.deviceId = builder.deviceId;
        this.serviceName = builder.serviceName;
        this.originService = builder.originService;
        this.transactionType = builder.transactionType;
        this.clientIp = builder.clientIp;
        this.userAgent = builder.userAgent;
        this.timestamp = builder.timestamp;
        this.attributes = new ConcurrentHashMap<>(builder.attributes);
    }
    
    // Getters (no setters - immutable)
    public String getUserId() { return userId; }
    public String getTenantId() { return tenantId; }
    public String getUserEmail() { return userEmail; }
    public List<String> getUserGroups() { return userGroups; }
    public List<String> getPermissions() { return permissions; }
    public String getRequestId() { return requestId; }
    public String getCorrelationId() { return correlationId; }
    public String getSessionId() { return sessionId; }
    public String getDeviceId() { return deviceId; }
    public String getServiceName() { return serviceName; }
    public String getOriginService() { return originService; }
    public String getTransactionType() { return transactionType; }
    public String getClientIp() { return clientIp; }
    public String getUserAgent() { return userAgent; }
    public Instant getTimestamp() { return timestamp; }
    public Map<String, Object> getAttributes() { return attributes; }
    
    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String userId = "anonymous";
        private String tenantId = "default";
        private String userEmail;
        private List<String> userGroups = List.of();
        private List<String> permissions = List.of();
        private String requestId;
        private String correlationId;
        private String sessionId;
        private String deviceId;
        private String serviceName;
        private String originService;
        private String transactionType;
        private String clientIp;
        private String userAgent;
        private Instant timestamp = Instant.now();
        private Map<String, Object> attributes = new ConcurrentHashMap<>();
        
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }
        
        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }
        
        public Builder userEmail(String userEmail) {
            this.userEmail = userEmail;
            return this;
        }
        
        public Builder userGroups(List<String> userGroups) {
            this.userGroups = userGroups != null ? List.copyOf(userGroups) : List.of();
            return this;
        }
        
        public Builder permissions(List<String> permissions) {
            this.permissions = permissions != null ? List.copyOf(permissions) : List.of();
            return this;
        }
        
        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }
        
        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }
        
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }
        
        public Builder deviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }
        
        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }
        
        public Builder originService(String originService) {
            this.originService = originService;
            return this;
        }
        
        public Builder transactionType(String transactionType) {
            this.transactionType = transactionType;
            return this;
        }
        
        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }
        
        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }
        
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder attribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }
        
        public Builder attributes(Map<String, Object> attributes) {
            this.attributes.putAll(attributes);
            return this;
        }
        
        public StandardContext build() {
            return new StandardContext(this);
        }
    }
    
    @Override
    public String toString() {
        return String.format(
            "StandardContext[userId=%s, tenantId=%s, correlationId=%s, transactionType=%s]",
            userId, tenantId, correlationId, transactionType
        );
    }
}
```

### Context Holder for Thread-Local Access

```java
// ContextHolder.java
package com.example.common.context;

import org.slf4j.MDC;

/**
 * Thread-local holder for standard context
 */
public final class ContextHolder {
    
    private static final ThreadLocal<StandardContext> contextHolder = new ThreadLocal<>();
    
    private ContextHolder() {
        // Prevent instantiation
    }
    
    /**
     * Set context for current thread
     */
    public static void setContext(StandardContext context) {
        contextHolder.set(context);
        
        // Also update MDC for logging
        if (context != null) {
            MDC.put("userId", context.getUserId());
            MDC.put("tenantId", context.getTenantId());
            MDC.put("correlationId", context.getCorrelationId());
            MDC.put("transactionType", context.getTransactionType());
        }
    }
    
    /**
     * Get context for current thread
     */
    public static StandardContext getContext() {
        return contextHolder.get();
    }
    
    /**
     * Get context or create default
     */
    public static StandardContext getContextOrDefault() {
        StandardContext context = contextHolder.get();
        if (context == null) {
            context = createDefaultContext();
            setContext(context);
        }
        return context;
    }
    
    /**
     * Clear context for current thread
     */
    public static void clearContext() {
        contextHolder.remove();
        MDC.clear();
    }
    
    /**
     * Execute with context
     */
    public static <T> T executeWithContext(StandardContext context, ContextualSupplier<T> supplier) {
        StandardContext previousContext = getContext();
        try {
            setContext(context);
            return supplier.get();
        } finally {
            if (previousContext != null) {
                setContext(previousContext);
            } else {
                clearContext();
            }
        }
    }
    
    /**
     * Execute async with context
     */
    public static Runnable wrapWithContext(Runnable task) {
        StandardContext context = getContext();
        return () -> executeWithContext(context, () -> {
            task.run();
            return null;
        });
    }
    
    private static StandardContext createDefaultContext() {
        return StandardContext.builder()
            .userId("system")
            .tenantId("default")
            .serviceName(System.getProperty("spring.application.name", "unknown"))
            .build();
    }
    
    @FunctionalInterface
    public interface ContextualSupplier<T> {
        T get();
    }
}
```

## Python Services

### Context Extraction Decorator

```python
# context/standard_context.py
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any
from datetime import datetime
import uuid
import logging
from functools import wraps
from contextvars import ContextVar

logger = logging.getLogger(__name__)

# Context variable for async-safe access
current_context: ContextVar[Optional['StandardContext']] = ContextVar('current_context', default=None)

@dataclass(frozen=True)
class StandardContext:
    """Immutable standard context for Python services"""
    
    # Identity
    user_id: str = "anonymous"
    tenant_id: str = "default"
    user_email: Optional[str] = None
    user_groups: List[str] = field(default_factory=list)
    permissions: List[str] = field(default_factory=list)
    
    # Request
    request_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    correlation_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    session_id: Optional[str] = None
    device_id: Optional[str] = None
    
    # Transaction
    service_name: str = "python-service"
    origin_service: Optional[str] = None
    transaction_type: Optional[str] = None
    
    # Client
    client_ip: Optional[str] = None
    user_agent: Optional[str] = None
    
    # Timing
    timestamp: datetime = field(default_factory=datetime.utcnow)
    
    # Additional
    attributes: Dict[str, Any] = field(default_factory=dict)
    
    @classmethod
    def from_headers(cls, headers: Dict[str, str]) -> 'StandardContext':
        """Create context from HTTP headers"""
        return cls(
            user_id=headers.get('X-User-ID', 'anonymous'),
            tenant_id=headers.get('X-Tenant-ID', 'default'),
            user_email=headers.get('X-User-Email'),
            user_groups=headers.get('X-User-Groups', '').split(',') if headers.get('X-User-Groups') else [],
            request_id=headers.get('X-Request-ID', str(uuid.uuid4())),
            correlation_id=headers.get('X-Correlation-ID', str(uuid.uuid4())),
            session_id=headers.get('X-Session-ID'),
            device_id=headers.get('X-Device-ID'),
            origin_service=headers.get('X-Service-Name'),
            transaction_type=headers.get('X-Transaction-Type'),
            client_ip=headers.get('X-Client-IP'),
            user_agent=headers.get('User-Agent'),
        )
    
    def to_headers(self) -> Dict[str, str]:
        """Convert context to HTTP headers for propagation"""
        headers = {
            'X-User-ID': self.user_id,
            'X-Tenant-ID': self.tenant_id,
            'X-Request-ID': self.request_id,
            'X-Correlation-ID': self.correlation_id,
            'X-Service-Name': self.service_name,
        }
        
        if self.user_email:
            headers['X-User-Email'] = self.user_email
        if self.user_groups:
            headers['X-User-Groups'] = ','.join(self.user_groups)
        if self.session_id:
            headers['X-Session-ID'] = self.session_id
        if self.device_id:
            headers['X-Device-ID'] = self.device_id
        if self.transaction_type:
            headers['X-Transaction-Type'] = self.transaction_type
        if self.client_ip:
            headers['X-Client-IP'] = self.client_ip
            
        return headers
    
    def to_log_context(self) -> Dict[str, Any]:
        """Convert to logging context"""
        return {
            'user_id': self.user_id,
            'tenant_id': self.tenant_id,
            'correlation_id': self.correlation_id,
            'request_id': self.request_id,
            'transaction_type': self.transaction_type,
            'service': self.service_name,
        }

class ContextManager:
    """Manager for standard context operations"""
    
    @staticmethod
    def set_context(context: StandardContext):
        """Set context for current async context"""
        current_context.set(context)
    
    @staticmethod
    def get_context() -> Optional[StandardContext]:
        """Get context for current async context"""
        return current_context.get()
    
    @staticmethod
    def get_context_or_default() -> StandardContext:
        """Get context or create default"""
        context = current_context.get()
        if context is None:
            context = StandardContext()
            current_context.set(context)
        return context
    
    @staticmethod
    def clear_context():
        """Clear current context"""
        current_context.set(None)

def with_context(func):
    """Decorator to ensure context is available"""
    @wraps(func)
    async def async_wrapper(*args, **kwargs):
        context = ContextManager.get_context_or_default()
        logger.info(
            f"Executing {func.__name__}",
            extra=context.to_log_context()
        )
        return await func(*args, **kwargs)
    
    @wraps(func)
    def sync_wrapper(*args, **kwargs):
        context = ContextManager.get_context_or_default()
        logger.info(
            f"Executing {func.__name__}",
            extra=context.to_log_context()
        )
        return func(*args, **kwargs)
    
    return async_wrapper if asyncio.iscoroutinefunction(func) else sync_wrapper
```

### Flask Integration

```python
# flask_context.py
from flask import Flask, request, g
from context.standard_context import StandardContext, ContextManager
import logging

logger = logging.getLogger(__name__)

def init_context_middleware(app: Flask):
    """Initialize context middleware for Flask"""
    
    @app.before_request
    def extract_context():
        """Extract context from request headers"""
        # Create context from headers
        context = StandardContext.from_headers(dict(request.headers))
        
        # Store in Flask g object and context manager
        g.context = context
        ContextManager.set_context(context)
        
        # Configure logging with context
        logging.LoggerAdapter(logger, context.to_log_context())
        
        logger.info(
            f"{request.method} {request.path}",
            extra=context.to_log_context()
        )
    
    @app.after_request
    def add_correlation_header(response):
        """Add correlation ID to response"""
        if hasattr(g, 'context'):
            response.headers['X-Correlation-ID'] = g.context.correlation_id
            response.headers['X-Server-Timestamp'] = g.context.timestamp.isoformat()
        return response
    
    @app.teardown_request
    def clear_context(error=None):
        """Clear context after request"""
        ContextManager.clear_context()
        if error:
            logger.error(
                f"Request failed: {error}",
                extra=g.context.to_log_context() if hasattr(g, 'context') else {}
            )

# Usage
app = Flask(__name__)
init_context_middleware(app)

@app.route('/api/process', methods=['POST'])
@with_context
def process_request():
    context = g.context
    
    # Use context in business logic
    if context.tenant_id != 'default':
        # Tenant-specific logic
        pass
    
    # Make downstream call with context
    response = requests.post(
        'http://downstream-service/api/endpoint',
        headers=context.to_headers(),
        json={'data': 'value'}
    )
    
    return {'status': 'processed', 'correlation_id': context.correlation_id}
```

## Async Messaging (Kafka)

### Producer with Context

```java
// KafkaContextProducer.java
package com.example.kafka;

import com.example.common.context.ContextHolder;
import com.example.common.context.StandardContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
public class KafkaContextProducer {
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    public KafkaContextProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
    }
    
    public void sendWithContext(String topic, String key, Object payload) {
        StandardContext context = ContextHolder.getContextOrDefault();
        
        // Create enriched payload with context
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("payload", payload);
        envelope.put("context", createContextMap(context));
        envelope.put("timestamp", System.currentTimeMillis());
        
        // Create producer record
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, envelope);
        
        // Add context as headers for systems that can read headers
        addContextHeaders(record, context);
        
        // Send with callback
        kafkaTemplate.send(record).addCallback(
            result -> {
                logger.info("Message sent to topic {} with key {} for user {} in tenant {}",
                    topic, key, context.getUserId(), context.getTenantId());
            },
            error -> {
                logger.error("Failed to send message to topic {} for user {} in tenant {}",
                    topic, context.getUserId(), context.getTenantId(), error);
            }
        );
    }
    
    private Map<String, Object> createContextMap(StandardContext context) {
        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put("user_id", context.getUserId());
        contextMap.put("tenant_id", context.getTenantId());
        contextMap.put("request_id", context.getRequestId());
        contextMap.put("correlation_id", context.getCorrelationId());
        contextMap.put("session_id", context.getSessionId());
        contextMap.put("service_name", context.getServiceName());
        contextMap.put("transaction_type", context.getTransactionType());
        return contextMap;
    }
    
    private void addContextHeaders(ProducerRecord<String, Object> record, StandardContext context) {
        record.headers()
            .add("X-User-ID", context.getUserId().getBytes(StandardCharsets.UTF_8))
            .add("X-Tenant-ID", context.getTenantId().getBytes(StandardCharsets.UTF_8))
            .add("X-Correlation-ID", context.getCorrelationId().getBytes(StandardCharsets.UTF_8))
            .add("X-Request-ID", context.getRequestId().getBytes(StandardCharsets.UTF_8));
        
        if (context.getTransactionType() != null) {
            record.headers().add("X-Transaction-Type", 
                context.getTransactionType().getBytes(StandardCharsets.UTF_8));
        }
    }
}
```

### Consumer with Context

```java
// KafkaContextConsumer.java
package com.example.kafka;

import com.example.common.context.ContextHolder;
import com.example.common.context.StandardContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class KafkaContextConsumer {
    
    private static final Logger logger = LoggerFactory.getLogger(KafkaContextConsumer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @KafkaListener(topics = "${app.kafka.topics}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record) {
        StandardContext context = null;
        
        try {
            // Extract context from message
            context = extractContext(record);
            
            // Set context for thread
            ContextHolder.setContext(context);
            
            // Set MDC for logging
            populateMDC(context);
            
            logger.info("Processing message from topic {} with key {}",
                record.topic(), record.key());
            
            // Process the message
            processMessage(record.value(), context);
            
            logger.info("Successfully processed message for user {} in tenant {}",
                context.getUserId(), context.getTenantId());
            
        } catch (Exception e) {
            logger.error("Error processing message for user {} in tenant {}",
                context != null ? context.getUserId() : "unknown",
                context != null ? context.getTenantId() : "unknown", e);
            throw e;
        } finally {
            ContextHolder.clearContext();
            MDC.clear();
        }
    }
    
    private StandardContext extractContext(ConsumerRecord<String, String> record) {
        try {
            // Try to extract from message envelope first
            JsonNode root = objectMapper.readTree(record.value());
            
            if (root.has("context")) {
                JsonNode contextNode = root.get("context");
                return StandardContext.builder()
                    .userId(getTextValue(contextNode, "user_id", "anonymous"))
                    .tenantId(getTextValue(contextNode, "tenant_id", "default"))
                    .requestId(getTextValue(contextNode, "request_id"))
                    .correlationId(getTextValue(contextNode, "correlation_id"))
                    .sessionId(getTextValue(contextNode, "session_id"))
                    .serviceName(getTextValue(contextNode, "service_name"))
                    .transactionType(getTextValue(contextNode, "transaction_type"))
                    .build();
            }
        } catch (Exception e) {
            logger.debug("Failed to extract context from message envelope: {}", e.getMessage());
        }
        
        // Fall back to headers
        return extractContextFromHeaders(record.headers());
    }
    
    private StandardContext extractContextFromHeaders(org.apache.kafka.common.header.Headers headers) {
        return StandardContext.builder()
            .userId(getHeaderValue(headers, "X-User-ID", "anonymous"))
            .tenantId(getHeaderValue(headers, "X-Tenant-ID", "default"))
            .correlationId(getHeaderValue(headers, "X-Correlation-ID"))
            .requestId(getHeaderValue(headers, "X-Request-ID"))
            .transactionType(getHeaderValue(headers, "X-Transaction-Type"))
            .build();
    }
    
    private String getHeaderValue(org.apache.kafka.common.header.Headers headers, String key) {
        return getHeaderValue(headers, key, null);
    }
    
    private String getHeaderValue(org.apache.kafka.common.header.Headers headers, String key, String defaultValue) {
        Header header = headers.lastHeader(key);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return defaultValue;
    }
    
    private String getTextValue(JsonNode node, String field) {
        return getTextValue(node, field, null);
    }
    
    private String getTextValue(JsonNode node, String field, String defaultValue) {
        JsonNode fieldNode = node.get(field);
        return fieldNode != null && !fieldNode.isNull() ? fieldNode.asText() : defaultValue;
    }
    
    private void populateMDC(StandardContext context) {
        MDC.put("userId", context.getUserId());
        MDC.put("tenantId", context.getTenantId());
        MDC.put("correlationId", context.getCorrelationId());
        MDC.put("requestId", context.getRequestId());
        MDC.put("transactionType", context.getTransactionType());
        MDC.put("service", context.getServiceName());
    }
    
    private void processMessage(String message, StandardContext context) {
        // Business logic with context
        if ("premium".equals(context.getTenantId())) {
            // Premium tenant processing
        } else {
            // Standard processing
        }
    }
}
```

## Database & CDC Context

### Outbox Pattern with Context

```sql
-- Outbox table with standard context
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    
    -- Standard context columns
    user_id VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(255) NOT NULL,
    request_id VARCHAR(255),
    session_id VARCHAR(255),
    transaction_type VARCHAR(100),
    service_name VARCHAR(100),
    
    -- Metadata
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    processed_at TIMESTAMP,
    
    -- Indexes for performance
    INDEX idx_tenant_user (tenant_id, user_id),
    INDEX idx_correlation (correlation_id),
    INDEX idx_unprocessed (processed_at) WHERE processed_at IS NULL,
    INDEX idx_created_at (created_at)
);

-- Context audit table
CREATE TABLE context_audit (
    id BIGSERIAL PRIMARY KEY,
    correlation_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    service_name VARCHAR(100) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id VARCHAR(255),
    old_values JSONB,
    new_values JSONB,
    client_ip VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_audit_correlation (correlation_id),
    INDEX idx_audit_user_tenant (tenant_id, user_id),
    INDEX idx_audit_created (created_at)
);
```

### JPA Auditing with Context

```java
// ContextAwareAuditor.java
package com.example.common.audit;

import com.example.common.context.ContextHolder;
import com.example.common.context.StandardContext;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ContextAwareAuditor implements AuditorAware<String> {
    
    @Override
    public Optional<String> getCurrentAuditor() {
        StandardContext context = ContextHolder.getContext();
        if (context != null) {
            return Optional.of(context.getUserId());
        }
        return Optional.of("system");
    }
}

// Base entity with context audit fields
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class ContextAuditableEntity {
    
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;
    
    @Column(name = "created_by")
    @CreatedBy
    private String createdBy;
    
    @Column(name = "created_at")
    @CreatedDate
    private Instant createdAt;
    
    @Column(name = "modified_by")
    @LastModifiedBy
    private String modifiedBy;
    
    @Column(name = "modified_at")
    @LastModifiedDate
    private Instant modifiedAt;
    
    @Column(name = "correlation_id")
    private String correlationId;
    
    @PrePersist
    @PreUpdate
    public void setContextFields() {
        StandardContext context = ContextHolder.getContext();
        if (context != null) {
            if (this.tenantId == null) {
                this.tenantId = context.getTenantId();
            }
            this.correlationId = context.getCorrelationId();
        }
    }
    
    // Getters and setters...
}
```

## Scheduled Jobs & System Context

### System Context for Background Jobs

```java
// ScheduledJobsWithContext.java
package com.example.jobs;

import com.example.common.context.ContextHolder;
import com.example.common.context.StandardContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ScheduledJobsWithContext {
    
    private static final Logger logger = LoggerFactory.getLogger(ScheduledJobsWithContext.class);
    
    @Scheduled(fixedDelay = 30000) // Every 30 seconds
    public void processExpiredLinks() {
        // Create system context for scheduled job
        StandardContext context = createSystemContext("expire-links-job");
        
        try {
            ContextHolder.setContext(context);
            
            logger.info("Starting scheduled job: expire-links");
            
            // Job logic with context
            int processed = expireLinks(context);
            
            logger.info("Completed scheduled job: expire-links, processed {} items", processed);
            
        } catch (Exception e) {
            logger.error("Scheduled job failed: expire-links", e);
        } finally {
            ContextHolder.clearContext();
        }
    }
    
    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void generateAnalyticsReport() {
        StandardContext context = createSystemContext("analytics-report-job");
        
        ContextHolder.executeWithContext(context, () -> {
            logger.info("Starting scheduled job: analytics-report");
            
            // Process each tenant separately
            for (String tenantId : getTenantIds()) {
                StandardContext tenantContext = createTenantSystemContext(
                    "analytics-report-job", 
                    tenantId
                );
                
                ContextHolder.executeWithContext(tenantContext, () -> {
                    generateTenantReport(tenantId);
                    return null;
                });
            }
            
            return null;
        });
    }
    
    private StandardContext createSystemContext(String jobName) {
        return StandardContext.builder()
            .userId("system-scheduler")
            .tenantId("system")
            .correlationId(UUID.randomUUID().toString())
            .requestId(UUID.randomUUID().toString())
            .serviceName(getServiceName())
            .transactionType(jobName)
            .build();
    }
    
    private StandardContext createTenantSystemContext(String jobName, String tenantId) {
        return StandardContext.builder()
            .userId("system-scheduler")
            .tenantId(tenantId)
            .correlationId(UUID.randomUUID().toString())
            .requestId(UUID.randomUUID().toString())
            .serviceName(getServiceName())
            .transactionType(jobName)
            .attribute("job.tenant", tenantId)
            .build();
    }
    
    private String getServiceName() {
        return System.getProperty("spring.application.name", "scheduler");
    }
}
```

## Common Patterns

### 1. Context Propagation in Thread Pools

```java
// ContextAwareExecutor.java
package com.example.common.concurrent;

import com.example.common.context.ContextHolder;
import com.example.common.context.StandardContext;

import java.util.concurrent.*;

public class ContextAwareExecutorService implements ExecutorService {
    
    private final ExecutorService delegate;
    
    public ContextAwareExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }
    
    @Override
    public void execute(Runnable command) {
        delegate.execute(wrapWithContext(command));
    }
    
    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrapWithContext(task));
    }
    
    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(wrapWithContext(task));
    }
    
    private Runnable wrapWithContext(Runnable task) {
        StandardContext context = ContextHolder.getContext();
        return () -> {
            StandardContext previousContext = ContextHolder.getContext();
            try {
                ContextHolder.setContext(context);
                task.run();
            } finally {
                ContextHolder.setContext(previousContext);
            }
        };
    }
    
    private <T> Callable<T> wrapWithContext(Callable<T> task) {
        StandardContext context = ContextHolder.getContext();
        return () -> {
            StandardContext previousContext = ContextHolder.getContext();
            try {
                ContextHolder.setContext(context);
                return task.call();
            } finally {
                ContextHolder.setContext(previousContext);
            }
        };
    }
    
    // Delegate other methods...
}

// Configuration
@Configuration
public class ExecutorConfig {
    
    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("context-aware-");
        executor.setTaskDecorator(new ContextTaskDecorator());
        executor.initialize();
        return executor;
    }
    
    static class ContextTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            StandardContext context = ContextHolder.getContext();
            return () -> {
                try {
                    ContextHolder.setContext(context);
                    runnable.run();
                } finally {
                    ContextHolder.clearContext();
                }
            };
        }
    }
}
```

### 2. Reactive Context Propagation (WebFlux)

```java
// ReactiveContextFilter.java
package com.example.reactive;

import com.example.common.context.StandardContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
public class ReactiveContextFilter implements WebFilter {
    
    public static final String CONTEXT_KEY = "standard-context";
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        StandardContext standardContext = extractContext(exchange);
        
        return chain.filter(exchange)
            .contextWrite(Context.of(CONTEXT_KEY, standardContext))
            .doOnEach(signal -> {
                if (!signal.isOnComplete()) {
                    return;
                }
                // Log completion with context
                logger.info("Request completed for user {} in tenant {}",
                    standardContext.getUserId(), 
                    standardContext.getTenantId());
            });
    }
    
    private StandardContext extractContext(ServerWebExchange exchange) {
        var headers = exchange.getRequest().getHeaders();
        
        return StandardContext.builder()
            .userId(headers.getFirst("X-User-ID"))
            .tenantId(headers.getFirst("X-Tenant-ID"))
            .correlationId(headers.getFirst("X-Correlation-ID"))
            .requestId(headers.getFirst("X-Request-ID"))
            .sessionId(headers.getFirst("X-Session-ID"))
            .build();
    }
    
    public static Mono<StandardContext> getContext() {
        return Mono.deferContextual(ctx -> 
            Mono.justOrEmpty(ctx.getOrEmpty(CONTEXT_KEY))
        );
    }
}

// Usage in reactive service
@RestController
public class ReactiveController {
    
    @GetMapping("/reactive/data")
    public Mono<ResponseEntity<Data>> getData() {
        return ReactiveContextFilter.getContext()
            .flatMap(context -> {
                logger.info("Processing request for user: {}", context.getUserId());
                
                return dataService.fetchData(context.getTenantId())
                    .map(data -> ResponseEntity.ok()
                        .header("X-Correlation-ID", context.getCorrelationId())
                        .body(data));
            });
    }
}
```

## Testing & Validation

### Unit Test Context Helper

```java
// TestContextBuilder.java
package com.example.test;

import com.example.common.context.StandardContext;
import com.example.common.context.ContextHolder;

public class TestContextBuilder {
    
    public static StandardContext createTestContext() {
        return StandardContext.builder()
            .userId("test-user")
            .tenantId("test-tenant")
            .correlationId("test-correlation")
            .requestId("test-request")
            .serviceName("test-service")
            .transactionType("test-transaction")
            .build();
    }
    
    public static void withContext(Runnable test) {
        StandardContext context = createTestContext();
        try {
            ContextHolder.setContext(context);
            test.run();
        } finally {
            ContextHolder.clearContext();
        }
    }
}

// Test example
@Test
public void testServiceWithContext() {
    TestContextBuilder.withContext(() -> {
        // Your test code here
        Result result = service.process(data);
        
        // Verify context was used
        verify(auditService).audit(
            argThat(audit -> 
                "test-user".equals(audit.getUserId()) &&
                "test-tenant".equals(audit.getTenantId())
            )
        );
    });
}
```

### Integration Test Validation

```java
// ContextIntegrationTest.java
@SpringBootTest
@AutoConfigureMockMvc
public class ContextIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    public void testContextPropagation() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        
        mockMvc.perform(post("/api/process")
                .header("X-User-ID", "test-user")
                .header("X-Tenant-ID", "test-tenant")
                .header("X-Correlation-ID", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\": \"test\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-ID", correlationId))
            .andExpect(jsonPath("$.processedBy").value("test-user"));
        
        // Verify context in logs
        assertThat(logCapture.getLogs())
            .contains("userId=test-user")
            .contains("tenantId=test-tenant")
            .contains("correlationId=" + correlationId);
    }
}
```

## Troubleshooting

### Common Issues and Solutions

1. **Missing Context in Logs**
   - Verify MDC configuration in logback/log4j
   - Check filter order (context filter should be first)
   - Ensure MDC.clear() is called in finally blocks

2. **Context Lost in Async Operations**
   - Use context-aware executors
   - Capture context before async boundary
   - Restore context in async callbacks

3. **Context Not Propagating to Kafka**
   - Check producer is adding headers/envelope
   - Verify consumer is extracting from correct location
   - Ensure JSON serialization is working

4. **Wrong Tenant Data Access**
   - Verify tenant ID in context matches data query
   - Check row-level security implementation
   - Audit all data access with context

5. **Performance Impact**
   - Cache validated context (Redis/Memcached)
   - Avoid repeated JWT validation
   - Use efficient header extraction

### Debug Logging Configuration

```yaml
# application.yml
logging:
  level:
    com.example.common.context: DEBUG
    com.example.kafka: DEBUG
  pattern:
    console: "%d{ISO8601} [%thread] %-5level %logger{36} - userId=%X{userId} tenantId=%X{tenantId} correlationId=%X{correlationId} - %msg%n"
    file: "%d{ISO8601} [%thread] %-5level %logger{36} - userId=%X{userId} tenantId=%X{tenantId} correlationId=%X{correlationId} - %msg%n"
```

### Context Validation Script

```bash
#!/bin/bash
# validate-context.sh - Test context propagation

CORRELATION_ID=$(uuidgen)
USER_ID="test-user-$$"
TENANT_ID="test-tenant"

echo "Testing with Correlation ID: $CORRELATION_ID"

# Make API call
response=$(curl -s -H "X-User-ID: $USER_ID" \
  -H "X-Tenant-ID: $TENANT_ID" \
  -H "X-Correlation-ID: $CORRELATION_ID" \
  -H "Content-Type: application/json" \
  -d '{"test": true}' \
  http://localhost:8080/api/test)

# Check response header
if echo "$response" | grep -q "$CORRELATION_ID"; then
  echo "✓ Correlation ID in response"
else
  echo "✗ Correlation ID missing in response"
fi

# Check logs
if docker logs app 2>&1 | grep -q "userId=$USER_ID.*tenantId=$TENANT_ID.*correlationId=$CORRELATION_ID"; then
  echo "✓ Context in logs"
else
  echo "✗ Context missing in logs"
fi

# Check Kafka message (if applicable)
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic events \
  --max-messages 1 \
  --property print.headers=true | grep -q "X-User-ID:$USER_ID"
```

## Adoption Strategy

### Why Not Create Libraries Yet?

Similar to OpenTelemetry adoption, **we strongly recommend against creating context libraries immediately**. Here's why:

#### The Evolution Path

1. **First Implementation** ✅: Reference implementation (this guide)
2. **Second Implementation** ⏳: Teams discover variations
3. **Third Implementation** ⏳: Patterns stabilize

We're at stage one. Creating libraries now risks the wrong abstractions.

#### Unknown Variations

Each organization will have unique needs:
- Different identity providers (Okta, Auth0, Keycloak, Azure AD)
- Various compliance requirements (GDPR, HIPAA, SOC2)
- Different multi-tenancy models (schema, row-level, hybrid)
- Custom business context fields
- Performance constraints

### Recommended Incremental Approach

#### Phase 1: Reference Implementation (Months 1-3)
**Current Stage**
- Teams study this guide
- Copy and adapt patterns
- Document variations needed
- Share learnings

#### Phase 2: Pattern Templates (Months 4-6)
**After 2-3 Implementations**
- Create template repositories
- Document common patterns
- Build snippet library
- Establish naming conventions

#### Phase 3: Shared Components (Months 7+)
**With Proven Patterns**
- Extract truly common code
- Create thin libraries
- Provide extension points
- Maintain carefully

### What to Use Instead of Libraries

1. **Template Repositories**
```
standard-context-java-template/
standard-context-python-template/
standard-context-node-template/
```

2. **Code Snippet Collection**
- MDC filter implementation
- Kafka context producer/consumer
- Async context propagation
- Test context builders

3. **Configuration Templates**
- Logback MDC configuration
- Spring Security context integration
- Kafka header mappings

### When to Create Libraries

Wait for these signals:
- ✅ 3+ successful implementations
- ✅ Minimal variations between implementations
- ✅ Teams requesting shared libraries
- ✅ Dedicated maintainers available
- ✅ Clear 80/20 rule understanding

### Benefits of Waiting

1. **Deep Understanding**: Teams learn context propagation internals
2. **Flexibility**: Adapt to specific requirements
3. **Reduced Risk**: Avoid maintaining unused abstractions
4. **Better Design**: Libraries based on real usage
5. **Team Buy-in**: Engineers understand before abstracting

## Conclusion

Standard context propagation is essential for:
- **Security**: Know who is doing what
- **Multi-tenancy**: Ensure data isolation
- **Debugging**: Understand business context of errors
- **Compliance**: Maintain audit trails
- **Operations**: Correlate across services

Key success factors:
1. Establish context once at the edge
2. Propagate automatically everywhere
3. Make it immutable and trustworthy
4. Include in all logs and events
5. Test thoroughly at all boundaries
6. **Don't abstract too early** - let patterns emerge

Remember: Context is not just technical metadata - it's the business story of every request. Make it complete, make it automatic, make it reliable.