package com.mercala.order.web.dto;

/**
 * What checkout returns: the order, and where to go and pay for it.
 *
 * <p>Wrapping rather than widening {@link OrderResponse}, because that record is also what
 * every order-read endpoint returns and none of those have a checkout URL to give.
 *
 * @param order    the placed order
 * @param payment  the payment attempt, or null when payment could not be started. A null here
 *                 is not a failed checkout: the order exists and stock is reserved, and the
 *                 client should surface a retry rather than a lost basket.
 */
public record CheckoutResponse(
        OrderResponse order,
        PaymentInfo payment
) {
    /**
     * @param provider          the provider the routing landed on, e.g. PAYSTACK
     * @param status            PENDING, FAILED, or whatever a prior attempt recorded
     * @param checkoutUrl       where to send the shopper to pay. Null for a failed attempt,
     *                          and null for a repeat call on an order already initiated.
     * @param providerReference the provider's id for this attempt, which is what the inbound
     *                          webhook matches on
     */
    public record PaymentInfo(
            String provider,
            String status,
            String checkoutUrl,
            String providerReference
    ) {}
}
