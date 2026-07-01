package com.mercala.catalog.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.catalog.service.ProductService;
import com.mercala.catalog.web.dto.ProductResponse;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final ProductService productService;

    public SearchController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF') or hasRole('SHOPPER')")
    public Page<ProductResponse> search(
            @RequestParam("q") String query,
            @RequestParam(value = "mode", defaultValue = "hybrid") String mode,
            @PageableDefault(size = 10) Pageable pageable) {
        if ("semantic".equalsIgnoreCase(mode)) {
            return productService.searchSemantic(query, pageable);
        } else if ("lexical".equalsIgnoreCase(mode)) {
            return productService.searchLexical(query, pageable);
        }
        return productService.searchHybrid(query, pageable);
    }
}
