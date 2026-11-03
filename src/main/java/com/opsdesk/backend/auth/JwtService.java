package com.opsdesk.backend.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.opsdesk.backend.user.AppUser;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

// Creates the token that login returns, and reads the token that comes back
// on every later request in "Authorization: Bearer <token>".
//
// A JWT has three parts: a header (which algorithm signed it), a payload (the
// claims: who it's for and when it expires), and a signature over both.
// Header and payload are only base64-encoded, not encrypted, so anyone holding
// a token can read them. Nothing secret goes in the payload for that reason.
// What nobody can do without the secret is change them: altering one
// character invalidates the signature, and the parser refuses the token.
@Service
public class JwtService {

    private final SecretKey key;
    private final long expiryMinutes;

    // Both values come from application.yml (app.jwt.*), and the secret from
    // the JWT_SECRET environment variable when it's set. Same @Value in Boot 3.
    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiry-minutes}") long expiryMinutes) {
        // HMAC-SHA: the same key both signs and verifies, so it must stay on
        // the server. hmacShaKeyFor refuses a secret shorter than 32 bytes
        // (256 bits) with a WeakKeyException. That happens here, when the
        // app starts, so a too-short JWT_SECRET stops the app instead of
        // producing tokens that are easy to forge.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiryMinutes = expiryMinutes;
    }

    // The subject is the email, which identifies the user: it's unique and
    // stored in lowercase (NOTES.md, decision 26), and no endpoint changes it.
    //
    // The role claim is there for the frontend, so it can show or hide
    // actions without another request. The server never trusts it: the filter
    // loads the user by email and uses the role from the database. A role
    // changed in psql therefore takes effect on the next request, not when
    // the token expires.
    public String generateToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiryMinutes, ChronoUnit.MINUTES)))
                // The algorithm is picked from the key's size: HS256 for a
                // 32-byte secret, a stronger one for a longer secret.
                .signWith(key)
                .compact();
    }

    // Returns the email from a token that is genuine and unexpired.
    //
    // Throws a JwtException for anything else: a bad signature (the token
    // was altered, or signed with another secret), an expired token, or text
    // that isn't a token at all. The expiry is checked by the parser itself;
    // nothing here needs to compare dates. The caller (JwtAuthFilter) treats
    // the exception as "no valid token", which ends in a 401.
    public String extractEmail(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }
}
