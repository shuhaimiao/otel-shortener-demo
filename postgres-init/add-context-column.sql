-- Add context column to outbox_events table for standard context propagation
-- As per desktop review decision: using JSONB for extensibility

ALTER TABLE outbox_events 
ADD COLUMN IF NOT EXISTS context JSONB;

-- Add index for common context queries (optional, for performance)
CREATE INDEX IF NOT EXISTS idx_outbox_context_user_id 
ON outbox_events ((context->>'user_id'));

CREATE INDEX IF NOT EXISTS idx_outbox_context_tenant_id 
ON outbox_events ((context->>'tenant_id'));

-- Comment for documentation
COMMENT ON COLUMN outbox_events.context IS 'Standard context headers as JSON: request_id, user_id, tenant_id, service_name, transaction_type';