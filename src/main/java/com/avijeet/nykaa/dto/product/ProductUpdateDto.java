package com.avijeet.nykaa.dto.product;

public record ProductUpdateDto(
        Long id,
        String name,
        String category,
        String brand,
        Double price
) {}
