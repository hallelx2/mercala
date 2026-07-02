package com.mercala.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import com.mercala.agent.chat.AgentContext;
import com.mercala.agent.tool.ToolPayloads.*;

/**
 * Spring AI tool/function definitions for the Mercala agent.
 * Each @Bean produces a Function that Spring AI registers as a callable tool.
 * The agent resolves which to invoke based on the user's natural-language request.
 *
 * All calls are tenant-scoped: the X-Tenant-ID header is injected from the
 * AgentContext ThreadLocal set by the MerchantAgentService before each chat turn.
 */
@Configuration
public class CatalogTools {

    private static final Logger log = LoggerFactory.getLogger(CatalogTools.class);
    private static final String TENANT_HEADER = "X-Tenant-ID";

    @Value("${mercala.core.base-url:http://localhost:8080}")
    private String coreBaseUrl;

    private final RestTemplate restTemplate;

    public CatalogTools() {
        this.restTemplate = new RestTemplate();
    }

    // ── Helper: build headers with tenant context ───────────────────

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Inject tenant ID from the AgentContext ThreadLocal
        try {
            AgentContext ctx = AgentContext.current();
            headers.set(TENANT_HEADER, ctx.tenantId().toString());
        } catch (IllegalStateException e) {
            log.warn("AgentContext not available — tool call will proceed without tenant header");
        }

        return headers;
    }

    // ── CreateProduct ──────────────────────────────────────────────

    @Bean
    @Description("Creates a new product in the merchant's catalog. " +
            "Accepts product name, description, price, optional category ID, tags, and variant specifications. " +
            "Returns the created product details including its ID.")
    public Function<CreateProductArgs, Map<String, Object>> createProduct() {
        return args -> {
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

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders());
            String url = coreBaseUrl + "/api/products";

            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(url, entity, Map.class);
            log.info("Tool: createProduct completed — result={}", result);
            return result != null ? result : Map.of("error", "Empty response from core API");
        };
    }

    // ── GetProduct ─────────────────────────────────────────────────

    @Bean
    @Description("Retrieves a single product by its ID from the catalog. Returns product details including name, price, description, tags, and variants.")
    public Function<GetProductArgs, Map<String, Object>> getProduct() {
        return args -> {
            log.info("Tool: getProduct invoked — productId={}", args.productId());

            String url = coreBaseUrl + "/api/products/" + args.productId();
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class).getBody();
            log.info("Tool: getProduct completed");
            return result != null ? result : Map.of("error", "Product not found");
        };
    }

    // ── SearchCatalog ──────────────────────────────────────────────

    @Bean
    @Description("Searches the product catalog by keyword or natural-language intent. " +
            "Supports modes: 'hybrid' (default, best quality), 'semantic' (intent-based), 'lexical' (keyword-exact). " +
            "Returns a paginated list of matching products with their details.")
    public Function<SearchCatalogArgs, Map<String, Object>> searchCatalog() {
        return args -> {
            log.info("Tool: searchCatalog invoked — query='{}', mode={}, page={}, size={}",
                    args.query(), args.mode(), args.page(), args.size());

            String url = String.format("%s/api/search?q=%s&mode=%s&page=%d&size=%d",
                    coreBaseUrl,
                    java.net.URLEncoder.encode(args.query(), java.nio.charset.StandardCharsets.UTF_8),
                    args.mode(), args.page(), args.size());

            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class).getBody();
            log.info("Tool: searchCatalog completed — {} results",
                    result != null ? result.getOrDefault("totalElements", "?") : 0);
            return result != null ? result : Map.of("content", List.of(), "totalElements", 0);
        };
    }

    // ── UpdateInventory ────────────────────────────────────────────

    @Bean
    @Description("Adjusts the stock quantity for a product variant. " +
            "Use a positive number to add stock, or a negative number to reduce stock. " +
            "Returns the updated stock levels including available and reserved quantities.")
    public Function<UpdateInventoryArgs, Map<String, Object>> updateInventory() {
        return args -> {
            log.info("Tool: updateInventory invoked — variantId={}, quantity={}", args.variantId(), args.quantity());

            Map<String, Object> body = Map.of("quantity", args.quantity());
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders());
            String url = coreBaseUrl + "/api/inventory/" + args.variantId() + "/adjust";

            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(url, entity, Map.class);
            log.info("Tool: updateInventory completed — result={}", result);
            return result != null ? result : Map.of("error", "Failed to update inventory");
        };
    }
}
