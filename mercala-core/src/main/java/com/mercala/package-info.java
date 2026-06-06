/**
 * Mercala modular-monolith core. Each bounded context lives in its own sub-package and
 * communicates with the others through interfaces (ports) — never by reaching into another
 * module's tables — so a module can later be extracted into its own service.
 *
 * Modules: identity, catalog, inventory, cart, orders, payments, media, platform.
 * The Spring Boot application class arrives in HAL-120.
 */
package com.mercala;
