package com.avijeet.nykaa.dto.cdc;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Maps the Debezium envelope for the products table.
 * op values: c=create, u=update, d=delete, r=read (snapshot)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductCdcEvent {
    private ProductCdcPayload before;
    private ProductCdcPayload after;
    private String op;

    @JsonProperty("ts_ms")
    private Long tsMs;
}
