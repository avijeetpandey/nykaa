package com.avijeet.nykaa.repository.elasticsearch;

import com.avijeet.nykaa.entities.product.ProductDocument;
import com.avijeet.nykaa.enums.Brand;
import com.avijeet.nykaa.enums.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductSearchRepository extends ElasticsearchRepository<ProductDocument, String> {
    Page<ProductDocument> findByNameContainingIgnoreCase(String name, Pageable pageable);
    Page<ProductDocument> findByCategory(Category category, Pageable pageable);
    Page<ProductDocument> findByBrand(Brand brand, Pageable pageable);
}
