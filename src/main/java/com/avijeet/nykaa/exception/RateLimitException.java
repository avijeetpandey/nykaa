package com.avijeet.nykaa.exception;

import lombok.Getter;

@Getter
public class RateLimitException extends RuntimeException {
    private final String identifier;
    private final int capacity;

    public RateLimitException(String identifier, int capacity) {
        super("Rate limit exceeded for '" + identifier + "'. Max " + capacity + " requests per minute.");
        this.identifier = identifier;
        this.capacity = capacity;
    }
}
