package com.avijeet.nykaa.service.product;

import com.avijeet.nykaa.dto.product.PageResponse;
import com.avijeet.nykaa.dto.product.ProductRequestDto;
import com.avijeet.nykaa.dto.product.ProductResponseDto;
import com.avijeet.nykaa.dto.product.ProductUpdateDto;
import com.avijeet.nykaa.entities.product.Product;
import com.avijeet.nykaa.entities.product.ProductDocument;
import com.avijeet.nykaa.enums.Brand;
import com.avijeet.nykaa.enums.Category;
import com.avijeet.nykaa.exception.ProductNotFoundException;
import com.avijeet.nykaa.repository.elasticsearch.ProductSearchRepository;
import com.avijeet.nykaa.repository.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// Elasticsearch is kept in sync via the Debezium CDC pipeline (ProductCdcConsumer).
// ProductService only writes to PostgreSQL; search reads go to Elasticsearch directly.

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductSearchRepository productSearchRepository; // read-only: search queries only

    @Transactional
    public ProductResponseDto addProduct(ProductRequestDto dto) {
        log.info("Adding product: name={}, brand={}, category={}", dto.name(), dto.brand(), dto.category());
        try {
            Product product = Product.builder()
                    .name(dto.name())
                    .brand(dto.brand())
                    .category(dto.category())
                    .price(dto.price())
                    .stockQuantity(dto.stockQuantity() != null ? dto.stockQuantity() : 100)
                    .build();

            Product saved = productRepository.save(product);
            log.info("Product saved: id={}. Elasticsearch will be synced via CDC.", saved.getId());
            return mapToResponseDto(saved);
        } catch (Exception e) {
            log.error("Error adding product: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to add product", e);
        }
    }

    @Transactional
    public ProductResponseDto updateProduct(ProductUpdateDto dto) {
        log.info("Updating product id={}", dto.id());
        Product product = productRepository.findById(dto.id())
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + dto.id()));
        try {
            if (dto.name() != null && !dto.name().isEmpty()) {
                product.setName(dto.name());
            }
            if (dto.brand() != null && !dto.brand().isEmpty()) {
                product.setBrand(Brand.valueOf(dto.brand()));
            }
            if (dto.category() != null && !dto.category().isEmpty()) {
                product.setCategory(Category.valueOf(dto.category()));
            }
            if (dto.price() != null && !dto.price().isNaN()) {
                product.setPrice(dto.price());
            }
            if (dto.stockQuantity() != null) {
                product.setStockQuantity(dto.stockQuantity());
            }

            Product saved = productRepository.save(product);
            log.info("Product updated: id={}. Elasticsearch will be synced via CDC.", saved.getId());
            return mapToResponseDto(saved);
        } catch (Exception e) {
            log.error("Error updating product id={}: {}", dto.id(), e.getMessage(), e);
            throw new RuntimeException("Failed to update product", e);
        }
    }

    @Transactional
    public void deleteProduct(Long productId) {
        log.info("Deleting product id={}", productId);
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException("Product not found with id: " + productId);
        }
        try {
            productRepository.deleteById(productId);
            log.info("Product deleted: id={}. Elasticsearch removal will be synced via CDC.", productId);
        } catch (Exception e) {
            log.error("Error deleting product id={}: {}", productId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete product", e);
        }
    }

    public PageResponse<ProductResponseDto> getAllProducts(int pageNo, int pageSize, String sortBy, String sortDir) {
        log.info("Fetching products page={}, size={}, sort={}:{}", pageNo, pageSize, sortBy, sortDir);
        try {
            Sort sort = sortDir.equalsIgnoreCase(Sort.Direction.ASC.name())
                    ? Sort.by(sortBy).ascending()
                    : Sort.by(sortBy).descending();
            Page<Product> page = productRepository.findAll(PageRequest.of(pageNo, pageSize, sort));
            List<ProductResponseDto> content = page.getContent().stream().map(this::mapToResponseDto).toList();
            return new PageResponse<>(content, page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages(), page.isLast());
        } catch (Exception e) {
            log.error("Error fetching products: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch products", e);
        }
    }

    public PageResponse<ProductResponseDto> searchProducts(String name, String category, String brand,
                                                            int pageNo, int pageSize, String sortBy, String sortDir) {
        log.info("Searching products: name={}, category={}, brand={}", name, category, brand);
        try {
            Sort sort = sortDir.equalsIgnoreCase(Sort.Direction.ASC.name())
                    ? Sort.by(sortBy).ascending()
                    : Sort.by(sortBy).descending();
            Pageable pageable = PageRequest.of(pageNo, pageSize, sort);
            Page<ProductDocument> results;

            if (name != null && !name.trim().isEmpty()) {
                results = productSearchRepository.findByNameContainingIgnoreCase(name, pageable);
            } else if (category != null && !category.trim().isEmpty()) {
                results = productSearchRepository.findByCategory(Category.valueOf(category.toUpperCase()), pageable);
            } else if (brand != null && !brand.trim().isEmpty()) {
                results = productSearchRepository.findByBrand(Brand.valueOf(brand.toUpperCase()), pageable);
            } else {
                results = productSearchRepository.findAll(pageable);
            }

            List<ProductResponseDto> content = results.getContent().stream().map(this::mapDocumentToResponseDto).toList();
            return new PageResponse<>(content, results.getNumber(), results.getSize(),
                    results.getTotalElements(), results.getTotalPages(), results.isLast());
        } catch (Exception e) {
            log.error("Error searching products: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to search products", e);
        }
    }

    @Transactional
    public List<ProductResponseDto> addBulkProducts(List<ProductRequestDto> dtos) {
        log.info("Bulk adding {} products", dtos.size());
        try {
            List<Product> saved = productRepository.saveAll(dtos.stream().map(this::mapToEntity).toList());
            log.info("Bulk added {} products. Elasticsearch will be synced via CDC.", saved.size());
            return saved.stream().map(this::mapToResponseDto).toList();
        } catch (Exception e) {
            log.error("Error bulk adding products: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to add bulk products", e);
        }
    }

    private ProductResponseDto mapToResponseDto(Product p) {
        return new ProductResponseDto(p.getId(), p.getName(), p.getCategory().name(),
                p.getBrand().name(), p.getPrice(), p.getStockQuantity(), p.getCreatedAt(), p.getUpdatedAt());
    }

    private ProductResponseDto mapDocumentToResponseDto(ProductDocument doc) {
        return new ProductResponseDto(Long.parseLong(doc.getId()), doc.getName(),
                doc.getCategory().name(), doc.getBrand().name(), doc.getPrice(), null, null, null);
    }

    private Product mapToEntity(ProductRequestDto dto) {
        return Product.builder()
                .name(dto.name())
                .price(dto.price())
                .category(Category.valueOf(String.valueOf(dto.category())))
                .brand(Brand.valueOf(String.valueOf(dto.brand())))
                .stockQuantity(dto.stockQuantity() != null ? dto.stockQuantity() : 100)
                .build();
    }

    private ProductDocument mapToDocument(Product p) {
        return ProductDocument.builder()
                .id(String.valueOf(p.getId()))
                .name(p.getName())
                .category(p.getCategory())
                .brand(p.getBrand())
                .price(p.getPrice())
                .build();
    }
}
