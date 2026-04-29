package com.avijeet.nykaa.repository;

import com.avijeet.nykaa.entities.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
