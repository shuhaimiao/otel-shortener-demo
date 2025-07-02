package com.example.analyticsapi;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaListeners {

    private static final String URL_CREATIONS_TOPIC = "url-creations";
    private static final String URL_CLICKS_TOPIC = "url-clicks";
    private static final String GROUP_ID = "analytics-group";

    @KafkaListener(topics = URL_CREATIONS_TOPIC, groupId = GROUP_ID)
    void listenUrlCreations(String message) {
        System.out.println("Analytics API: Received message from '" + URL_CREATIONS_TOPIC + "': " + message);
        // TODO: Process the message
        // 1. Parse the JSON payload
        // 2. Store/update analytics data (e.g., in a separate table or a data warehouse)
        //    For this demo, we might just log it or store it in a simple in-memory structure if needed.
        //    The README doesn't specify a DB for analytics-api, so logging is the primary action.
    }

    @KafkaListener(topics = URL_CLICKS_TOPIC, groupId = GROUP_ID)
    void listenUrlClicks(String message) {
        System.out.println("Analytics API: Received message from '" + URL_CLICKS_TOPIC + "': " + message);
        // TODO: Process the message
        // 1. Parse the JSON payload
        // 2. Store/update click analytics data (e.g., in the 'clicks' table in Postgres, or another store)
        //    The README mentions a 'clicks' table in PostgreSQL. This service could write to it.
    }
}
