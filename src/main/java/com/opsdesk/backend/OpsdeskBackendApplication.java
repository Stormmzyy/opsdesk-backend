package com.opsdesk.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// UserDetailsServiceAutoConfiguration is switched off. With Spring Security on
// the classpath and no UserDetailsService of our own, Spring Boot creates an
// in-memory user called "user" with a random password, and LOGS that password
// at every startup ("Using generated security password: ..."), production
// included. Nothing here can use that account (sign-in is only through
// POST /auth/login and JwtAuthFilter; there's no HTTP Basic or form login), so
// it was a password in the log for an account that does nothing, and anyone
// reading the log would rightly ask what it opens. Found by the Week 8 log
// audit (NOTES.md, decision 72). Boot 3 name:
// org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class OpsdeskBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(OpsdeskBackendApplication.class, args);
	}

}
