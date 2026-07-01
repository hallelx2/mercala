package com.mercala.cart;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@Entity
@Table(name = "cart")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = jakarta.persistence.FetchType.EAGER)
    private List<CartLine> lines = new ArrayList<>();

    protected Cart() {}

    public Cart(UUID tenantId, UUID userId) {
        this.tenantId = tenantId;
        this.userId = userId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public List<CartLine> getLines() {
        return lines;
    }

    public Optional<CartLine> getLine(UUID variantId) {
        return lines.stream()
                .filter(line -> line.getVariantId().equals(variantId))
                .findFirst();
    }

    public void addOrUpdateLine(UUID variantId, int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        Optional<CartLine> existing = getLine(variantId);
        if (existing.isPresent()) {
            existing.get().setQuantity(existing.get().getQuantity() + qty);
        } else {
            lines.add(new CartLine(this.tenantId, this, variantId, qty));
        }
    }

    public void updateLine(UUID variantId, int qty) {
        if (qty <= 0) {
            removeLine(variantId);
            return;
        }
        CartLine line = getLine(variantId)
                .orElseThrow(() -> new IllegalArgumentException("Line not found in cart for variant: " + variantId));
        line.setQuantity(qty);
    }

    public void removeLine(UUID variantId) {
        lines.removeIf(line -> line.getVariantId().equals(variantId));
    }
}
