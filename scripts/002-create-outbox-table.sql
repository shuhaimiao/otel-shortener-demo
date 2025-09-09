-- Migration: Create outbox table for transactional event publishing with trace context
-- Author: System
-- Date: 2025-09-09
-- Purpose: Support CDC with Debezium while maintaining trace continuity

-- Drop table if exists (for development only)
DROP TABLE IF EXISTS outbox_events CASCADE;

-- Create outbox table with W3C trace context fields
CREATE TABLE outbox_events (
    -- Primary key
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Aggregate information
    aggregate_id VARCHAR(255) NOT NULL,      -- e.g., short_code for links
    aggregate_type VARCHAR(100) NOT NULL,    -- e.g., 'Link', 'User', 'Click'
    
    -- Event information
    event_type VARCHAR(100) NOT NULL,        -- e.g., 'LINK_CREATED', 'LINK_CLICKED', 'LINK_EXPIRED'
    payload JSONB NOT NULL,                  -- Event data as JSON
    
    -- W3C Trace Context fields (for trace propagation through CDC)
    trace_id VARCHAR(32),                    -- 32 hex characters (128-bit)
    parent_span_id VARCHAR(16),              -- 16 hex characters (64-bit)
    trace_flags VARCHAR(2),                  -- 2 hex characters (8-bit)
    trace_state TEXT,                        -- Optional vendor-specific trace state
    
    -- Metadata
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),                 -- User who triggered the event
    tenant_id VARCHAR(100),                  -- For multi-tenant support
    
    -- Processing status (fallback for polling if CDC fails)
    processed_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'PENDING',    -- PENDING, PROCESSED, FAILED
    retry_count INTEGER DEFAULT 0,
    error_message TEXT,
    
    -- Constraints
    CONSTRAINT check_status CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    CONSTRAINT check_retry_count CHECK (retry_count >= 0)
);

-- Indexes for query performance
CREATE INDEX idx_outbox_status_created 
    ON outbox_events(status, created_at) 
    WHERE status = 'PENDING';  -- Partial index for unprocessed events

CREATE INDEX idx_outbox_aggregate 
    ON outbox_events(aggregate_type, aggregate_id);

CREATE INDEX idx_outbox_event_type 
    ON outbox_events(event_type, created_at);

CREATE INDEX idx_outbox_trace_id 
    ON outbox_events(trace_id) 
    WHERE trace_id IS NOT NULL;  -- Partial index for traced events

-- Create a function to clean up old processed events (optional)
CREATE OR REPLACE FUNCTION cleanup_processed_outbox_events()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM outbox_events
    WHERE status = 'PROCESSED' 
    AND processed_at < NOW() - INTERVAL '7 days';
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Comment the table and columns for documentation
COMMENT ON TABLE outbox_events IS 'Transactional outbox for reliable event publishing with CDC';
COMMENT ON COLUMN outbox_events.trace_id IS 'W3C trace ID for distributed tracing continuity';
COMMENT ON COLUMN outbox_events.parent_span_id IS 'Parent span ID to link async operations to originating request';
COMMENT ON COLUMN outbox_events.payload IS 'Event payload in JSON format, structure depends on event_type';

-- Grant permissions (adjust as needed)
GRANT SELECT, INSERT, UPDATE ON outbox_events TO otel_user;
GRANT USAGE ON SEQUENCE outbox_events_id_seq TO otel_user;