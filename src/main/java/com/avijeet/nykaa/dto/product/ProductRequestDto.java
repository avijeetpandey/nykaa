package com.avijeet.nykaa.dto.product;

import com.avijeet.nykaa.enums.Brand;
import com.avijeet.nykaa.enums.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ProductRequestDto(
        @NotBlank(message = "Product name cannot be empty")
        String name,

        @NotNull(message = "Brand is required")
        Brand brand,

        @NotNull(message = "Category is required")
        Category category,

        @NotNull(message = "Price is required")
        @Positive(message = "Price must be greater than zero")
        Double price
) {}
