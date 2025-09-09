const { trace } = require('@opentelemetry/api');
const redisClient = require('../redis-client');

// Placeholder token validation - in production would validate with Keycloak
const validateAndExtractClaims = (token) => {
    // Mock claims for demo
    // In production: validate JWT and extract real claims
    return {
        sub: 'user-123',
        tenant_id: 'tenant-456', 
        email: 'demo@example.com',
        groups: ['users', 'admin'],
        scopes: ['create:links', 'read:links'],
        exp: Math.floor(Date.now() / 1000) + (15 * 60) // 15 minutes from now
    };
};

// Context establishment middleware
const establishContext = async (req, res, next) => {
    try {
        const span = trace.getActiveSpan();
        
        // Extract token from Authorization header
        const authHeader = req.headers.authorization;
        const token = authHeader?.replace('Bearer ', '');
        
        // Default context
        let context = {
            tenant_id: 'default',
            user_id: 'anonymous',
            service_name: 'bff',
            transaction_name: `${req.method} ${req.path}`
        };
        
        if (token) {
            // Try to get from cache first
            const cacheKey = `token:${token.substring(0, 16)}`; // Use token prefix as key
            
            try {
                // Connect Redis if needed
                if (!redisClient.isConnected) {
                    await redisClient.connect();
                }
                
                let cachedClaims = await redisClient.get(cacheKey);
                
                if (cachedClaims) {
                    // Cache hit
                    context = JSON.parse(cachedClaims);
                    span?.setAttribute('cache.token.hit', true);
                    console.log('Context cache hit for user:', context.user_id);
                } else {
                    // Cache miss - validate token and extract claims
                    span?.setAttribute('cache.token.hit', false);
                    const claims = validateAndExtractClaims(token);
                    
                    // Build context from claims
                    context = {
                        tenant_id: claims.tenant_id || 'default',
                        user_id: claims.sub,
                        user_email: claims.email,
                        user_groups: claims.groups?.join(',') || '',
                        user_scopes: claims.scopes?.join(',') || '',
                        service_name: 'bff',
                        transaction_name: `${req.method} ${req.path}`
                    };
                    
                    // Cache the context with TTL matching token expiration
                    const ttl = Math.max(1, claims.exp - Math.floor(Date.now() / 1000));
                    await redisClient.set(cacheKey, JSON.stringify(context), ttl);
                    console.log('Context cached for user:', context.user_id, 'TTL:', ttl);
                }
            } catch (redisError) {
                // If Redis fails, continue without caching
                console.warn('Redis error, continuing without cache:', redisError.message);
                const claims = validateAndExtractClaims(token);
                context = {
                    tenant_id: claims.tenant_id || 'default',
                    user_id: claims.sub,
                    user_email: claims.email,
                    user_groups: claims.groups?.join(',') || '',
                    user_scopes: claims.scopes?.join(',') || '',
                    service_name: 'bff',
                    transaction_name: `${req.method} ${req.path}`
                };
            }
        }
        
        // Establish standard headers
        req.headers['x-tenant-id'] = context.tenant_id;
        req.headers['x-user-id'] = context.user_id;
        req.headers['x-user-email'] = context.user_email || '';
        req.headers['x-user-groups'] = context.user_groups || '';
        req.headers['x-service-name'] = context.service_name;
        req.headers['x-transaction-name'] = context.transaction_name;
        req.headers['x-correlation-id'] = req.headers['x-correlation-id'] || 
                                          req.headers['x-request-id'] || 
                                          trace.getActiveSpan()?.spanContext().traceId;
        
        // Add context to trace span
        if (span) {
            span.setAttributes({
                'user.id': context.user_id,
                'user.tenant_id': context.tenant_id,
                'user.email': context.user_email || '',
                'user.groups': context.user_groups || '',
                'service.name': context.service_name,
                'transaction.name': context.transaction_name
            });
        }
        
        // Store context in request for later use
        req.userContext = context;
        
        // Log context establishment
        console.log(`Context established - User: ${context.user_id}, Tenant: ${context.tenant_id}, Transaction: ${context.transaction_name}`);
        
        next();
    } catch (error) {
        console.error('Error establishing context:', error);
        // Continue without context on error
        next();
    }
};

// Authorization check middleware (simplified)
const checkAuthorization = (requiredScope) => {
    return (req, res, next) => {
        // For demo purposes, always allow access
        // In production, this would properly check scopes from JWT
        console.log(`Authorization check (demo mode) - Required scope: ${requiredScope}`);
        
        const userScopes = req.headers['x-user-scopes'] || '';
        
        // In demo mode, always pass authorization
        // Log what would have been checked
        if (userScopes) {
            console.log(`User scopes: ${userScopes}`);
        } else {
            console.log('No scopes present (anonymous user) - allowing for demo');
        }
        
        next();
    };
};

module.exports = {
    establishContext,
    checkAuthorization
};