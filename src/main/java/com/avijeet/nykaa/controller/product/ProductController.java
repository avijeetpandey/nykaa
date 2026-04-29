package com.avijeet.nykaa.controller.product;

import com.avijeet.nykaa.constants.ApiConstants;
import com.avijeet.nykaa.dto.product.ProductRequestDto;
import com.avijeet.nykaa.dto.product.ProductResponseDto;
import com.avijeet.nykaa.service.product.ProductService;
import com.avijeet.nykaa.utils.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService productService;

    @PostMapping("/add")
    public ResponseEntity<ApiResponse<ProductResponseDto>> addProduct(@Valid @RequestBody ProductRequestDto productRequestDto) {
        log.info("POST /api/v1/products/add - name={}", productRequestDto.name());
        ProductResponseDto savedProduct = productService.addProduct(productRequestDto);
        ApiResponse<ProductResponseDto> response = ApiResponse.success(ApiConstants.DONE_MESSAGE,savedProduct);
        return ResponseEntity.ok(response);
    }
}
