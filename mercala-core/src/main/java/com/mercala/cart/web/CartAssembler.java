package com.mercala.cart.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.cart.Cart;
import com.mercala.cart.CartLine;
import com.mercala.cart.web.dto.CartLineResponse;
import com.mercala.cart.web.dto.CartResponse;
import com.mercala.catalog.VariantLookup;
import com.mercala.catalog.VariantLookup.VariantSummary;
import com.mercala.media.ProductImageDecorator;

/**
 * Turns a stored cart into one a shopper can read.
 *
 * <p>Two batched queries for the whole cart regardless of its length: one for the variants
 * and their products, one for the imagery. Doing this per line — the obvious shape when the
 * mapper is a lambda inside the controller — is a query per row on the page a shopper looks
 * at most carefully.
 *
 * <p>A line whose variant no longer resolves keeps its quantity and loses its detail rather
 * than vanishing. It is priced at zero so it cannot inflate a total the shopper is about to
 * agree to, and the null name is what tells the storefront to mark it unavailable.
 */
@Component
public class CartAssembler {

    private final VariantLookup variants;
    private final ProductImageDecorator images;

    public CartAssembler(VariantLookup variants, ProductImageDecorator images) {
        this.variants = variants;
        this.images = images;
    }

    @Transactional(readOnly = true)
    public CartResponse assemble(Cart cart) {
        List<CartLine> lines = cart.getLines();

        Map<UUID, VariantSummary> summaries =
                variants.byIds(lines.stream().map(CartLine::getVariantId).toList());
        Map<UUID, String> thumbnails = images.primaryImageUrls(
                summaries.values().stream().map(VariantSummary::productId).toList());

        BigDecimal total = BigDecimal.ZERO;
        int itemCount = 0;
        List<CartLineResponse> mapped = new java.util.ArrayList<>(lines.size());

        for (CartLine line : lines) {
            VariantSummary summary = summaries.get(line.getVariantId());
            BigDecimal unitPrice = summary != null ? summary.price() : BigDecimal.ZERO;
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.getQuantity()));

            mapped.add(new CartLineResponse(
                    line.getId(),
                    line.getVariantId(),
                    line.getQuantity(),
                    summary != null ? summary.productId() : null,
                    summary != null ? summary.productName() : null,
                    summary != null ? summary.sku() : null,
                    unitPrice,
                    lineTotal,
                    summary != null ? thumbnails.get(summary.productId()) : null));

            total = total.add(lineTotal);
            itemCount += line.getQuantity();
        }

        return new CartResponse(cart.getId(), cart.getUserId(), mapped, total, itemCount);
    }
}
