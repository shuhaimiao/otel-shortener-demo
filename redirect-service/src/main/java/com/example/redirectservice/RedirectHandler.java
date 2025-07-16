package com.example.redirectservice;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import java.net.URI;

@Component
public class RedirectHandler {

    private final LinkRepository linkRepository;

    public RedirectHandler(LinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    public Mono<ServerResponse> handleRedirect(ServerRequest request) {
        String shortCode = request.pathVariable("shortCode");
        System.out.println("Redirect Service: Received request for shortCode: " + shortCode);

        return linkRepository.findById(shortCode)
                .flatMap(link -> {
                    System.out.println("Redirect Service: Found long URL: " + link.getLongUrl() + " for shortCode: " + shortCode);
                    // TODO: Publish click event to Kafka
                    System.out.println("Redirect Service: Publishing click event to Kafka (simulated) for " + shortCode);
                    return ServerResponse.temporaryRedirect(URI.create(link.getLongUrl())).build();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    System.out.println("Redirect Service: No URL found for shortCode: " + shortCode);
                    return ServerResponse.notFound().build();
                }));
    }
}
