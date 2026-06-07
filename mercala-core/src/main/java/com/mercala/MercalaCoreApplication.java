package com.mercala;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Mercala modular-monolith core.
 *
 * <p>{@code @SpringBootApplication} enables component scanning across {@code com.mercala.*},
 * auto-configuration, and configuration-properties binding — so each bounded-context module
 * (identity, catalog, inventory, cart, orders, payments, media, platform) is wired in as it
 * gains beans in later milestones.
 */
@SpringBootApplication
public class MercalaCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(MercalaCoreApplication.class, args);
    }
}
