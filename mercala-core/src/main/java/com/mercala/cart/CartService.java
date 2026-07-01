package com.mercala.cart;

import java.util.UUID;

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
        UUID tenantId = getRequiredTenantId();
        validateVariantExists(variantId, tenantId);

        Cart cart = getOrCreateCart(userId);
        cart.addOrUpdateLine(variantId, qty);
        return cartRepository.save(cart);
    }

    public Cart updateLine(UUID userId, UUID variantId, int qty) {
        UUID tenantId = getRequiredTenantId();
        validateVariantExists(variantId, tenantId);

        Cart cart = getOrCreateCart(userId);
        cart.updateLine(variantId, qty);
        return cartRepository.save(cart);
    }

    public Cart removeLine(UUID userId, UUID variantId) {
        Cart cart = getOrCreateCart(userId);
        cart.removeLine(variantId);
        return cartRepository.save(cart);
    }

    public void clearCart(UUID userId) {
        Cart cart = getOrCreateCart(userId);
        cart.getLines().clear();
        cartRepository.save(cart);
    }

    private void validateVariantExists(UUID variantId, UUID tenantId) {
        variantRepository.findByIdAndTenantId(variantId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Variant not found: " + variantId));
    }

    private UUID getRequiredTenantId() {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Tenant context is required");
        }
        return tenantId;
    }
}
