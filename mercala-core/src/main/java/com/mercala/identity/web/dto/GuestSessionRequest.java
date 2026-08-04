package com.mercala.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Asks for a shopper session scoped to one storefront.
 *
 * @param storeSlug the store being shopped. There is no account to derive a tenant from,
 *                  so this is what scopes the session — and it is the only input, because
 *                  a guest supplies nothing else until checkout asks for an email.
 */
public record GuestSessionRequest(@NotBlank(message = "A store slug is required") String storeSlug) {}
