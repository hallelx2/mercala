package com.mercala.inventory;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.Filter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "stock_item")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class StockItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "variant_id", nullable = false, unique = true)
    private UUID variantId;

    @Column(nullable = false)
    private int quantity = 0;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity = 0;

    @Version
    @Column(nullable = false)
    private long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Default constructor
    public StockItem() {}

    public StockItem(UUID tenantId, UUID variantId, int quantity) {
        this.tenantId = tenantId;
        this.variantId = variantId;
        setQuantity(quantity);
        this.reservedQuantity = 0;
    }

    // Business Methods
    public int getAvailableQuantity() {
        return quantity - reservedQuantity;
    }

    /**
     * Reserves the specified quantity.
     * Ensures we don't exceed available quantity.
     */
    public void reserve(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be positive");
        }
        if (qty > getAvailableQuantity()) {
            throw new IllegalStateException("Insufficient stock to reserve " + qty + " units (Available: " + getAvailableQuantity() + ")");
        }
        this.reservedQuantity += qty;
    }

    /**
     * Releases the specified quantity from reservation back to physical stock.
     */
    public void release(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("Release quantity must be positive");
        }
        if (qty > this.reservedQuantity) {
            throw new IllegalStateException("Cannot release " + qty + " units (Reserved: " + this.reservedQuantity + ")");
        }
        this.reservedQuantity -= qty;
    }

    /**
     * Confirms the reservation by deducting it from the physical quantity and releasing the reservation.
     */
    public void commitReservation(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("Commit quantity must be positive");
        }
        if (qty > this.reservedQuantity) {
            throw new IllegalStateException("Cannot commit " + qty + " units (Reserved: " + this.reservedQuantity + ")");
        }
        if (qty > this.quantity) {
            throw new IllegalStateException("Cannot commit " + qty + " units (Physical stock: " + this.quantity + ")");
        }
        this.quantity -= qty;
        this.reservedQuantity -= qty;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public void setVariantId(UUID variantId) {
        this.variantId = variantId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        if (quantity < this.reservedQuantity) {
            throw new IllegalStateException("Quantity cannot be set lower than reserved quantity (" + this.reservedQuantity + ")");
        }
        this.quantity = quantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public void setReservedQuantity(int reservedQuantity) {
        if (reservedQuantity < 0) {
            throw new IllegalArgumentException("Reserved quantity cannot be negative");
        }
        if (reservedQuantity > this.quantity) {
            throw new IllegalStateException("Reserved quantity cannot exceed physical quantity (" + this.quantity + ")");
        }
        this.reservedQuantity = reservedQuantity;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
