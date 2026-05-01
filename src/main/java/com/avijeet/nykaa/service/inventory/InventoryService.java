package com.avijeet.nykaa.service.inventory;

import com.avijeet.nykaa.exception.ProductNotFoundException;
import com.avijeet.nykaa.exception.inventory.InsufficientStockException;
import com.avijeet.nykaa.exception.inventory.InventoryLockException;
import com.avijeet.nykaa.repository.product.ProductRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductRepository productRepository;
    private final Tracer tracer;

    // Self-injection via proxy so @Transactional on deductStock / rollbackStock
    // is correctly intercepted when called from the non-transactional reserveStock.
    @Autowired
    @Lazy
    private InventoryService self;

    private static final String LOCK_PREFIX    = "nykaa:lock:inventory:";
    private static final Duration LOCK_TTL     = Duration.ofSeconds(10);
    private static final int     MAX_RETRIES   = 3;
    private static final long    RETRY_DELAY_MS = 100;

    /**
     * Acquires a Redis distributed lock for the product, then deducts stock
     * atomically via SELECT … FOR UPDATE inside a database transaction.
     * Releases the lock in the finally block regardless of outcome.
     */
    public void reserveStock(Long productId, int quantity) {
        Span span = tracer.nextSpan().name("inventory.reserve").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            span.tag("product.id", String.valueOf(productId));
            span.tag("quantity", String.valueOf(quantity));

            String lockKey = LOCK_PREFIX + productId;
            if (!acquireLock(lockKey)) {
                throw new InventoryLockException(
                        "Could not acquire inventory lock for product " + productId +
                        " after " + MAX_RETRIES + " attempts");
            }
            try {
                self.deductStock(productId, quantity);
            } finally {
                redisTemplate.delete(lockKey);
                log.debug("Released inventory lock for product {}", productId);
            }
        } catch (Exception ex) {
            span.error(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * Restores stock — used as a compensating transaction when the order saga fails.
     * No Redis lock needed: adding stock cannot cause overselling.
     */
    @Transactional
    public void rollbackStock(Long productId, int quantity) {
        var product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found: " + productId));
        product.setStockQuantity(product.getStockQuantity() + quantity);
        productRepository.save(product);
        log.info("Rolled back {} unit(s) for product {}. New stock: {}",
                quantity, productId, product.getStockQuantity());
    }

    @Transactional
    public void deductStock(Long productId, int quantity) {
        var product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found: " + productId));

        if (product.getStockQuantity() < quantity) {
            throw new InsufficientStockException(String.format(
                    "Insufficient stock for product %d. Requested: %d, Available: %d",
                    productId, quantity, product.getStockQuantity()));
        }

        product.setStockQuantity(product.getStockQuantity() - quantity);
        productRepository.save(product);
        log.info("Deducted {} unit(s) of product {}. Remaining: {}",
                quantity, productId, product.getStockQuantity());
    }

    private boolean acquireLock(String lockKey) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Acquired lock {} on attempt {}", lockKey, attempt);
                return true;
            }
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
