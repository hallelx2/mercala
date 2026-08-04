package com.mercala.order.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of an order.
 *
 * <p>{@code unitPrice} is what the shopper was charged, stored on the line at checkout. It
 * is never re-resolved from the catalogue — a receipt that changes when the merchant edits a
 * price is not a receipt. The name, SKU and picture are resolved at read time, because those
 * are labels rather than terms.
 *
 * @param productName null when the variant has since been deleted; the line still shows its
 *                    quantity and what was paid, which is the part a receipt is for
 * @param imageUrl    a signed, expiring URL (HAL-425). Never store it
 */
public record OrderLineResponse(
        UUID id,
        UUID variantId,
        int quantity,
        BigDecimal unitPrice,
        UUID productId,
        String productName,
        String sku,
        String imageUrl
) {}
