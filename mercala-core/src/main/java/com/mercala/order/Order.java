package com.mercala.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import org.hibernate.annotations.Filter;

@Entity
@Table(name = "orders")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status = OrderStatus.PLACED;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    /**
     * Mapped read-only: the column already exists with a database default (V9__orders.sql),
     * so Postgres owns the value and Hibernate must not try to write it. Needed because an
     * order list is read chronologically and the primary key is a random UUID — sorting by
     * id looks ordered but is arbitrary.
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.Instant createdAt;


    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {}

    public Order(UUID tenantId, UUID userId, BigDecimal totalAmount, String idempotencyKey) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.totalAmount = totalAmount;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getId() {
        return id;
    }

    public java.time.Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void transitionTo(OrderStatus newStatus) {
        if (!isValidTransition(this.status, newStatus)) {
            throw new IllegalStateException("Illegal status transition from " + this.status + " to " + newStatus);
        }
        this.status = newStatus;
    }

    private boolean isValidTransition(OrderStatus current, OrderStatus next) {
        if (current == next) {
            return true;
        }
        return switch (current) {
            case PLACED -> next == OrderStatus.PAID || next == OrderStatus.CANCELLED;
            case PAID -> next == OrderStatus.FULFILLED || next == OrderStatus.CANCELLED;
            case FULFILLED, CANCELLED -> false;
        };
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public List<OrderLine> getLines() {
        return lines;
    }

    public void addLine(UUID variantId, int quantity, BigDecimal unitPrice) {
        lines.add(new OrderLine(this.tenantId, this, variantId, quantity, unitPrice));
    }
}
