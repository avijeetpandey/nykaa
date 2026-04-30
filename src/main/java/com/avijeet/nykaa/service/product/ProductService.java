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

        Product product = Product.builder()
                .name(productRequestDto.name())
                .brand(productRequestDto.brand())
                .category(productRequestDto.category())
                .price(productRequestDto.price())
                .build();

        Product savedProduct = productRepository.save(product);

        log.info("Product saved successfully: id={}", savedProduct.getId());
        return mapToResponseDto(savedProduct);
    }

    @Transactional
    public ProductResponseDto updateProduct(ProductUpdateDto productUpdateDto) {
        Product existingProduct = productRepository.findById(productUpdateDto.id())
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + productUpdateDto.id()));

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

        return mapToResponseDto(savedProduct);
    }

    @Transactional
    public void deleteProduct(Long productId) {
        if(!productRepository.existsById(productId)) {
            throw new ProductNotFoundException("Product not found with id: " + productId);
        }
        productRepository.deleteById(productId);
    }

    public PageResponse<ProductResponseDto> getAllProducts(int pageNo, int pageSize, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase(Sort.Direction.ASC.name())
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(pageNo, pageSize, sort);

        Page<Product> productPage = productRepository.findAll(pageable);

        List<ProductResponseDto> content = productPage.getContent().stream()
                .map(this::mapToResponseDto)
                .toList();

        return new PageResponse<>(
                content,
                productPage.getNumber(),
                productPage.getSize(),
                productPage.getTotalElements(),
                productPage.getTotalPages(),
                productPage.isLast()
        );
    }

    @Transactional
    public List<ProductResponseDto> addBulkProducts(List<ProductRequestDto> requestDtos) {
        List<Product> productsToSave = requestDtos.stream()
                .map(this::mapToEntity)
                .toList();

        List<Product> savedProducts = productRepository.saveAll(productsToSave);

        return savedProducts.stream()
                .map(this::mapToResponseDto)
                .toList();
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
