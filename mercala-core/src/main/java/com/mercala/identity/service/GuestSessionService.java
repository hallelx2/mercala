package com.mercala.identity.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;

/**
 * Mints a shopper session for someone who has not signed up for anything.
 *
 * <h2>Why a session rather than an anonymous cart</h2>
 *
 * <p>Cart, checkout and order history are all {@code isAuthenticated()} and keyed on a user
 * id. The alternative to this class was a parallel set of anonymous endpoints keyed on a
 * cart cookie — a second code path through stock reservation and money, with its own
 * tenant-scoping to get right. Two paths through checkout is how one of them ends up
 * subtly wrong.
 *
 * <p>So a guest gets a real, tenant-scoped identity and the existing endpoints do not
 * change at all. Isolation is then enforced by the machinery that already exists: the token
 * carries {@code tenant_id}, the filter installs it, the Hibernate filter and RLS do the
 * rest.
 *
 * <h2>What a guest is not</h2>
 *
 * <p>It cannot log in. The password hash is bcrypt over a random string that is never
 * returned, stored elsewhere, or recoverable, so there is no credential to present — the
 * row exists to own a cart and an order, not to be an account. The email is synthetic and
 * marked as such; a real address is collected at checkout for the receipt, which is a
 * different thing from an identity.
 *
 * <h2>What is not bounded</h2>
 *
 * <p>Every call writes a row, and the endpoint is unauthenticated because it has to be.
 * Only nginx's per-IP limit stands between this and an arbitrarily large table. The rows
 * are individually harmless, but the table is not bounded — see HAL-595, which is where
 * reuse and a sweep of empty guests belong.
 */
@Service
public class GuestSessionService {

    private static final Logger log = LoggerFactory.getLogger(GuestSessionService.class);

    /**
     * A domain reserved by RFC 6761 for exactly this: names guaranteed never to resolve and
     * never to be registrable. A guest row cannot collide with, or be mistaken for, a real
     * address — including by anything that later decides to send email to shoppers.
     */
    private static final String GUEST_EMAIL_DOMAIN = "@guest.invalid";

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public GuestSessionService(
            TenantRepository tenants, AppUserRepository users, PasswordEncoder passwordEncoder) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * @param storeSlug the storefront the shopper is standing in — the only thing that
     *                  establishes which tenant this session belongs to, since there is no
     *                  account to look it up from
     */
    @Transactional
    public AppUser createFor(String storeSlug) {
        Tenant tenant = tenants.findBySlug(storeSlug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found"));

        String email = "guest-" + UUID.randomUUID() + GUEST_EMAIL_DOMAIN;

        // Bcrypt over a value that is generated here, never returned, and never written
        // anywhere else. There is no password to present, so this identity cannot be logged
        // into — it exists to own a cart, not to be an account.
        AppUser guest = users.save(new AppUser(
                tenant.getId(), email, passwordEncoder.encode(UUID.randomUUID().toString()), Role.SHOPPER));

        log.info("Issued a guest session for store {} (tenant {})", storeSlug, tenant.getId());
        return guest;
    }
}
