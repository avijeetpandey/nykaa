package com.avijeet.nykaa.service.product;

import com.avijeet.nykaa.dto.product.ProductRequestDto;
import com.avijeet.nykaa.dto.product.ProductResponseDto;
import com.avijeet.nykaa.entities.Product;
import com.avijeet.nykaa.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


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
}
