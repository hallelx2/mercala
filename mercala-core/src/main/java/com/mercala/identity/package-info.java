/**
 * Identity bounded context: tenants, users, roles, and JWT issuance/validation.
 * Owns tenant signup and authentication; the source of {@code tenant_id} + roles for the platform.
 */
package com.mercala.identity;
