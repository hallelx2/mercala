package com.mercala.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Provides the BCrypt {@link PasswordEncoder} used to hash + verify user passwords.
 * (Only the crypto module is on the classpath for now — the full Spring Security filter
 * chain arrives with JWT auth in HAL-126.)
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
