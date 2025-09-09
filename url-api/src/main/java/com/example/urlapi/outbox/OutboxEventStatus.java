package com.example.urlapi.outbox;

/**
 * Status of an outbox event in its lifecycle.
 */
public enum OutboxEventStatus {
    /**
     * Event has been created but not yet processed by CDC
     */
    PENDING,
    
    /**
     * Event has been successfully processed by CDC
     * (This status is typically not set by CDC but by cleanup jobs)
     */
    PROCESSED,
    
    /**
     * Event processing failed and may need retry
     * (Used if implementing fallback polling mechanism)
     */
    FAILED
}