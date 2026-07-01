package com.mercala.cart.web;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.cart.Cart;
import com.mercala.cart.CartService;
import com.mercala.cart.web.dto.AddCartItemRequest;
import com.mercala.cart.web.dto.CartLineResponse;
import com.mercala.cart.web.dto.CartResponse;
import com.mercala.cart.web.dto.UpdateCartItemRequest;
import com.mercala.platform.security.AuthenticatedUser;

@RestController
@RequestMapping("/api/cart")
@PreAuthorize("isAuthenticated()")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse getCart(@AuthenticationPrincipal AuthenticatedUser user) {
        Cart cart = cartService.getOrCreateCart(user.userId());
        return mapToResponse(cart);
    }

    @PostMapping("/items")
    public CartResponse addItem(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody AddCartItemRequest request) {
        Cart cart = cartService.addOrUpdateLine(user.userId(), request.variantId(), request.quantity());
        return mapToResponse(cart);
    }

    @PutMapping("/items/{variantId}")
    public CartResponse updateItem(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID variantId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        Cart cart = cartService.updateLine(user.userId(), variantId, request.quantity());
        return mapToResponse(cart);
    }

    @DeleteMapping("/items/{variantId}")
    public CartResponse removeItem(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID variantId) {
        Cart cart = cartService.removeLine(user.userId(), variantId);
        return mapToResponse(cart);
    }

    @DeleteMapping
    public void clearCart(@AuthenticationPrincipal AuthenticatedUser user) {
        cartService.clearCart(user.userId());
    }

    private CartResponse mapToResponse(Cart cart) {
        List<CartLineResponse> lines = cart.getLines().stream()
                .map(line -> new CartLineResponse(line.getId(), line.getVariantId(), line.getQuantity()))
                .collect(Collectors.toList());

        return new CartResponse(cart.getId(), cart.getUserId(), lines);
    }
}
