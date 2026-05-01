package com.avijeet.nykaa.repository.product;

import com.avijeet.nykaa.entities.product.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
