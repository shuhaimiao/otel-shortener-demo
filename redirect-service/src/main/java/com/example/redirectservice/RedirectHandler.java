package com.example.redirectservice;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.Map;

@Component
public class RedirectHandler {

    // Placeholder for database interaction
    private final Map<String, String> linkStore = Map.of(
        "testcd", "https://opentelemetry.io",
        "test02", "https://www.google.com"
    );

    public Mono<ServerResponse> handleRedirect(ServerRequest request) {
        String shortCode = request.pathVariable("shortCode");
        System.out.println("Redirect Service: Received request for shortCode: " + shortCode);

        // TODO: Implement actual logic:
        // 1. Lookup shortCode in PostgreSQL database (links table) using R2DBC
        // 2. If found, publish event to Kafka (topic: url-clicks)
        // 3. If found, return 302 Redirect.
        // 4. If not found, return 404 Not Found.

        String longUrl = linkStore.get(shortCode);

        if (longUrl != null) {
            System.out.println("Redirect Service: Found long URL: " + longUrl + " for shortCode: " + shortCode);
            // Simulate publishing to Kafka
            System.out.println("Redirect Service: Publishing click event to Kafka (simulated) for " + shortCode);
            return ServerResponse.temporaryRedirect(URI.create(longUrl)).build();
        } else {
            System.out.println("Redirect Service: No URL found for shortCode: " + shortCode);
            return ServerResponse.notFound().build();
        }
    }
}
