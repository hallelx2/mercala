package com.mercala.cart.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A cart, priced.
 *
 * <p>The totals are computed here rather than left to the client. Two clients adding up the
 * same lines will eventually round differently, and the number a shopper reads before
 * pressing the button should come from the same place as the number checkout charges.
 *
 * @param totalAmount what these lines cost at today's prices — an estimate until checkout,
 *                    which reprices and is the only authority on what is owed
 * @param itemCount   units, not lines: three of one shirt is three items
 */
public record CartResponse(
        UUID id,
        UUID userId,
        List<CartLineResponse> lines,
        BigDecimal totalAmount,
        int itemCount
) {}
