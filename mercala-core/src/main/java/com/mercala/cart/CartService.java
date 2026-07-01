package com.mercala.cart;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.catalog.VariantRepository;
import com.mercala.identity.exception.ResourceNotFoundException;
import com.mercala.platform.multitenancy.TenantContext;

@Service
@Transactional
public class CartService {

    private final CartRepository cartRepository;
    private final VariantRepository variantRepository;

    public CartService(CartRepository cartRepository, VariantRepository variantRepository) {
        this.cartRepository = cartRepository;
        this.variantRepository = variantRepository;
    }

    public Cart getOrCreateCart(UUID userId) {
        UUID tenantId = getRequiredTenantId();
        return cartRepository.findByUserIdAndTenantId(userId, tenantId)
                .orElseGet(() -> cartRepository.save(new Cart(tenantId, userId)));
    }

    public Cart addOrUpdateLine(UUID userId, UUID variantId, int qty) {
        return mutateCartWithVariant(userId, variantId, cart -> cart.addOrUpdateLine(variantId, qty));
    }

    public Cart updateLine(UUID userId, UUID variantId, int qty) {
        return mutateCartWithVariant(userId, variantId, cart -> cart.updateLine(variantId, qty));
    }

    public Cart removeLine(UUID userId, UUID variantId) {
        return mutateCart(userId, cart -> cart.removeLine(variantId));
    }

    public void clearCart(UUID userId) {
        mutateCart(userId, Cart::clearLines);
    }

    private Cart mutateCart(UUID userId, java.util.function.Consumer<Cart> mutation) {
        Cart cart = getOrCreateCart(userId);
        mutation.accept(cart);
        return cartRepository.save(cart);
    }

    private Cart mutateCartWithVariant(UUID userId, UUID variantId, java.util.function.Consumer<Cart> mutation) {
        Cart cart = getOrCreateCart(userId);
        validateVariantExists(variantId, cart.getTenantId());
        mutation.accept(cart);
        return cartRepository.save(cart);
    }

    private void validateVariantExists(UUID variantId, UUID tenantId) {
        variantRepository.findByIdAndTenantId(variantId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Variant not found: " + variantId));
    }

    private UUID getRequiredTenantId() {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Tenant context is required");
        }
        return tenantId;
    }
}
