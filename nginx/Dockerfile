# Use NGINX with OpenTelemetry module pre-built
FROM nginx:1.25-alpine

# For now, we'll use standard NGINX and add trace initiation through headers
# In production, you would compile with OpenTelemetry module

# Copy configuration
COPY nginx.conf /etc/nginx/nginx.conf

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost/health || exit 1

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]