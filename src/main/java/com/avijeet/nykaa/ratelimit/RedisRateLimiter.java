package com.avijeet.nykaa.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis token-bucket rate limiter backed by a Lua script for atomicity.
 *
 * Each key holds two hash fields — 'tokens' (current fill) and 'ts'
 * (last-refill timestamp in ms). The Lua script refills tokens
 * proportionally to elapsed time and then either consumes one token
 * (returns 1 = allowed) or rejects (returns 0 = denied).
 *
 * Using a Lua script guarantees that the read-modify-write is atomic
 * — no distributed locking needed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "nykaa:ratelimit:";

    /**
     * Lua script: token bucket refill + consume.
     *
     * KEYS[1]  — bucket key
     * ARGV[1]  — capacity (integer)
     * ARGV[2]  — refill rate in tokens per millisecond (decimal string, e.g. "0.00167")
     * ARGV[3]  — current epoch ms
     *
     * Returns 1 if the request is allowed, 0 if rate-limited.
     */
    private static final RedisScript<Long> TOKEN_BUCKET_SCRIPT = RedisScript.of(
            """
            local key          = KEYS[1]
            local capacity     = tonumber(ARGV[1])
            local rate_per_ms  = tonumber(ARGV[2])
            local now          = tonumber(ARGV[3])

            local data      = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens    = tonumber(data[1]) or capacity
            local last_ts   = tonumber(data[2]) or now

            -- Refill proportionally to elapsed time
            local elapsed   = math.max(0, now - last_ts)
            local refill    = elapsed * rate_per_ms
            tokens          = math.min(capacity, tokens + refill)

            local allowed = 0
            if tokens >= 1 then
                tokens  = tokens - 1
                allowed = 1
            end

            redis.call('HMSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))

            -- TTL: time to refill a completely empty bucket
            local ttl_ms = math.ceil(capacity / rate_per_ms)
            redis.call('PEXPIRE', key, ttl_ms)

            return allowed
            """,
            Long.class
    );

    /**
     * @param identifier    per-user or per-IP string (the bucket key suffix)
     * @param endpoint      logical endpoint name included in the Redis key to isolate limits
     * @param capacity      max burst tokens
     * @param refillPerMinute tokens replenished per minute (sustained throughput)
     * @return true if the request is within the rate limit, false if it should be rejected
     */
    public boolean allowRequest(String identifier, String endpoint, int capacity, int refillPerMinute) {
        String key = KEY_PREFIX + endpoint + ":" + identifier;
        double ratePerMs = (double) refillPerMinute / 60_000.0;
        long nowMs = System.currentTimeMillis();

        try {
            Long result = stringRedisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(ratePerMs),
                    String.valueOf(nowMs)
            );
            return Long.valueOf(1L).equals(result);
        } catch (Exception ex) {
            // Fail open: if Redis is unavailable, allow the request rather than block all traffic
            log.error("[RATELIMIT] Redis error for key '{}': {}. Failing open.", key, ex.getMessage());
            return true;
        }
    }
}
