package com.avijeet.nykaa.controller.product;

import com.avijeet.nykaa.constants.ApiConstants;
import com.avijeet.nykaa.dto.product.PageResponse;
import com.avijeet.nykaa.dto.product.ProductRequestDto;
import com.avijeet.nykaa.dto.product.ProductResponseDto;
import com.avijeet.nykaa.dto.product.ProductUpdateDto;
import com.avijeet.nykaa.service.product.ProductService;
import com.avijeet.nykaa.utils.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService productService;

    @PostMapping("/add")
    public ResponseEntity<ApiResponse<ProductResponseDto>> addProduct(@Valid @RequestBody ProductRequestDto productRequestDto) {
        log.info("POST /api/v1/products/add - name={}", productRequestDto.name());
        try {
            ProductResponseDto savedProduct = productService.addProduct(productRequestDto);
            ApiResponse<ProductResponseDto> response = ApiResponse.success(ApiConstants.DONE_MESSAGE,savedProduct);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Error in POST /api/v1/products/add: {}", e.getMessage(), e);
            throw e;
        }
    }

    @PostMapping("/update")
    public ResponseEntity<ApiResponse<ProductResponseDto>> updateProduct(@Valid @RequestBody ProductUpdateDto productUpdateDto) {
        log.info("POST /api/v1/products/update - id={}", productUpdateDto.id());
        try {
            ProductResponseDto savedProduct = productService.updateProduct(productUpdateDto);
            ApiResponse<ProductResponseDto> response = ApiResponse.success(ApiConstants.DONE_MESSAGE,savedProduct);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error in POST /api/v1/products/update for id {}: {}", productUpdateDto.id(), e.getMessage(), e);
            throw e;
        }
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<ApiResponse<ProductResponseDto>> deleteProduct(@PathVariable Long id) {
        log.info("DELETE /api/v1/products/delete/{} - Deleting product with id={}", id, id);
        try {
            productService.deleteProduct(id);
            ApiResponse<ProductResponseDto> response = ApiResponse.success(ApiConstants.DONE_MESSAGE, null);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error in DELETE /api/v1/products/delete/{}: {}", id, e.getMessage(), e);
            throw e;
        }
    }

    @GetMapping("/all")
    public ResponseEntity<ApiResponse<PageResponse<ProductResponseDto>>> getAllProducts(
            @RequestParam(value = "pageNo", defaultValue = "0", required = false) int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10", required = false) int pageSize,
            @RequestParam(value = "sortBy", defaultValue = "id", required = false) String sortBy,
            @RequestParam(value = "sortDir", defaultValue = "asc", required = false) String sortDir
    ) {
        log.info("GET /api/v1/products/all called with pageNo={}, pageSize={}, sortBy={}, sortDir={}", pageNo, pageSize, sortBy, sortDir);
        try {
            PageResponse<ProductResponseDto> paginatedProducts = productService.getAllProducts(pageNo, pageSize, sortBy, sortDir);
            return ResponseEntity.ok(ApiResponse.success("Products fetched successfully", paginatedProducts));
        } catch (Exception e) {
            log.error("Error in GET /api/v1/products/all: {}", e.getMessage(), e);
            throw e;
        }
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PageResponse<ProductResponseDto>>> searchProducts(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "brand", required = false) String brand,
            @RequestParam(value = "pageNo", defaultValue = "0", required = false) int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10", required = false) int pageSize,
            @RequestParam(value = "sortBy", defaultValue = "id", required = false) String sortBy,
            @RequestParam(value = "sortDir", defaultValue = "asc", required = false) String sortDir
    ) {
        log.info("GET /api/v1/products/search called with name={}, category={}, brand={}", name, category, brand);
        try {
            PageResponse<ProductResponseDto> searchResults = productService.searchProducts(name, category, brand, pageNo, pageSize, sortBy, sortDir);
            return ResponseEntity.ok(ApiResponse.success("Products searched successfully", searchResults));
        } catch (Exception e) {
            log.error("Error in GET /api/v1/products/search: {}", e.getMessage(), e);
            throw e;
        }
    }

    @PostMapping("/addBulk")
    public ResponseEntity<ApiResponse<List<ProductResponseDto>>> addBulkProducts(@Valid @RequestBody List<ProductRequestDto> requestDtosList) {
        log.info("POST /api/v1/products/addBulk called with {} items", requestDtosList.size());
        try {
            List<ProductResponseDto> bulkProducts = productService.addBulkProducts(requestDtosList);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Products added successfully", bulkProducts));
        } catch (Exception e) {
            log.error("Error in POST /api/v1/products/addBulk: {}", e.getMessage(), e);
            throw e;
        }
    }
}
