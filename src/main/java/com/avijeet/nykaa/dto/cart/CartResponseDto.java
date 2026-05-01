package com.avijeet.nykaa.dto.cart;

import lombok.Builder;

import java.util.List;

@Builder
public record CartResponseDto(
        Long userId,
        List<CartItemData> items,
        Double totalAmount
) {}
