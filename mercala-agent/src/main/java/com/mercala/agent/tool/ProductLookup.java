package com.mercala.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mercala.agent.client.MercalaCoreClient;

/**
 * Turns what the merchant called a product into the id the API works in.
 *
 * <p>A merchant says "my navy linen shirt". They do not know the UUID, they have nowhere
 * to look it up, and being asked for one is the point at which a conversation stops being
 * a conversation — the agent asked, the merchant could not answer, and the turn looped
 * until the run gave up. Every tool that takes a product therefore accepts a name too, and
 * resolves it here.
 *
 * <p>Resolution is deliberately narrow: an exact name match wins, and a single search hit
 * is accepted as the answer. Anything more ambiguous returns nothing, because guessing
 * which of four shirts the merchant meant is worse than saying which four there are.
 */
@Component
public class ProductLookup {

    private static final Logger log = LoggerFactory.getLogger(ProductLookup.class);

    private final MercalaCoreClient coreClient;

    public ProductLookup(MercalaCoreClient coreClient) {
        this.coreClient = coreClient;
    }

    /** The result of looking a product up: an id, or the names that were on offer. */
    public record Resolution(UUID id, String name, List<String> candidates) {
        public boolean found() {
            return id != null;
        }
    }

    public Resolution resolve(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return new Resolution(null, null, List.of());
        }
        String query = supplied.trim();
        try {
            return new Resolution(UUID.fromString(query), null, List.of());
        } catch (IllegalArgumentException notAnId) {
            // Expected: the merchant named the product instead of identifying it.
        }

        // The catalogue first. A merchant naming their own product is a lookup, not a
        // search, and going through search made resolution depend on the BM25 parser —
        // which fails on the punctuation products' names actually contain.
        for (Map<String, Object> product : catalogue()) {
            Object name = product.get("name");
            if (name != null && name.toString().equalsIgnoreCase(query)) {
                return new Resolution(idOf(product), name.toString(), List.of());
            }
        }

        List<Map<String, Object>> hits = search(query);
        for (Map<String, Object> hit : hits) {
            Object name = hit.get("name");
            if (name != null && name.toString().equalsIgnoreCase(query)) {
                return new Resolution(idOf(hit), name.toString(), List.of());
            }
        }
        if (hits.size() == 1) {
            Map<String, Object> only = hits.get(0);
            return new Resolution(idOf(only), String.valueOf(only.get("name")), List.of());
        }
        return new Resolution(null, null, hits.stream().map(hit -> String.valueOf(hit.get("name"))).toList());
    }

    /** A variant and the product it belongs to, or the SKUs that could have been meant. */
    public record VariantResolution(UUID variantId, String sku, String productName, List<String> candidates) {
        public boolean found() {
            return variantId != null;
        }
    }

    /**
     * The variant behind what the merchant said — an SKU, a product name, or an id.
     *
     * <p>Stock is held per variant, but a merchant says "I have twelve of the serving bowl".
     * Where a product has exactly one variant, that is the one they mean; where it has
     * several, the SKUs come back so the model can ask which, instead of asking for a UUID
     * the merchant has never seen.
     */
    @SuppressWarnings("unchecked")
    public VariantResolution resolveVariant(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return new VariantResolution(null, null, null, List.of());
        }
        String query = supplied.trim();
        try {
            return new VariantResolution(UUID.fromString(query), null, null, List.of());
        } catch (IllegalArgumentException notAnId) {
            // Expected: a SKU or a product name.
        }

        List<Map<String, Object>> candidates = new java.util.ArrayList<>(catalogue());
        candidates.addAll(search(query));
        for (Map<String, Object> product : candidates) {
            List<Map<String, Object>> variants = product.get("variants") instanceof List<?> list
                    ? (List<Map<String, Object>>) list : List.of();
            for (Map<String, Object> variant : variants) {
                if (matches(variant.get("sku"), query)) {
                    return new VariantResolution(
                            idOf(variant), String.valueOf(variant.get("sku")),
                            String.valueOf(product.get("name")), List.of());
                }
            }
        }

        Resolution product = resolve(query);
        if (!product.found()) {
            return new VariantResolution(null, null, null, product.candidates());
        }
        List<Map<String, Object>> variants = variantsOf(product.id());
        if (variants.size() == 1) {
            Map<String, Object> only = variants.get(0);
            return new VariantResolution(
                    idOf(only), String.valueOf(only.get("sku")), product.name(), List.of());
        }
        return new VariantResolution(null, null, product.name(),
                variants.stream().map(variant -> String.valueOf(variant.get("sku"))).toList());
    }

    /** What to tell the model when a variant could not be pinned down. */
    public static Map<String, Object> variantNotFound(String supplied, VariantResolution resolution) {
        return Map.of(
                "status", "VARIANT_NOT_FOUND",
                "searchedFor", supplied == null ? "" : supplied,
                "candidates", resolution.candidates(),
                "message", resolution.candidates().isEmpty()
                        ? "Nothing in the catalogue matches \"" + supplied + "\". Say so, and ask "
                                + "the merchant which product they mean — by name, never by id."
                        : "That product has more than one variant. Ask the merchant which, by "
                                + "SKU: " + String.join(", ", resolution.candidates()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> variantsOf(UUID productId) {
        try {
            Map<String, Object> product = coreClient.getProduct(productId);
            Object variants = product == null ? null : product.get("variants");
            return variants instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        } catch (RuntimeException e) {
            log.warn("Could not read variants of {}", productId, e);
            return List.of();
        }
    }

    /**
     * What to tell the model when a name matched nothing. It reads as an instruction
     * because the model's next move should be to say this to the merchant, not to ask
     * them for an id it has just been told they do not have.
     */
    public static Map<String, Object> notFound(String supplied, Resolution resolution) {
        return Map.of(
                "status", "PRODUCT_NOT_FOUND",
                "searchedFor", supplied == null ? "" : supplied,
                "candidates", resolution.candidates(),
                "message", resolution.candidates().isEmpty()
                        ? "No product matches \"" + supplied + "\". Tell the merchant, and offer to "
                                + "create it. Never ask them for a product id."
                        : "More than one product could be meant. Ask the merchant which of these: "
                                + String.join(", ", resolution.candidates()));
    }

    /** The merchant's own products, newest first. Cheap, exact, and never parsed. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> catalogue() {
        try {
            Map<String, Object> page = coreClient.listProducts(0, 100);
            Object content = page == null ? null : page.get("content");
            return content instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        } catch (RuntimeException e) {
            log.warn("Could not list the catalogue while resolving a product", e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> search(String query) {
        try {
            Map<String, Object> page = coreClient.searchCatalog(query, "hybrid", 0, 5);
            Object content = page == null ? null : page.get("content");
            return content instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        } catch (RuntimeException e) {
            log.warn("Catalog search failed while resolving '{}'", query, e);
            return List.of();
        }
    }

    private static boolean matches(Object candidate, String supplied) {
        return candidate != null && candidate.toString().equalsIgnoreCase(supplied);
    }

    private static UUID idOf(Map<String, Object> product) {
        Object id = product.get("id");
        try {
            return id == null ? null : UUID.fromString(id.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
