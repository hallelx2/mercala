package com.mercala.identity.exception;

/**
 * Thrown when login credentials don't match. Mapped to HTTP 401 with a generic
 * message (we never reveal whether the tenant, email, or password was wrong).
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
