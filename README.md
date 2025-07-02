# otel-shortener-demo

A reference application for demonstrating end-to-end distributed tracing with OpenTelemetry. This project implements a URL shortener using a modern microservices architecture to showcase realistic tracing and authentication scenarios.

## 🎯 Core Objective

The primary goal of this project is to serve as a hands-on, educational tool for understanding and implementing distributed tracing. It demonstrates:
- The **W3C Trace Context** specification via **OpenTelemetry**.
- Tracing across synchronous (HTTP) and asynchronous (Kafka) boundaries.
- **Server-to-server authentication** using the OAuth 2.0 Client Credentials grant.
- **Centralized authorization** at an edge service (BFF).
- OpenTelemetry context propagation in both **blocking (Servlet)** and **non-blocking (Reactive WebFlux)** Java frameworks.

## 🛠️ Tech Stack

- **Frontend (SPA):** Next.js / React
- **Backend-for-Frontend (BFF):** Node.js (Express.js)
- **Microservices (Java 21+):**
    - **Spring Boot 3+ (MVC / Servlet):** For the `URL API`.
    - **Spring Boot 3+ (WebFlux / Reactive):** For the `Redirect Service`.
- **Messaging Queue:** Apache Kafka
- **Database:** PostgreSQL (with `r2dbc-postgresql` for reactive access)
- **Identity & Access Mgmt:** Keycloak (with both User and M2M authentication)
- **Feature Flagging:** flagd
- **Observability:** OpenTelemetry

## 📡 Context & Header Strategy

A standardized set of headers is used to propagate context through the system.

| Header | Purpose | Owner / Generator |
|---|---|---|
| `traceparent` | **W3C Trace Context.** Carries the `trace-id`, `parent-id`, and sampling flags. | OpenTelemetry SDK |
| `tracestate` | **W3C Trace Context.** Carries vendor-specific trace information. | OpenTelemetry SDK |
| `Authorization` | **Service-level Authentication.** Carries the M2M Bearer token. | BFF |
| `X-User-ID` | **User Identity Context.** Carries the verified `sub` claim of the end-user. | BFF |
| `X-Tenant-ID` | **Tenant Context.** Carries the tenant identifier associated with the user. | BFF |

**A Note on `X-Request-ID`:** This header is intentionally **not used**. In a modern OpenTelemetry-native application, the `trace-id` from the `traceparent` header serves as the definitive, unique identifier for an entire request chain, making a separate `X-Request-ID` header redundant.

## 🏗️ Architecture

### Component Responsibilities

- **`Frontend (SPA)`**: The user's entry point. Handles login via Keycloak, provides a UI to submit a URL, and displays the result. It is responsible for initiating the trace.
- **`BFF (Node.js)`**: The secure gateway and **Policy Enforcement Point**. It validates the end-user's JWT from Keycloak. **It then inspects the user's token for the necessary permissions (e.g., `scopes` or `roles`) to authorize the request.** If the user is authorized, the BFF authenticates *itself* with Keycloak (using Client Credentials) to obtain an M2M token before calling the `URL API`. If not, it rejects the request with a `403 Forbidden`.
- **`URL API (Java/MVC)`**: The primary "write" service, built with **Spring Boot MVC (Servlet)**. It is protected and requires a valid M2M token from the BFF. **It operates on the principle that the BFF has already enforced user authorization,** and focuses solely on its business logic.
- **`Redirect Service (Java/WebFlux)`**: A highly-optimized "read" service, built with **Spring Boot WebFlux (Reactive)** to handle high concurrency. It uses the reactive `R2DBC` driver to query Postgres non-blockingly. This service is public.
- **`Analytics API (Java)`**: An asynchronous "worker" service. It consumes events from Kafka to process and store analytics data.
- **`Keycloak`**: Manages authentication for both end-users (via Authorization Code Flow) and services (via Client Credentials Flow).
- **`Postgres`**: The primary data store. Must be accessible via both traditional JDBC and reactive R2DBC drivers.
- **`Kafka`**: The messaging backbone for a decoupling service.
- **`flagd`**: A server for providing dynamic feature flags.

## 🚀 User Flows & Tracing Verification

The success of the demo is measured by visualizing two complete, end-to-end traces.

### Trace 1: Creating a Link

This trace starts when the user clicks "Shrink It!" and should contain the following connected spans:

1.  **`span: ui-create-link`** (from: React SPA)
2.  **`span: bff-post-link`** (from: Node.js BFF)
3.  **`span: url-api-create-link`** (from: URL API)
4.  **`span: flagd-check-custom-alias`** (from: URL API, calling flagd)
5.  **`span: db-insert-link`** (from: URL API, calling Postgres)
6.  **`span: kafka-publish-created`** (from: URL API, publishing to Kafka)
7.  **`span: analytics-api-process-created`** (from: Analytics API, consuming from Kafka, linked to the publishing span)

### Trace 2: Using a Link

This trace starts when a user accesses a short URL and should contain these connected spans:

