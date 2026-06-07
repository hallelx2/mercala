package com.mercala.identity;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mercala.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the identity domain persists correctly against a real Postgres:
 * tenant + user CRUD, password hashing, and the per-tenant email uniqueness constraint.
 */
class IdentityRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void savesAndFindsTenantBySlug() {
        Tenant saved = tenants.save(new Tenant("mango-store", "Mango Store"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(tenants.findBySlug("mango-store")).isPresent();
        assertThat(tenants.findBySlug("mango-store").get().getStatus()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void savesUserWithHashedPasswordAndFindsByTenantAndEmail() {
        Tenant tenant = tenants.save(new Tenant("bella-store", "Bella Store"));
        String hash = passwordEncoder.encode("s3cret!");

        users.save(new AppUser(tenant.getId(), "owner@bella.test", hash, Role.MERCHANT_OWNER));

        var found = users.findByTenantIdAndEmail(tenant.getId(), "owner@bella.test");
        assertThat(found).isPresent();
        assertThat(found.get().getRole()).isEqualTo(Role.MERCHANT_OWNER);
        assertThat(found.get().getPasswordHash()).isNotEqualTo("s3cret!");
        assertThat(passwordEncoder.matches("s3cret!", found.get().getPasswordHash())).isTrue();
    }

    @Test
    void enforcesUniqueEmailWithinTenant() {
        Tenant tenant = tenants.save(new Tenant("dup-store", "Dup Store"));
        users.save(new AppUser(tenant.getId(), "a@dup.test", "h1", Role.MERCHANT_STAFF));

        assertThatThrownBy(() ->
                users.saveAndFlush(new AppUser(tenant.getId(), "a@dup.test", "h2", Role.MERCHANT_STAFF)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameEmailInDifferentTenants() {
        Tenant t1 = tenants.save(new Tenant("store-one", "Store One"));
        Tenant t2 = tenants.save(new Tenant("store-two", "Store Two"));

        users.save(new AppUser(t1.getId(), "shared@email.test", "h1", Role.SHOPPER));
        AppUser second = users.saveAndFlush(
                new AppUser(t2.getId(), "shared@email.test", "h2", Role.SHOPPER));

        assertThat(second.getId()).isNotNull();
        assertThat(UUID.fromString(second.getTenantId().toString())).isEqualTo(t2.getId());
    }
}
