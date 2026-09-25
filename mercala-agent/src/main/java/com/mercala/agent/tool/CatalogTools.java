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

    private final ProductLookup productLookup;

    public CatalogTools(MercalaCoreClient coreClient, ProductLookup productLookup) {
        this.coreClient = coreClient;
        this.productLookup = productLookup;
    }

    // ── CreateProduct ──────────────────────────────────────────────

    @Bean
    @Description("Creates a new product in the merchant's catalog. " +
            "Accepts product name, description, price, tags, variant specifications, and an " +
            "optional category given either as its name or its id. A product does not need a " +
            "category — leave it out rather than asking the merchant for one. " +
            "Returns the created product details including its ID.")
    public Function<CreateProductArgs, Map<String, Object>> createProduct() {
        return args -> ToolActivity.observe("createProduct", args, () -> {
            log.info("Tool: createProduct invoked — name={}, price={}", args.name(), args.price());

            // A price the merchant did not give is not a price. Left to itself the model
            // fills the gap with zero and the shop ends up listing something for nothing —
            // a silent, public mistake that reads as a bug in the storefront. Answered to
            // the model rather than thrown, because asking is the correct next move and it
            // can only take it if it is told so.
            if (args.price() == null || args.price().signum() <= 0) {
                return Map.of(
                        "status", "PRICE_REQUIRED",
                        "message", "This product has no price. Ask the merchant for one with "
                                + "askUser and create it afterwards — never guess a price, and "
                                + "never create a product at zero.");
            }

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("name", args.name());
            body.put("description", args.description());
            body.put("price", args.price());
            // The model answers a category the way a person would — "Apparel" — but the
            // catalog API takes a UUID and rejects anything else with a bare 400. Passing
            // the name through made a helpful answer fatal: the creation failed, the error
            // carried nothing to correct, and the agent asked for a "category ID" the
            // merchant has no way to know. So resolve names against the merchant's own
            // categories, and where nothing matches, create the product without one and
            // say so. A category is metadata; it is not worth losing the product over.
            String categoryNote = null;
            if (args.categoryId() != null && !args.categoryId().isBlank()) {
                String resolved = resolveCategory(args.categoryId().trim());
                if (resolved != null) {
                    body.put("categoryId", resolved);
                } else {
                    categoryNote = "No category matches \"" + args.categoryId().trim()
                            + "\"; the product was created without one.";
                }
            }
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
            if (result == null) {
                return Map.of("error", "Empty response from core API");
            }
            if (categoryNote != null) {
                Map<String, Object> annotated = new java.util.LinkedHashMap<>(result);
                annotated.put("note", categoryNote);
                return annotated;
            }
            return result;
        });
    }

    /**
     * A category id for what the model supplied, or null when nothing matches.
     *
     * <p>Accepts an id verbatim, otherwise matches case-insensitively against each
     * category's name and slug. Returns null rather than throwing: an unknown category is
     * a detail to mention, not a reason to refuse to create the product.
     */
    private String resolveCategory(String supplied) {
        try {
            return UUID.fromString(supplied).toString();
        } catch (IllegalArgumentException notAnId) {
            // Expected: the model named the category instead of identifying it.
        }
        try {
            for (Map<String, Object> category : coreClient.listCategories()) {
                if (matches(category.get("name"), supplied) || matches(category.get("slug"), supplied)) {
                    Object id = category.get("id");
                    return id == null ? null : id.toString();
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not list categories while resolving '{}'", supplied, e);
        }
        return null;
    }

    private static boolean matches(Object candidate, String supplied) {
        return candidate != null && candidate.toString().equalsIgnoreCase(supplied);
    }

    // ── ListCategories ─────────────────────────────────────────────

    @Bean
    @Description("Lists the categories in the merchant's catalog, each with its id and name. " +
            "Use it when the merchant asks what categories exist, or before filing a product " +
            "under one — never ask the merchant to type a category id.")
    public Function<ListCategoriesArgs, Map<String, Object>> listCategories() {
        return args -> ToolActivity.observe("listCategories", args, () -> {
            List<Map<String, Object>> categories = coreClient.listCategories();
            log.info("Tool: listCategories completed — {} categories", categories.size());
            return Map.of("categories", categories, "count", categories.size());
        });
    }

    // ── GetProduct ─────────────────────────────────────────────────

    @Bean
    @Description("Retrieves a single product from the catalog by its name or its id. " +
            "Returns product details including name, price, description, tags, and variants.")
    public Function<GetProductArgs, Map<String, Object>> getProduct() {
        return args -> ToolActivity.observe("getProduct", args, () -> {
            log.info("Tool: getProduct invoked — product={}", args.productId());
            ProductLookup.Resolution resolved = productLookup.resolve(args.productId());
            if (!resolved.found()) {
                return ProductLookup.notFound(args.productId(), resolved);
            }
            Map<String, Object> result = coreClient.getProduct(resolved.id());
            log.info("Tool: getProduct completed");
            return result != null ? result : Map.of("error", "Product not found");
        });
    }

    // ── ListProducts ───────────────────────────────────────────────

    @Bean
    @Description("Lists the merchant's products, newest first, with prices and variants. " +
            "This is the tool for \"what am I selling?\", \"what's in my catalogue?\" and " +
            "anything else with no search term in it — searchCatalog needs something to " +
            "search for and returns nothing when asked for everything. Pages are zero-based.")
    public Function<ListProductsArgs, Map<String, Object>> listProducts() {
        return args -> ToolActivity.observe("listProducts", args, () -> {
            log.info("Tool: listProducts invoked — page={}, size={}", args.page(), args.size());
            Map<String, Object> result = coreClient.listProducts(args.page(), args.size());
            if (result == null) {
                return Map.of("content", List.of(), "totalElements", 0);
            }
            log.info("Tool: listProducts completed — {} products", result.getOrDefault("totalElements", "?"));
            return result;
        });
    }

    // ── SearchCatalog ──────────────────────────────────────────────

    @Bean
    @Description("Searches the product catalog by keyword or natural-language intent, or " +
            "lists it when the query is empty. " +
            "Supports modes: 'hybrid' (default, best quality), 'semantic' (intent-based), 'lexical' (keyword-exact). " +
            "Pages are zero-based: the first page is page 0. Omit page and size unless you are " +
            "deliberately walking a long catalogue. " +
            "Returns a paginated list of matching products with their details.")
    public Function<SearchCatalogArgs, Map<String, Object>> searchCatalog() {
        return args -> ToolActivity.observe("searchCatalog", args, () -> {
            log.info("Tool: searchCatalog invoked — query='{}', mode={}, page={}, size={}",
                    args.query(), args.mode(), args.page(), args.size());
            Map<String, Object> result = coreClient.searchCatalog(args.query(), args.mode(), args.page(), args.size());

            // A page past the end of a catalogue that has products reads as an empty
            // catalogue, and the merchant who asked what they sell is told "nothing".
            // Pages are zero-based and the model routinely means "the first one" when it
            // says 1, so a fruitless page beyond the first is retried at the beginning
            // rather than reported as an absence.
            if (args.page() > 0 && isEmptyPageOfANonEmptyCatalog(result)) {
                log.info("Tool: searchCatalog — page {} was past the end; retrying at page 0", args.page());
                result = coreClient.searchCatalog(args.query(), args.mode(), 0, args.size());
            }

            log.info("Tool: searchCatalog completed — {} results",
                    result != null ? result.getOrDefault("totalElements", "?") : 0);
            return result != null ? result : Map.of("content", List.of(), "totalElements", 0);
        });
    }

    private static boolean isEmptyPageOfANonEmptyCatalog(Map<String, Object> page) {
        if (page == null) {
            return false;
        }
        boolean empty = !(page.get("content") instanceof List<?> content) || content.isEmpty();
        long total = page.get("totalElements") instanceof Number number ? number.longValue() : 0L;
        return empty && total > 0;
    }

    // ── UpdateInventory ────────────────────────────────────────────

    @Bean
    @Description("Sets the stock quantity for a product. Identify it the way the merchant " +
            "does — the product's name, or an SKU when the product has several variants. " +
            "Returns the updated stock levels including available and reserved quantities.")
    public Function<UpdateInventoryArgs, Map<String, Object>> updateInventory() {
        return args -> ToolActivity.observe("updateInventory", args, () -> {
            log.info("Tool: updateInventory invoked — variant={}, quantity={}", args.variantId(), args.quantity());
            ProductLookup.VariantResolution variant = productLookup.resolveVariant(args.variantId());
            if (!variant.found()) {
                return ProductLookup.variantNotFound(args.variantId(), variant);
            }
            Map<String, Object> result = coreClient.updateInventory(variant.variantId(), args.quantity());
            log.info("Tool: updateInventory completed — result={}", result);
            return result != null ? result : Map.of("error", "Failed to update inventory");
        });
    }
}
