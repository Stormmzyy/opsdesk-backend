package com.opsdesk.backend.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.opsdesk.backend.config.MissingSettingException;
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

    // The shortest secret HMAC-SHA256 accepts: 32 bytes (256 bits).
    private static final int MIN_SECRET_BYTES = 32;

    // Both values come from app.jwt.* (application.yml), and the secret only
    // from the JWT_SECRET environment variable, except under the dev and test
    // profiles, which have their own. Same @Value in Boot 3.
    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiry-minutes}") long expiryMinutes) {
        // Checked here, when the app starts, so a missing or weak secret stops
        // the app with one sentence that names the variable and the fix,
        // instead of producing tokens anyone could forge (decision 68).
        //
        // Missing: application.yml resolves an unset JWT_SECRET to "" on
        // purpose, so it arrives here rather than failing inside Spring with
        // "Could not resolve placeholder". Too short: hmacShaKeyFor would
        // refuse it anyway, but with a WeakKeyException about key bits that
        // doesn't mention JWT_SECRET at all.
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        // MissingSettingException, so MissingSettingFailureAnalyzer shows it as
        // Spring Boot's "APPLICATION FAILED TO START" report at the end of the
        // output, instead of the last line of a long stack trace.
        if (secret.isBlank()) {
            throw new MissingSettingException("JWT_SECRET", "is not set",
                    "Set the JWT_SECRET environment variable to a random value of at least " + MIN_SECRET_BYTES
                            + " characters (it signs every login token, so keep it secret), or, on a"
                            + " development machine, run with SPRING_PROFILES_ACTIVE=dev.");
        }
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new MissingSettingException("JWT_SECRET",
                    "is too short: it is " + secretBytes.length + " bytes, and it must be at least " + MIN_SECRET_BYTES,
                    "Set the JWT_SECRET environment variable to a random value of at least " + MIN_SECRET_BYTES
                            + " characters.");
        }
        // HMAC-SHA: the same key both signs and verifies, so it must stay on
        // the server.
        this.key = Keys.hmacShaKeyFor(secretBytes);
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
