package com.mercala.cart.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a cart, as a shopper needs to see it.
 *
 * <p>The stored line is only a variant id and a quantity. Everything else here is resolved
 * at read time rather than copied at add time, deliberately: a price copied in when the item
 * was added would still be showing yesterday's number today, and the price a shopper agrees
 * to should be the one checkout actually charges.
 *
 * @param productName null when the variant has left the catalogue since it was added — the
 *                    line is shown as unavailable rather than dropped, because a bag that
 *                    quietly loses things is worse than one that explains
 * @param imageUrl    a signed, expiring URL (HAL-425). Never store it or treat it as identity
 */
public record CartLineResponse(
        UUID id,
        UUID variantId,
        int quantity,
        UUID productId,
        String productName,
        String sku,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String imageUrl
) {}
