/**
 * Mercala modular-monolith core. Each bounded context lives in its own sub-package and
 * communicates with the others through interfaces (ports) — never by reaching into another
 * module's tables — so a module can later be extracted into its own service.
 *
 * Modules: identity, catalog, inventory, cart, orders, payments, media, platform.
 * Bootstrapped by {@link com.mercala.MercalaCoreApplication}.
 */
package com.mercala;
