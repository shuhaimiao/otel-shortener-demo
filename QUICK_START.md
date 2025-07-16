# Quick Start Guide

This guide provides step-by-step instructions to get the `otel-shortener-demo` application running locally and to test its core functionality.

## Prerequisites

- Docker and Docker Compose are installed and running on your machine.
- You have cloned this repository.

## 1. Start The Application

The entire application stack is orchestrated using Docker Compose.

1.  Open a terminal in the root directory of the project.
2.  Run the following command to build the images and start all the services in the background:

    ```bash
    docker-compose up --build -d
    ```

    This will start all the services defined in `docker-compose.yml`, including the frontend, all backend microservices, Keycloak, PostgreSQL, Kafka, and Jaeger. It may take a few minutes for all services to become healthy, especially Keycloak.

## 2. Test the URL Shortener

### Step 2.1: Access the Frontend

Once the services are running, open your web browser and navigate to the frontend application:

- **URL:** `http://localhost:3000`

### Step 2.2: Log In

The application uses Keycloak for authentication. You will be prompted to log in.

- **Keycloak Instance:** `http://localhost:8880`
- **Username:** `testuser`
- **Password:** `password`

Upon successful login, you will be redirected back to the URL shortener application.

### Step 2.3: Create a Short Link

1.  In the input box, enter a long URL you want to shorten (e.g., `https://opentelemetry.io/`).
2.  Click the "Shrink It!" button.
3.  The application will call the backend services, and you will see the generated short URL, which will look something like `http://localhost:8081/aBcDeF`.

### Step 2.4: Test the Redirect

1.  Copy the generated short URL.
2.  Paste it into a new browser tab or window and press Enter.
3.  You should be immediately redirected to the original long URL you provided.

## 3. Verify Distributed Traces in Jaeger

The core purpose of this demo is to showcase end-to-end distributed tracing. You can visualize these traces in the Jaeger UI.

1.  Open the Jaeger UI in your browser:

    - **URL:** `http://localhost:16686`

2.  In the Jaeger UI search panel (on the left):
    - Select `bff` from the **Service** dropdown.
    - Click **Find Traces**.

3.  You should see traces corresponding to the actions you just performed:
    - **Trace 1: Creating a Link (`/api/links`)**: This trace is initiated when you click "Shrink It!". It should show a series of connected spans starting from the `bff`, going to the `url-api`, `flagd`, `postgres` (via JDBC), and publishing to `kafka`. You will also see a subsequent, separate trace initiated by the `analytics-api` consuming the Kafka message.
    - **Trace 2: Using a Link (`/{shortCode}`)**: This trace is initiated when you access the short URL. It should show spans from the `redirect-service` looking up the link in `postgres` (via R2DBC) and publishing another event to `kafka`.

## 4. Shutting Down

To stop all the running services, run the following command in your terminal:

```bash
docker-compose down
```

If you also want to remove the persistent data (like the PostgreSQL database), run:

```bash
docker-compose down -v
``` 