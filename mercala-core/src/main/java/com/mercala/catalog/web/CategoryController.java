package com.mercala.catalog.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.catalog.service.ProductService;
import com.mercala.catalog.web.dto.CategoryResponse;
import com.mercala.catalog.web.dto.CreateCategoryRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final ProductService productService;

    public CategoryController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF')")
    public CategoryResponse createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        return productService.createCategory(request);
    }

    @GetMapping
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF') or hasRole('SHOPPER')")
    public List<CategoryResponse> getCategories() {
        return productService.getCategories();
    }
}
