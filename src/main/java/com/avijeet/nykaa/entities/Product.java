package com.avijeet.nykaa.entities;

import com.avijeet.nykaa.enums.Brand;
import com.avijeet.nykaa.enums.Category;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.validator.constraints.URL;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    @NotBlank(message = "Product name cannot be empty")
    private String name;

    @Column(name = "price", nullable = false)
    @NotNull(message = "Price is required")
    @Positive(message = "Price must be greater than zero")
    private Double price;

    @Column(name = "imageUrl", nullable = true)
    @URL(message = "Please provide a valid url")
    private String imageUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = true, columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "category", nullable = false)
    @NotBlank(message = "Category is required")
    private Category category;

    @Column(name = "brand", nullable = false)
    @NotBlank(message = "Brand is required")
    private Brand brand;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
