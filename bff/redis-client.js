const redis = require('redis');
const { trace } = require('@opentelemetry/api');

class TracedRedisClient {
    constructor() {
        this.client = null;
        this.tracer = trace.getTracer('redis-cache');
        this.isConnected = false;
    }

    async connect() {
        if (this.isConnected) return;
        
        const redisUrl = process.env.REDIS_URL || 'redis://localhost:6379';
        console.log('Connecting to Redis at:', redisUrl);
        
        this.client = redis.createClient({
            url: redisUrl,
            socket: {
                reconnectStrategy: (retries) => {
                    if (retries > 10) {
                        console.error('Redis: Max reconnection attempts reached');
                        return new Error('Max reconnection attempts reached');
                    }
                    const delay = Math.min(retries * 100, 3000);
                    console.log(`Redis: Reconnecting in ${delay}ms...`);
                    return delay;
                }
            }
        });

        this.client.on('error', (err) => {
            console.error('Redis Client Error:', err);
            this.isConnected = false;
        });

        this.client.on('connect', () => {
            console.log('Redis Client Connected');
            this.isConnected = true;
        });

        await this.client.connect();
    }

    async get(key) {
        const span = this.tracer.startSpan('cache.get', {
            attributes: {
                'cache.operation': 'get',
                'cache.key': key,
                'cache.system': 'redis'
            }
        });

        try {
            if (!this.isConnected) {
                await this.connect();
            }
            
            const value = await this.client.get(key);
            const hit = value !== null;
            
            span.setAttribute('cache.hit', hit);
            if (hit) {
                span.setAttribute('cache.item_size', value.length);
            }
            
            return value;
        } catch (error) {
            span.recordException(error);
            span.setStatus({ code: 1, message: error.message });
            throw error;
        } finally {
            span.end();
        }
    }

    async set(key, value, ttlSeconds = 900) {
        const span = this.tracer.startSpan('cache.set', {
            attributes: {
                'cache.operation': 'set',
                'cache.key': key,
                'cache.ttl': ttlSeconds,
                'cache.system': 'redis'
            }
        });

        try {
            if (!this.isConnected) {
                await this.connect();
            }
            
            const result = await this.client.setEx(key, ttlSeconds, value);
            span.setAttribute('cache.set.success', result === 'OK');
            return result;
        } catch (error) {
            span.recordException(error);
            span.setStatus({ code: 1, message: error.message });
            throw error;
        } finally {
            span.end();
        }
    }

    async delete(key) {
        const span = this.tracer.startSpan('cache.delete', {
            attributes: {
                'cache.operation': 'delete',
                'cache.key': key,
                'cache.system': 'redis'
            }
        });

        try {
            if (!this.isConnected) {
                await this.connect();
            }
            
            const result = await this.client.del(key);
            span.setAttribute('cache.delete.count', result);
            return result;
        } catch (error) {
            span.recordException(error);
            span.setStatus({ code: 1, message: error.message });
            throw error;
        } finally {
            span.end();
        }
    }

    async close() {
        if (this.client && this.isConnected) {
            await this.client.quit();
            this.isConnected = false;
        }
    }
}

// Export singleton instance
const redisClient = new TracedRedisClient();
module.exports = redisClient;