1.  **`span: redirect-service-get-link`** (from: Redirect Service)
2.  **`span: db-lookup-link`** (from: Redirect Service, calling Postgres via R2DBC)
3.  **`span: kafka-publish-clicked`** (from: Redirect Service, publishing to Kafka)
4.  **`span: analytics-api-process-clicked`** (from: Analytics API, consuming from Kafka, linked to the publishing span)

## 🗃️ Data Models (PostgreSQL)

#### `links` table
| Column | Type | Constraints | Description |
|---|---|---|---|
| `short_code` | `VARCHAR(16)` | `PRIMARY KEY` | The unique, randomly generated short identifier. |
| `long_url` | `TEXT` | `NOT NULL` | The original URL. |
| `user_id` | `VARCHAR(255)` | `NOT NULL` | The Keycloak user ID of the creator. |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | `DEFAULT NOW()` | Timestamp of creation. |

#### `clicks` table
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `BIGSERIAL` | `PRIMARY KEY` | Unique identifier for the click event. |
| `link_short_code`| `VARCHAR(16)` | `FK to links.short_code` | The link that was clicked. |
| `clicked_at` | `TIMESTAMP WITH TIME ZONE` | `DEFAULT NOW()` | Timestamp of the click. |
| `user_agent` | `TEXT` | `NULL` | The User-Agent string of the clicker. |

## 🔌 API Contracts (High-Level)

### BFF (`Node.js`)
- **`POST /api/links`** (Protected by Keycloak User Token)
    - **Request Body:** `{ "url": "https://opentelemetry.io/" }`
    - **Success Response (201):** `{ "shortUrl": "http://localhost:8081/aBcDeF" }`
    - **Action:** Validates user token and permissions, then calls `URL API` with an M2M token.

### URL API (`Java/MVC`)
- **`POST /links`** (Internal Service, Protected by M2M Token)
    - **Request Body:** `{ "url": "https://opentelemetry.io/", "userId": "user-sub-from-jwt", "tenantId": "tenant-from-jwt" }`
    - **Success Response (201):** `{ "shortCode": "aBcDeF" }`

### Redirect Service (`Java/WebFlux`)
- **`GET /{shortCode}`** (Public)
    - **Example:** `GET /aBcDeF`
    - **Success Response (302):** HTTP Redirect to the corresponding `long_url`.
    - **Error Response (404):** If `shortCode` not found.

## 📨 Kafka Topics & Payloads

1.  **Topic: `url-creations`**
    - **Payload:**
      ```json
      {
        "shortCode": "aBcDeF",
        "longUrl": "[https://opentelemetry.io/](https://opentelemetry.io/)",
        "userId": "user-sub-from-jwt",
        "tenantId": "tenant-from-jwt",
        "createdAt": "2025-07-02T11:07:15Z"
      }
      ```

2.  **Topic: `url-clicks`**
    - **Payload:**
      ```json
      {
        "shortCode": "aBcDeF",
        "clickedAt": "2025-07-02T11:08:00Z",
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ...",
        "ipAddress": "192.168.1.10"
      }
      ```

## 🚩 Feature Flag (flagd)

A boolean feature flag will be used to demonstrate dynamic configuration.

- **Flag Key:** `custom-alias-enabled`
- **Consumer:** The `URL API` will query this flag.
- **Logic:** If `true`, the API could theoretically allow users to specify a custom shortcode instead of a random one. For the demo, it can simply add a log entry or a tag to the trace indicating the flag was checked and its value.

## 📚 References & Further Reading

This project is built upon the concepts and specifications from the following official documentation. These links are excellent resources for diving deeper into the technologies used.

- **[W3C Trace Context Specification](https://www.w3.org/TR/trace-context/)**: The official web standard for propagating distributed trace context.
- **[OpenTelemetry for Java](https://opentelemetry.io/docs/languages/java/)**: The main documentation for using OpenTelemetry in Java.
- **[OpenTelemetry Spring Boot Starter](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/)**: Specific guide for the "zero-code" starter we'll use in our Java services.
- **[OpenTelemetry for JavaScript](https://opentelemetry.io/docs/languages/js/)**: The main documentation for instrumenting the Node.js BFF and the Next.js frontend.

## ✨ Collaborating with an AI Assistant

This document provides the complete requirements for the `otel-shortener-demo`. When working with a coding assistant (like Google Gemini), use this file as the primary source of truth. Pay special attention to the advanced requirements:

1.  **Dual Auth in Keycloak:** Configure one client for the Frontend (public, with standard flow) and another for the BFF (confidential, for client credentials). The `URL API` must be configured as a resource server that validates tokens intended for the BFF client.
2.  **Authorization at the Edge:** Implement the authorization logic within the BFF. The BFF should reject requests with a `403 Forbidden` if the user's JWT lacks the required scopes. Downstream services should not contain user authorization logic.
3.  **BFF Logic:** The BFF must contain the logic to manage its M2M token, including caching and renewal.
4.  **Servlet vs. WebFlux:** Ensure the `URL API` is built using `spring-boot-starter-web` and the `Redirect Service` is built using `spring-boot-starter-webflux`. This will require different dependency sets and coding styles.
5.  **Reactive Database:** The `Redirect Service` must use `spring-boot-starter-data-r2dbc` and the `r2dbc-postgresql` driver for its database interactions.
