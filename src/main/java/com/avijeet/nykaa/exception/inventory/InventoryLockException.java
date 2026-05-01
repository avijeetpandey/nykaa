package com.avijeet.nykaa.exception.inventory;

public class InventoryLockException extends RuntimeException {
    public InventoryLockException(String message) {
        super(message);
    }
}
