package com.mercala.identity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * "This user belongs to this store as this role" (HAL-556). The full set of a user's
 * stores lives here; {@code app_user.tenant_id} only names the <em>active</em> one —
 * the store the JWT carries. Deliberately NOT tenant-filtered: memberships are queried
 * by user, before any tenant is selected.
 */
@Entity
@Table(
        name = "store_membership",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_membership_user_tenant",
                columnNames = {"user_id", "tenant_id"}))
public class StoreMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Role role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StoreMembership() {
        // for JPA
    }

    public StoreMembership(UUID userId, UUID tenantId, Role role) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
