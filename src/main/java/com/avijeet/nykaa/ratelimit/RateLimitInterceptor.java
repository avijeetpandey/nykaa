package com.avijeet.nykaa.ratelimit;

import com.avijeet.nykaa.annotation.RateLimit;
import com.avijeet.nykaa.exception.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int DEFAULT_CAPACITY       = 200;
    private static final int DEFAULT_REFILL_PER_MIN = 200;

    private final RedisRateLimiter rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }

        RateLimit annotation = method.getMethodAnnotation(RateLimit.class);
        int capacity       = annotation != null ? annotation.capacity()       : DEFAULT_CAPACITY;
        int refillPerMin   = annotation != null ? annotation.refillPerMinute() : DEFAULT_REFILL_PER_MIN;

        String identifier = resolveIdentifier(request);
        String endpoint   = method.getBeanType().getSimpleName() + "." + method.getMethod().getName();

        boolean allowed = rateLimiter.allowRequest(identifier, endpoint, capacity, refillPerMin);
        if (!allowed) {
            log.warn("[RATELIMIT] Rejected request from '{}' on endpoint '{}' (capacity={})",
                    identifier, endpoint, capacity);
            throw new RateLimitException(identifier, capacity);
        }

        return true;
    }

    private String resolveIdentifier(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        // Fall back to IP for unauthenticated callers (e.g. payment webhook)
        String forwarded = request.getHeader("X-Forwarded-For");
        return "ip:" + (forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr());
    }
}
