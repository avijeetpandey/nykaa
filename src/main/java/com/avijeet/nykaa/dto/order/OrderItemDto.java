package com.avijeet.nykaa.dto.order;

import lombok.Builder;

@Builder
public record OrderItemDto(
        Long productId,
        String productName,
        Integer quantity,
        Double priceAtPurchase
) {}
