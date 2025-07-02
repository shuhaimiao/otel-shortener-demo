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
    -- ip_address was mentioned in Kafka payload but not in table schema in README.
    -- Add if needed: ip_address VARCHAR(45) NULL
);

-- Optional: Create a dedicated user and database for Keycloak if it uses the same Postgres instance
-- CREATE USER keycloak_user WITH PASSWORD 'keycloak_password';
-- CREATE DATABASE keycloak_db OWNER keycloak_user;
-- GRANT ALL PRIVILEGES ON DATABASE keycloak_db TO keycloak_user;

-- Grant privileges to the main application user on the tables
GRANT ALL PRIVILEGES ON TABLE links TO otel_user;
GRANT ALL PRIVILEGES ON TABLE clicks TO otel_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO otel_user;

-- Insert some dummy data for testing redirect service
INSERT INTO links (short_code, long_url, user_id) VALUES
('test01', 'https://opentelemetry.io/docs/', 'system-init'),
('test02', 'https://www.w3.org/TR/trace-context/', 'system-init')
ON CONFLICT (short_code) DO NOTHING;
