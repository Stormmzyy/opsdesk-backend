package com.opsdesk.backend.config;

import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

// Lets the frontend's dev server (http://localhost:5173) call this API from a
// browser.
//
// Why this is a backend setting, not something the frontend can fix:
//
// Browsers enforce the "same-origin policy". A page loaded from one origin
// (scheme + host + port, here http://localhost:5173) may send a request to
// another origin (http://localhost:8080), but it may not READ the response,
// unless the server that owns that response says it may. The server says so
// with response headers, chiefly Access-Control-Allow-Origin. That's CORS.
//
// The permission has to come from the server because the point is to protect
// the server's data. If a page could grant itself permission, any website you
// happened to visit could read any API your browser can reach, using your
// cookies. So nothing in the React code can switch this off. The frontend can
// only avoid it by not being cross-origin at all, for example a dev-server
// proxy that makes the API look like it's on port 5173. That's a workaround,
// not a fix, and production would still need this.
//
// curl and Postman don't enforce the policy at all, because they aren't
// browsers. That's why every curl test this week worked without this class.
//
// What the browser does in a preflight:
//
// For a "simple" request (a GET or a plain form POST with no custom headers),
// the browser just sends it and then checks the response's
// Access-Control-Allow-Origin before letting the page read it.
//
// Anything else is first checked with a preflight. Our PATCH and DELETE
// requests, and every POST or PATCH with "Content-Type: application/json",
// all count. Before the real request, the browser sends an OPTIONS request
// with no body, asking:
//   Origin: http://localhost:5173                    who is asking
//   Access-Control-Request-Method: PATCH             what they want to do
//   Access-Control-Request-Headers: content-type     which headers they'll send
// The filter below answers it, without reaching any controller, with:
//   Access-Control-Allow-Origin: http://localhost:5173
//   Access-Control-Allow-Methods: GET,POST,PATCH,DELETE
//   Access-Control-Allow-Headers: content-type
//   Access-Control-Max-Age: 3600                     cache this answer for an hour
// Only if that answer allows the origin, method and headers does the browser
// send the real PATCH. If it doesn't, the real request is never sent, and the
// page sees only a network error with "blocked by CORS policy" in the
// console. The server never even sees the attempt.
//
// Why a filter, not WebMvcConfigurer.addCorsMappings:
//
// A request goes through the servlet filters first, and only then reaches
// Spring MVC's DispatcherServlet, which looks for a controller. CORS set up
// with addCorsMappings runs as part of that controller lookup, so it only
// applies when a controller method matches. A request that matches none,
// such as POST /api/v1/tickets/reset (mock-only, so it 404s here), skipped it.
// Its 404 went out with no Access-Control-Allow-Origin, the browser refused
// to let the page read it, and the console said "blocked by CORS policy"
// instead of "404". Every error that never reached a controller looked like a
// CORS failure, which hides the real problem.
//
// A CorsFilter runs before all of that, on every /api/** request, so the
// headers are on every response to an allowed origin: successes, 404s, 400s
// and 500s alike. The rules haven't changed, only where they're applied.
@Configuration
public class CorsConfig {

    // The Vite dev server's address. A deployed frontend would add its own
    // origin here, ideally read from configuration rather than written in code.
    private static final String FRONTEND_DEV_ORIGIN = "http://localhost:5173";

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // Exactly this origin, not "*". A wildcard would let any website's
        // scripts read the API from a visitor's browser.
        config.setAllowedOrigins(List.of(FRONTEND_DEV_ORIGIN));
        // The methods the contract uses. The filter answers OPTIONS (the
        // preflight) itself. PUT isn't listed because the API has no PUT
        // endpoints.
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE"));
        // The frontend sends Content-Type: application/json. Allowing any
        // request header keeps adding one later (such as Authorization in
        // Week 8) from silently breaking preflights.
        config.setAllowedHeaders(List.of("*"));
        // How long the browser may reuse a preflight answer before asking
        // again, in seconds.
        config.setMaxAge(3600L);
        // No setAllowCredentials(true): there are no cookies or sessions until
        // Week 8. When there are, it must be added here, and it only works with
        // a named origin like the one above, never with "*".

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);

        // Registered with the highest precedence, so it runs before every
        // other filter. When Spring Security arrives in Week 8, its filters
        // would otherwise reject a preflight (an OPTIONS request with no
        // credentials) before CORS ever answered it.
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
