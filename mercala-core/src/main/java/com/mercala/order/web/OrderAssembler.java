package com.mercala.order.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.catalog.VariantLookup;
import com.mercala.catalog.VariantLookup.VariantSummary;
import com.mercala.media.ProductImageDecorator;
import com.mercala.order.Order;
import com.mercala.order.OrderLine;
import com.mercala.order.web.dto.OrderLineResponse;
import com.mercala.order.web.dto.OrderResponse;

/**
 * Turns stored orders into ones a person can read.
 *
 * <p>Same shape as {@code CartAssembler}, and separate from it on purpose: an order line
 * carries the price it was bought at, so only its labels come from the catalogue. Sharing
 * one assembler between the two would put the two pricing rules in one branch, which is
 * exactly the branch someone would later get wrong.
 *
 * <p>The batching matters most on the list: a page of twenty orders resolved line-by-line is
 * a query per line, and the merchant's orders table is the page that shows the most lines at
 * once. {@link #assemble(Page)} resolves the whole page in two queries.
 */
@Component
public class OrderAssembler {

    private final VariantLookup variants;
    private final ProductImageDecorator images;

    public OrderAssembler(VariantLookup variants, ProductImageDecorator images) {
        this.variants = variants;
        this.images = images;
    }

    @Transactional(readOnly = true)
    public OrderResponse assemble(Order order) {
        return assemble(List.of(order)).get(0);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> assemble(Page<Order> orders) {
        Map<UUID, OrderResponse> byId = assemble(orders.getContent()).stream()
                .collect(java.util.stream.Collectors.toMap(OrderResponse::id, r -> r));
        return orders.map(order -> byId.get(order.getId()));
    }

    /** Every order in the collection, resolved in two queries regardless of how many lines. */
    @Transactional(readOnly = true)
    public List<OrderResponse> assemble(List<Order> orders) {
        List<UUID> variantIds = orders.stream()
                .flatMap(order -> order.getLines().stream())
                .map(OrderLine::getVariantId)
                .distinct()
                .toList();

        Map<UUID, VariantSummary> summaries = variants.byIds(variantIds);
        Map<UUID, String> thumbnails = images.primaryImageUrls(
                summaries.values().stream().map(VariantSummary::productId).distinct().toList());

        return orders.stream().map(order -> toResponse(order, summaries, thumbnails)).toList();
    }

    private OrderResponse toResponse(
            Order order,
            Map<UUID, VariantSummary> summaries,
            Map<UUID, String> thumbnails) {

        List<OrderLineResponse> lines = order.getLines().stream()
                .map(line -> {
                    VariantSummary summary = summaries.get(line.getVariantId());
                    return new OrderLineResponse(
                            line.getId(),
                            line.getVariantId(),
                            line.getQuantity(),
                            // Not summary.price(): what was charged, not what it costs today.
                            line.getUnitPrice(),
                            summary != null ? summary.productId() : null,
                            summary != null ? summary.productName() : null,
                            summary != null ? summary.sku() : null,
                            summary != null ? thumbnails.get(summary.productId()) : null);
                })
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getIdempotencyKey(),
                lines,
                order.getCreatedAt());
    }
}
