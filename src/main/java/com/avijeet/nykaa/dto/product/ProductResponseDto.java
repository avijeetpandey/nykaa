package com.avijeet.nykaa.dto.product;

import java.time.LocalDateTime;

public record ProductResponseDto(
        Long id,
        String name,
        String category,
        String brand,
        Double price,
        Integer stockQuantity,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
