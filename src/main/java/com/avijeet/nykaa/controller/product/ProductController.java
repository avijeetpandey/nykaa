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
        ProductResponseDto savedProduct = productService.addProduct(productRequestDto);
        ApiResponse<ProductResponseDto> response = ApiResponse.success(ApiConstants.DONE_MESSAGE,savedProduct);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/update")
    public ResponseEntity<ApiResponse<ProductResponseDto>> addProduct(@Valid @RequestBody ProductUpdateDto productUpdateDto) {
        log.info("POST /api/v1/products/update - name={}", productUpdateDto.name());
        ProductResponseDto savedProduct = productService.updateProduct(productUpdateDto);
        ApiResponse<ProductResponseDto> response = ApiResponse.success(ApiConstants.DONE_MESSAGE,savedProduct);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<ApiResponse<ProductResponseDto>> deleteProduct(@PathVariable Long id) {
        log.info("DELETE /api/v1/products/delete/{} - Deleting product with id={}", id, id);
        productService.deleteProduct(id);
        ApiResponse<ProductResponseDto> response = ApiResponse.success(ApiConstants.DONE_MESSAGE, null);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/all")
    public ResponseEntity<ApiResponse<PageResponse<ProductResponseDto>>> getAllProducts(
            @RequestParam(value = "pageNo", defaultValue = "0", required = false) int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10", required = false) int pageSize,
            @RequestParam(value = "sortBy", defaultValue = "id", required = false) String sortBy,
            @RequestParam(value = "sortDir", defaultValue = "asc", required = false) String sortDir
    ) {
        PageResponse<ProductResponseDto> paginatedProducts = productService.getAllProducts(pageNo, pageSize, sortBy, sortDir);
        return ResponseEntity.ok(ApiResponse.success("Products fetched successfully", paginatedProducts));
    }

    @PostMapping("/addBulk")
    public ResponseEntity<ApiResponse<List<ProductResponseDto>>> addBulkProducts(@Valid @RequestBody List<ProductRequestDto> requestDtosList) {
        List<ProductResponseDto> bulkProducts = productService.addBulkProducts(requestDtosList);
        return ResponseEntity.ok(ApiResponse.success("Products fetched successfully", bulkProducts));
    }
}
