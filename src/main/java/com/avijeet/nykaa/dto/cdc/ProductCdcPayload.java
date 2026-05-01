package com.avijeet.nykaa.dto.cdc;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductCdcPayload {
    private Long id;
    private String name;
    private Double price;
    private String category;
    private String brand;

    @JsonProperty("stock_quantity")
    private Integer stockQuantity;
}
