package com.mercala.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import com.mercala.agent.client.MercalaCoreClient;
import com.mercala.agent.tool.ToolPayloads.*;

/**
 * Spring AI tool/function definitions for the Mercala agent.
 * Each @Bean produces a Function that Spring AI registers as a callable tool.
 * All calls are tenant-scoped and delegated to the MercalaCoreClient.
 *
 * <p>Every body is wrapped in {@link ToolActivity#observe} so the run's AG-UI stream
 * carries the call, its arguments, its result and its duration. That wrapper is not
 * optional decoration: these calls are where a turn spends its time, and an unwrapped
 * tool is a silent gap the merchant reads as a hang.
 */
@Configuration
public class CatalogTools {

    private static final Logger log = LoggerFactory.getLogger(CatalogTools.class);

    private final MercalaCoreClient coreClient;

    public CatalogTools(MercalaCoreClient coreClient) {
        this.coreClient = coreClient;
    }

    // ── CreateProduct ──────────────────────────────────────────────

    @Bean
    @Description("Creates a new product in the merchant's catalog. " +
            "Accepts product name, description, price, optional category ID, tags, and variant specifications. " +
            "Returns the created product details including its ID.")
    public Function<CreateProductArgs, Map<String, Object>> createProduct() {
        return args -> ToolActivity.observe("createProduct", args, () -> {
            log.info("Tool: createProduct invoked — name={}, price={}", args.name(), args.price());

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("name", args.name());
            body.put("description", args.description());
            body.put("price", args.price());
            if (args.categoryId() != null) body.put("categoryId", args.categoryId());
            if (args.tags() != null) body.put("tags", args.tags());
            if (args.variants() != null && !args.variants().isEmpty()) {
                List<Map<String, Object>> variantMaps = args.variants().stream().map(v -> {
                    Map<String, Object> vm = new java.util.LinkedHashMap<>();
                    vm.put("sku", v.sku());
                    vm.put("price", v.price());
                    if (v.attrs() != null) vm.put("attrs", v.attrs());
                    return vm;
                }).toList();
                body.put("variants", variantMaps);
            }

            Map<String, Object> result = coreClient.createProduct(body);
            log.info("Tool: createProduct completed — result={}", result);
            return result != null ? result : Map.of("error", "Empty response from core API");
        });
    }

    // ── GetProduct ─────────────────────────────────────────────────

    @Bean
    @Description("Retrieves a single product by its ID from the catalog. Returns product details including name, price, description, tags, and variants.")
    public Function<GetProductArgs, Map<String, Object>> getProduct() {
        return args -> ToolActivity.observe("getProduct", args, () -> {
            log.info("Tool: getProduct invoked — productId={}", args.productId());
            Map<String, Object> result = coreClient.getProduct(UUID.fromString(args.productId()));
            log.info("Tool: getProduct completed");
            return result != null ? result : Map.of("error", "Product not found");
        });
    }

    // ── SearchCatalog ──────────────────────────────────────────────

    @Bean
    @Description("Searches the product catalog by keyword or natural-language intent. " +
            "Supports modes: 'hybrid' (default, best quality), 'semantic' (intent-based), 'lexical' (keyword-exact). " +
            "Returns a paginated list of matching products with their details.")
    public Function<SearchCatalogArgs, Map<String, Object>> searchCatalog() {
        return args -> ToolActivity.observe("searchCatalog", args, () -> {
            log.info("Tool: searchCatalog invoked — query='{}', mode={}, page={}, size={}",
                    args.query(), args.mode(), args.page(), args.size());
            Map<String, Object> result = coreClient.searchCatalog(args.query(), args.mode(), args.page(), args.size());
            log.info("Tool: searchCatalog completed — {} results",
                    result != null ? result.getOrDefault("totalElements", "?") : 0);
            return result != null ? result : Map.of("content", List.of(), "totalElements", 0);
        });
    }

    // ── UpdateInventory ────────────────────────────────────────────

    @Bean
    @Description("Adjusts the stock quantity for a product variant. " +
            "Use a positive number to add stock, or a negative number to reduce stock. " +
            "Returns the updated stock levels including available and reserved quantities.")
    public Function<UpdateInventoryArgs, Map<String, Object>> updateInventory() {
        return args -> ToolActivity.observe("updateInventory", args, () -> {
            log.info("Tool: updateInventory invoked — variantId={}, quantity={}", args.variantId(), args.quantity());
            Map<String, Object> result = coreClient.updateInventory(UUID.fromString(args.variantId()), args.quantity());
            log.info("Tool: updateInventory completed — result={}", result);
            return result != null ? result : Map.of("error", "Failed to update inventory");
        });
    }
}
