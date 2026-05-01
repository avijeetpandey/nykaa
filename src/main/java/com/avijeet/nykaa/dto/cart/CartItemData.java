package com.avijeet.nykaa.dto.cart;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis-serialised cart item. Must be a non-final class (not a record) so that
 * Jackson's NON_FINAL DefaultTyping embeds @class metadata and can round-trip correctly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemData {
    private Long productId;
    private String productName;
    private Double price;
    private Integer quantity;
}
