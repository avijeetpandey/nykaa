package com.avijeet.nykaa.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies a Redis token-bucket rate limit to a controller method.
 * capacity        — max burst size (bucket depth)
 * refillPerMinute — steady-state throughput; tokens added per minute
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimit {
    int capacity() default 200;
    int refillPerMinute() default 200;
}
