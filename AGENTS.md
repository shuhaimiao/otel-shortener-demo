# Agent Instructions for otel-shortener-demo

This document provides instructions for AI assistants working with the `otel-shortener-demo` repository.

## Project Overview

This project is a URL shortener application built with a microservices architecture. It is designed to demonstrate end-to-end distributed tracing with OpenTelemetry. Please refer to `README.md` for detailed architectural and functional requirements.

## Development Environment

All services are containerized using Docker and managed with Docker Compose.

### Prerequisites

*   Docker installed and running.
*   Docker Compose installed (usually included with Docker Desktop).
*   Sufficient local resources (CPU, memory) to run multiple containers. Recommended: at least 8GB RAM.

### Building and Running the Project

1.  **Clone the Repository:**
    ```bash
    git clone <repository-url>
    cd otel-shortener-demo
    ```

2.  **Start all services using Docker Compose:**
    ```bash
    docker-compose up --build
    ```
    *   The `--build` flag ensures that images are rebuilt if there are changes to Dockerfiles or application code.
    *   This command will start all application services (frontend, bff, url-api, redirect-service, analytics-api) and backing services (PostgreSQL, Kafka, Zookeeper, Keycloak, flagd, OpenTelemetry Collector, Jaeger).
    *   Initial startup may take some time as images are downloaded/built and services initialize (especially Keycloak).

3.  **Accessing Services:**
    *   **Frontend:** `http://localhost:3000`
    *   **BFF API (for testing, direct access usually not needed):** `http://localhost:3001`
    *   **URL API (internal, not directly accessed by users):** `http://localhost:8080` (actuator: `/actuator/health`)
    *   **Redirect Service:** `http://localhost:8081/{shortCode}` (e.g., `http://localhost:8081/test01`)
    *   **Analytics API (internal, not directly accessed by users):** `http://localhost:8082` (actuator: `/actuator/health`)
    *   **Keycloak Admin Console:** `http://localhost:8880/auth/` (Login with `admin`/`admin`. Realm: `otel-demo`)
    *   **Jaeger UI (for Traces):** `http://localhost:16686`
    *   **flagd (management interface, if configured):** `http://localhost:8013` (check flagd docs for UI)
    *   **PostgreSQL:** Connect via `localhost:5432` (User: `otel_user`, Pass: `otel_password`, DB: `otel_shortener_db`)
    *   **Kafka:** Broker accessible at `localhost:29092` (from host) or `kafka:9092` (from other containers).

4.  **Stopping the Project:**
    *   Press `Ctrl+C` in the terminal where `docker-compose up` is running.
    *   To remove containers, networks, and volumes (optional, for a clean stop):
        ```bash
        docker-compose down -v
        ```

### Making Code Changes

1.  **Modify Service Code:**
    *   Frontend: `frontend/`
    *   BFF: `bff/`
    *   URL API: `url-api/`
    *   Redirect Service: `redirect-service/`
    *   Analytics API: `analytics-api/`

2.  **Rebuild and Restart:**
    *   If you change application code or Dockerfiles, you need to rebuild the specific service image and restart the containers.
    *   To rebuild a specific service (e.g., `url-api`) and restart all:
        ```bash
        docker-compose up --build url-api -d
        ```
    *   Or, to rebuild all and restart:
        ```bash
        docker-compose up --build -d
        ```
    *   The `-d` flag runs containers in detached mode.
    *   You can view logs using `docker-compose logs -f <service_name>` or `docker-compose logs -f` for all services.

### Key Configuration Files

*   `docker-compose.yml`: Defines all services, networks, volumes, and their configurations.
*   `frontend/Dockerfile`, `bff/Dockerfile`, etc.: Define how each application service is built into a Docker image.
*   `scripts/postgres-init.sql`: Initializes the PostgreSQL database schema and some seed data.
*   `keycloak-realm-config/otel-demo-realm.json`: Configuration for the Keycloak realm, clients, users, and roles. Imported on Keycloak startup.
*   `flagd-config/flags.json`: Feature flag definitions for `flagd`.
*   `otel-collector-config.yml`: Configuration for the OpenTelemetry Collector.

### Verifying Functionality (High-Level)

1.  **Frontend Loads:** Open `http://localhost:3000`.
2.  **Keycloak Login (Manual Setup):**
    *   Navigate to Keycloak admin console (`http://localhost:8880/auth/`).
    *   Ensure `otel-demo` realm is imported.
    *   Configure `frontend-client` redirect URIs if necessary (e.g., `http://localhost:3000/*`).
    *   Create a test user if `testuser/password` isn't working or if you need specific roles/scopes.
3.  **Create a Short Link:**
    *   Use the UI on `http://localhost:3000` to submit a URL.
    *   Observe network requests (browser dev tools) and logs from `frontend`, `bff`, `url-api`.
    *   Check Jaeger UI for the "Creating a Link" trace.
4.  **Use a Short Link:**
    *   Access a generated short link directly or one of the seed links (e.g., `http://localhost:8081/test01`).
    *   Observe logs from `redirect-service`.
    *   Check Jaeger UI for the "Using a Link" trace.
5.  **Check Kafka Messages (Optional):**
    *   You can use a Kafka tool (e.g., `kcat` or Conduktor/Offset Explorer) to inspect messages on `url-creations` and `url-clicks` topics.
    *   Example using `kcat` (if installed in a container or locally and configured for `localhost:29092`):
        ```bash
        # From another terminal, or exec into kafka container
        docker-compose exec kafka kafka-console-consumer --bootstrap-server kafka:9092 --topic url-creations --from-beginning
        docker-compose exec kafka kafka-console-consumer --bootstrap-server kafka:9092 --topic url-clicks --from-beginning
        ```

### Debugging Tips

*   **Service Logs:** `docker-compose logs -f <service_name>` (e.g., `docker-compose logs -f url-api`).
*   **Container Shell:** `docker-compose exec <service_name> /bin/sh` (or `/bin/bash` if available).
*   **Health Checks:** Check service health status in `docker-compose ps` or via actuator endpoints (e.g., `http://localhost:8080/actuator/health` for `url-api`).
*   **Port Conflicts:** Ensure ports defined in `docker-compose.yml` (e.g., 3000, 3001, 8080, 8081, 5432, 9092, 8880, 16686) are not in use by other applications on your host.

## Coding Conventions & Standards

*   Follow standard coding practices for each language/framework (Next.js/React, Node.js/Express, Java/Spring Boot).
*   Ensure OpenTelemetry instrumentation is correctly configured as per `README.md` requirements.
*   Pay attention to context propagation (Trace Context, custom headers) as outlined in `README.md`.
*   Authorization logic should primarily reside in the BFF. Downstream services should rely on M2M authentication.

## Testing

*   The `README.md` outlines two primary user flows and their expected traces. Verification of these traces in Jaeger is crucial.
*   Unit and integration tests should be added within each service's codebase as development progresses. (This setup is for initial dockerization).

This document should help in understanding how to work with the project. If specific instructions are unclear, please refer to the `README.md` or ask for clarification.Tool output for `create_file_with_block`:
