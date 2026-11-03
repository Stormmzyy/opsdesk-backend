package com.opsdesk.backend.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.AppUserRepository;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Turns "Authorization: Bearer <token>" into a signed-in user, once per
// request, inside Spring Security's filter chain (SecurityConfig places it).
//
// It never refuses a request itself. With a valid token it records who the
// caller is; with no token, or one that isn't valid, it records nothing and
// lets the request carry on. Deciding whether the path needed a signed-in
// user is the chain's job afterwards, and a caller who isn't signed in on a
// path that needs one gets UnauthorizedEntryPoint's 401. That's why login and
// register still work with a stale token in the header.
//
// OncePerRequestFilter: a request forwarded inside the server (to /error,
// say) passes through the filter chain again, and this guarantees the work
// below runs only once.
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final AppUserRepository users;

    public JwtAuthFilter(JwtService jwtService, AppUserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        // The scheme name is case-insensitive in HTTP (RFC 9110), so "bearer"
        // is accepted as well as "Bearer". The token itself is not.
        if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String token = header.substring(BEARER.length()).trim();
            try {
                String email = jwtService.extractEmail(token);

                // The user is loaded from the database on every request, and
                // the role used is the one stored there, never the token's
                // "role" claim (NOTES.md, decision 28). A role changed in psql
                // applies from the next request. An account deleted since the
                // token was issued finds nobody, so the request stays
                // unauthenticated and gets a 401.
                //
                // NOT A BUG: the caller's row is often read twice per
                // request, here by email and again when a ticket she created
                // is loaded (today as joined columns in the ticket query;
                // without the fetch joins it was a SELECT by id of its own).
                // This lookup runs in its own persistence context, which has
                // ended before the request's own one starts, so Hibernate
                // can't reuse the object. It's one constant query per
                // request, not one per row, so it's accepted (NOTES.md, the
                // N+1 decision).
                users.findByEmail(email).ifPresent(this::signIn);
            } catch (JwtException notValid) {
                // A bad signature, an expired token, or not a token at all.
                // Nothing is recorded, so the request is treated as having no
                // token. The reason isn't sent back: the 401 is the same for
                // every kind of invalid token, which gives an attacker
                // probing tokens nothing to go on.
            }
        }
        chain.doFilter(request, response);
    }

    // The principal is the AppUser itself, so a controller can ask for it
    // with @AuthenticationPrincipal AppUser (Wednesday). The authority is
    // "ROLE_" + role, the form Spring's hasRole("ADMIN") checks for.
    //
    // No credentials (null): the token has already been checked, and nothing
    // later needs it.
    private void signIn(AppUser user) {
        var authentication = new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));

        // A new, empty context rather than changing the existing one, as
        // Spring Security's documentation recommends, so this request can't
        // touch a context another thread might also hold.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
