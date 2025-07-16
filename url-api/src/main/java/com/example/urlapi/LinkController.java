package com.example.urlapi;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/links")
public class LinkController {

    private final LinkRepository linkRepository;

    public LinkController(LinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    private static final String ALPHANUMERIC_STRING = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SHORT_CODE_LENGTH = 6;

    public static String generateShortCode() {
        StringBuilder builder = new StringBuilder();
        Random random = ThreadLocalRandom.current();
        for (int i = 0; i < SHORT_CODE_LENGTH; i++) {
            builder.append(ALPHANUMERIC_STRING.charAt(random.nextInt(ALPHANUMERIC_STRING.length())));
        }
        return builder.toString();
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createLink(@RequestBody LinkCreationRequest request,
                                                          @RequestHeader(name = "X-User-ID", required = false) String userId,
                                                          @RequestHeader(name = "X-Tenant-ID", required = false) String tenantId,
                                                          @RequestHeader(name = "Authorization") String authorization) {
        // Basic M2M token validation placeholder
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            System.out.println("URL API: Missing or invalid Authorization header.");
            // In a real app, this would be handled by Spring Security and a proper JWT validation library
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        String token = authorization.substring(7);
        // TODO: Add actual M2M token validation logic here (e.g., using Spring Security OAuth2 Resource Server)
        System.out.println("URL API: Received M2M token (first 10 chars): " + (token.length() > 10 ? token.substring(0, 10) : token) + "...");


        System.out.println("URL API: Received request to create link for URL: " + request.url());
        System.out.println("URL API: User ID: " + userId + ", Tenant ID: " + tenantId);

        String shortCode = generateShortCode();

        Link newLink = new Link();
        newLink.setShortCode(shortCode);
        newLink.setLongUrl(request.url());
        newLink.setUserId(userId); // Use the userId from the header
        newLink.setCreatedAt(Instant.now());

        linkRepository.save(newLink);
        System.out.println("URL API: Saved new link with short code: " + shortCode);

        // Simulate response
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("shortCode", shortCode));
    }
}

record LinkCreationRequest(String url, String userId, String tenantId) {}
