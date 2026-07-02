package com.mercala.payment;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "provider_refs")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class ProviderRef {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "connected_account_id")
    private String connectedAccountId;

    @Column(name = "public_key")
    private String publicKey;

    @Column(name = "secret_key")
    private String secretKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    protected ProviderRef() {}

    public ProviderRef(UUID tenantId, String provider, String connectedAccountId, boolean enabled) {
        this.tenantId = tenantId;
        this.provider = provider;
        this.connectedAccountId = connectedAccountId;
        this.enabled = enabled;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getProvider() {
        return provider;
    }

    public String getConnectedAccountId() {
        return connectedAccountId;
    }

    public void setConnectedAccountId(String connectedAccountId) {
        this.connectedAccountId = connectedAccountId;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
