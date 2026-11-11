package com.opsdesk.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.opsdesk.backend.auth.JwtAuthFilter;

// Security setup: which paths need a signed-in user, how a token becomes a
// signed-in user, what a request without one gets, and how passwords are
// hashed.
//
// @EnableMethodSecurity switches on @PreAuthorize, which Wednesday's role
// rules use (decision 22, step 2). Nothing uses it yet. Same annotation in
// Boot 3, where the older @EnableGlobalMethodSecurity was already
// deprecated.
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    // The lambda style below (http.csrf(csrf -> ...)) is the only one Spring
    // Security 7, in Boot 4, accepts. Boot 3 (Spring Security 6) still allowed
    // the older chained style, http.csrf().disable().and()..., deprecated.
    // What springdoc serves: the OpenAPI document (and the UI's config, under
    // it) and the Swagger UI page with its static files.
    private static final String[] SWAGGER_PATHS = {
        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    // swaggerEnabled is the same property that switches springdoc on, so the
    // paths are opened only where they exist: under the dev profile
    // (application-dev.yml). Everywhere else it's false, the paths aren't
    // registered, and they fall to anyRequest().authenticated(), so an
    // anonymous caller gets decision 24's 401 there like anywhere else and
    // can't tell that Swagger exists (decision 74). The ":false" default keeps
    // them closed if the property is ever missing.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                                   UnauthorizedEntryPoint unauthorizedEntryPoint,
                                                   @Value("${springdoc.api-docs.enabled:false}") boolean swaggerEnabled)
            throws Exception {
        http
                // CSRF protection defends cookie-based sessions: a browser
                // sends cookies automatically, so another site could make it
                // send a request as the user. A Bearer token is never sent
                // automatically, only when the frontend's code adds the
                // header, so there is nothing for CSRF to defend. Left on, it
                // would refuse every POST, PATCH and DELETE without a CSRF
                // token.
                .csrf(AbstractHttpConfigurer::disable)
                // No .cors() here, unlike most tutorials: CorsConfig's filter
                // runs before every Spring Security filter, so a preflight is
                // answered, and every response (401s included) carries the
                // CORS headers, before this chain is reached.
                //
                // No server-side session: every request proves who it is with
                // its own token (decision 20), so Spring must never create an
                // HttpSession or send a JSESSIONID cookie.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // The only two open endpoints (NOTES.md, decision 18).
                    // Listed by method and exact path rather than
                    // "/api/v1/auth/**", so GET /auth/me, which needs a
                    // signed-in user, isn't opened by accident.
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll();
                    // The health check, for a container or load balancer,
                    // which call it without credentials (decision 74).
                    // GET on this exact path only: not "/actuator/**", so
                    // every other actuator path, real or not, still gets
                    // decision 24's 401. It answers {"status":"UP"} or
                    // "DOWN" and nothing else (application.yml).
                    auth.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
                    // Swagger, under dev only (see swaggerEnabled above). GET
                    // only: springdoc serves nothing else.
                    if (swaggerEnabled) {
                        auth.requestMatchers(HttpMethod.GET, SWAGGER_PATHS).permitAll();
                    }
                    // Everything else needs a signed-in user, including
                    // paths that don't exist (decision 24): the caller
                    // gets the same 401 for every path, real or not.
                    auth.anyRequest().authenticated();
                })
                // A request that isn't signed in, on a path that needs it,
                // gets the contract's 401 instead of Spring's empty 403.
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorizedEntryPoint))
                // Reads the Bearer token before Spring's own login filter
                // would look for a username and password, so by the time the
                // rules above are checked, the caller is already known.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // JwtAuthFilter is a @Component, and Spring Boot automatically registers
    // every Filter bean with the servlet container, to run on every request.
    // It would then run twice: once inside the security chain, where it
    // belongs, and once more on its own, outside it. This switches off the
    // second registration, so it only ever runs where the chain puts it.
    // (Same class and package in Boot 3.)
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

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
