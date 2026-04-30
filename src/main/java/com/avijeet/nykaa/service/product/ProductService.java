package com.avijeet.nykaa.service.product;

import com.avijeet.nykaa.dto.product.PageResponse;
import com.avijeet.nykaa.dto.product.ProductRequestDto;
import com.avijeet.nykaa.dto.product.ProductResponseDto;
import com.avijeet.nykaa.dto.product.ProductUpdateDto;
import com.avijeet.nykaa.entities.product.Product;
import com.avijeet.nykaa.enums.Brand;
import com.avijeet.nykaa.enums.Category;
import com.avijeet.nykaa.exception.ProductNotFoundException;
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


@Service
@Slf4j
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;

    @Transactional
    public ProductResponseDto addProduct(ProductRequestDto productRequestDto) {
        log.info("Adding product: name={}, brand={}, category={}", productRequestDto.name(), productRequestDto.brand(), productRequestDto.category());

        try {
            Product product = Product.builder()
                    .name(productRequestDto.name())
                    .brand(productRequestDto.brand())
                    .category(productRequestDto.category())
                    .price(productRequestDto.price())
                    .build();

            Product savedProduct = productRepository.save(product);

            log.info("Product saved successfully: id={}", savedProduct.getId());
            return mapToResponseDto(savedProduct);
        } catch (Exception e) {
            log.error("Error occurred while adding product: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to add product", e);
        }
    }

    @Transactional
    public ProductResponseDto updateProduct(ProductUpdateDto productUpdateDto) {
        log.info("Attempting to update product with id: {}", productUpdateDto.id());
        Product existingProduct = productRepository.findById(productUpdateDto.id())
                .orElseThrow(() -> {
                    log.error("Product not found with id: {}", productUpdateDto.id());
                    return new ProductNotFoundException("Product not found with id: " + productUpdateDto.id());
                });

        try {
            if(!productUpdateDto.name().isEmpty()) {
                existingProduct.setName(productUpdateDto.name());
            }

            if(!productUpdateDto.brand().isEmpty()) {
                existingProduct.setBrand(Brand.valueOf(productUpdateDto.brand()));
            }

            if(!productUpdateDto.category().isEmpty()) {
                existingProduct.setCategory(Category.valueOf(productUpdateDto.category()));
            }

            if(!productUpdateDto.price().isNaN()) {
                existingProduct.setPrice(productUpdateDto.price());
            }

            Product savedProduct = productRepository.save(existingProduct);
            log.info("Product updated successfully: id={}", savedProduct.getId());

            return mapToResponseDto(savedProduct);
        } catch (Exception e) {
            log.error("Error occurred while updating product id {}: {}", productUpdateDto.id(), e.getMessage(), e);
            throw new RuntimeException("Failed to update product", e);
        }
    }

    @Transactional
    public void deleteProduct(Long productId) {
        log.info("Attempting to delete product with id: {}", productId);
        if(!productRepository.existsById(productId)) {
            log.error("Product not found with id: {}", productId);
            throw new ProductNotFoundException("Product not found with id: " + productId);
        }
        
        try {
            productRepository.deleteById(productId);
            log.info("Product deleted successfully: id={}", productId);
        } catch (Exception e) {
            log.error("Error occurred while deleting product id {}: {}", productId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete product", e);
        }
    }

    public PageResponse<ProductResponseDto> getAllProducts(int pageNo, int pageSize, String sortBy, String sortDir) {
        log.info("Fetching all products with pageNo: {}, pageSize: {}, sortBy: {}, sortDir: {}", pageNo, pageSize, sortBy, sortDir);
        try {
            Sort sort = sortDir.equalsIgnoreCase(Sort.Direction.ASC.name())
                    ? Sort.by(sortBy).ascending()
                    : Sort.by(sortBy).descending();

            Pageable pageable = PageRequest.of(pageNo, pageSize, sort);

            Page<Product> productPage = productRepository.findAll(pageable);

            List<ProductResponseDto> content = productPage.getContent().stream()
                    .map(this::mapToResponseDto)
                    .toList();

            log.info("Fetched {} products", content.size());
            return new PageResponse<>(
                    content,
                    productPage.getNumber(),
                    productPage.getSize(),
                    productPage.getTotalElements(),
                    productPage.getTotalPages(),
                    productPage.isLast()
            );
        } catch (Exception e) {
            log.error("Error occurred while fetching products: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch products", e);
        }
    }

    @Transactional
    public List<ProductResponseDto> addBulkProducts(List<ProductRequestDto> requestDtos) {
        log.info("Attempting to add bulk products, count: {}", requestDtos.size());
        try {
            List<Product> productsToSave = requestDtos.stream()
                    .map(this::mapToEntity)
                    .toList();

            List<Product> savedProducts = productRepository.saveAll(productsToSave);
            log.info("Successfully added {} bulk products", savedProducts.size());

            return savedProducts.stream()
                    .map(this::mapToResponseDto)
                    .toList();
        } catch (Exception e) {
            log.error("Error occurred while adding bulk products: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to add bulk products", e);
        }
    }

    private ProductResponseDto mapToResponseDto(Product product) {
        return new ProductResponseDto(
                product.getId(),
                product.getName(),
                product.getCategory().name(),
                product.getBrand().name(),
                product.getPrice(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }

    private Product mapToEntity(ProductRequestDto productRequestDto) {
        return Product.builder()
                .price(productRequestDto.price())
                .category(Category.valueOf(String.valueOf(productRequestDto.category())))
                .brand(Brand.valueOf(String.valueOf(productRequestDto.brand())))
                .name(productRequestDto.name())
                .build();
    }

}