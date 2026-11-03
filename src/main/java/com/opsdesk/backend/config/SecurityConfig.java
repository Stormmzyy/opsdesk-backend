package com.opsdesk.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// Security setup. For now it holds only the PasswordEncoder; the filter chain
// (which paths need a token, the 401 response) joins it later this week.
// Until then Spring Security's defaults apply.
@Configuration
public class SecurityConfig {

    // Every password is hashed through this one bean: the seeder's, and
    // register's. Nothing else hashes passwords and no hash is ever written
    // into the source.
    //
    // BCrypt salts each hash, so the same password gives a different hash
    // every time, and it is deliberately slow, so guessing passwords from a
    // stolen hash is expensive. Its hashes start with $2a$, which is how to
    // check in psql that a column really holds one. Same class in Boot 3.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
