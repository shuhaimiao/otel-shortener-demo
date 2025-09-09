package com.example.urlapi;

import com.example.urlapi.outbox.OutboxEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/links")
public class LinkController {
    
    private static final Logger logger = LoggerFactory.getLogger(LinkController.class);
    private final LinkRepository linkRepository;
    private final OutboxEventService outboxEventService;

    public LinkController(LinkRepository linkRepository, OutboxEventService outboxEventService) {
        this.linkRepository = linkRepository;
        this.outboxEventService = outboxEventService;
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
    @Transactional
    public ResponseEntity<Map<String, String>> createLink(@RequestBody LinkCreationRequest request,
                                                          @RequestHeader(name = "X-User-ID", required = false) String userId,
                                                          @RequestHeader(name = "X-Tenant-ID", required = false) String tenantId,
                                                          @RequestHeader(name = "Authorization") String authorization) {
        // Basic M2M token validation placeholder
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            logger.warn("Missing or invalid Authorization header");
            // In a real app, this would be handled by Spring Security and a proper JWT validation library
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        String token = authorization.substring(7);
        // TODO: Add actual M2M token validation logic here (e.g., using Spring Security OAuth2 Resource Server)
        logger.debug("Received M2M token (first 10 chars): {}", 
                    token.length() > 10 ? token.substring(0, 10) + "..." : token);

        // MDC context is already established by MdcContextFilter
        logger.info("Creating short link for URL: {}", request.url());

        String shortCode = generateShortCode();

        // Save link to database
        Link newLink = new Link();
        newLink.setShortCode(shortCode);
        newLink.setLongUrl(request.url());
        newLink.setUserId(userId); // Use the userId from the header
        newLink.setCreatedAt(Instant.now());
        linkRepository.save(newLink);
        
        // Create outbox event for CDC (critical business event)
        // This will be atomically committed with the link creation
        outboxEventService.createLinkCreatedEvent(
            shortCode, 
            request.url(), 
            userId != null ? userId : "anonymous"
        );
        
        logger.info("Successfully created short link with code: {} and outbox event", shortCode);

        // Simulate response
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("shortCode", shortCode));
    }
}

record LinkCreationRequest(String url, String userId, String tenantId) {}
