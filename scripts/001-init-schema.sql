-- Initial schema setup for URL shortener with outbox pattern
-- This script combines the original schema with the outbox table

-- Create the 'links' table as per README.md
CREATE TABLE IF NOT EXISTS links (
    short_code VARCHAR(16) PRIMARY KEY,
    long_url TEXT NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create the 'clicks' table as per README.md
CREATE TABLE IF NOT EXISTS clicks (
    id BIGSERIAL PRIMARY KEY,
    link_short_code VARCHAR(16) REFERENCES links(short_code),
    clicked_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    user_agent TEXT NULL
);

-- Create outbox table for transactional event publishing with trace context
CREATE TABLE IF NOT EXISTS outbox_events (
    -- Primary key
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Aggregate information
    aggregate_id VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    
    -- Event information
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    
    -- W3C Trace Context fields
    trace_id VARCHAR(32),
    parent_span_id VARCHAR(16),
    trace_flags VARCHAR(2),
    trace_state TEXT,
    
    -- Metadata
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    tenant_id VARCHAR(100),
    
    -- Processing status
    processed_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'PENDING',
    retry_count INTEGER DEFAULT 0,
    error_message TEXT,
    
    -- Constraints
    CONSTRAINT check_status CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    CONSTRAINT check_retry_count CHECK (retry_count >= 0)
);

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_outbox_status_created 
    ON outbox_events(status, created_at) 
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate 
    ON outbox_events(aggregate_type, aggregate_id);

CREATE INDEX IF NOT EXISTS idx_outbox_event_type 
    ON outbox_events(event_type, created_at);

CREATE INDEX IF NOT EXISTS idx_outbox_trace_id 
    ON outbox_events(trace_id) 
    WHERE trace_id IS NOT NULL;

-- Grant privileges to the application user
GRANT ALL PRIVILEGES ON TABLE links TO otel_user;
GRANT ALL PRIVILEGES ON TABLE clicks TO otel_user;
GRANT ALL PRIVILEGES ON TABLE outbox_events TO otel_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO otel_user;

-- Grant replication privileges for CDC
ALTER USER otel_user REPLICATION;

-- Insert some test data
INSERT INTO links (short_code, long_url, user_id) VALUES
('test01', 'https://opentelemetry.io/docs/', 'system-init'),
('test02', 'https://www.w3.org/TR/trace-context/', 'system-init'),
('demo01', 'https://debezium.io/', 'system-init')
ON CONFLICT (short_code) DO NOTHING;