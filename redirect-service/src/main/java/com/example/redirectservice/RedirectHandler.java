package com.example.redirectservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import java.net.URI;

@Component
public class RedirectHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(RedirectHandler.class);
    private final LinkRepository linkRepository;

    public RedirectHandler(LinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    public Mono<ServerResponse> handleRedirect(ServerRequest request) {
        String shortCode = request.pathVariable("shortCode");
        logger.info("Processing redirect request for short code: {}", shortCode);

        return linkRepository.findById(shortCode)
                .flatMap(link -> {
                    logger.info("Successfully resolved short code {} to URL: {}", shortCode, link.getLongUrl());
                    // TODO: Publish click event to Kafka
                    logger.debug("Publishing click event to Kafka for short code: {}", shortCode);
                    return ServerResponse.temporaryRedirect(URI.create(link.getLongUrl())).build();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    logger.warn("No URL found for short code: {}", shortCode);
                    return ServerResponse.notFound().build();
                }));
    }
}
