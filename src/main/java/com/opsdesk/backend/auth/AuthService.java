package com.opsdesk.backend.auth;

import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.opsdesk.backend.error.ConflictException;
import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.AppUserRepository;
import com.opsdesk.backend.user.Role;

// The rules for registering and logging in. The controller only translates
// HTTP; everything that decides an outcome is here.
@Service
public class AuthService {

    private static final String EMAIL_TAKEN = "An account with this email already exists.";
    private static final String USERNAME_TAKEN = "An account with this username already exists.";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    // A hash of a random value that is never stored or shown. Login checks
    // the password against it when the email isn't registered (see login).
    // Made at startup through the PasswordEncoder, so it is a real BCrypt hash
    // with the same cost as every stored one, and no hash is written into the
    // source.
    private final String unknownUserHash;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // Always creates a USER. The role is never read from the request: there
    // is no role field to read. ADMIN, MANAGER and AGENT come only from the
    // seeder or from SQL (README.md, "Making a user an admin").
    public AppUser register(String name, String username, String email, String password) {
        // Lowercased before the duplicate check and before saving (NOTES.md,
        // decision 26), so Ama@example.com is found as a duplicate of
        // ama@example.com, and only the lowercase form is ever stored.
        // Locale.ROOT for the reason given in AppUser.
        String normalisedEmail = email.toLowerCase(Locale.ROOT);
        // The same for the username (decision 30), so Ama.Owusu is a
        // duplicate of ama.owusu.
        String normalisedUsername = username.toLowerCase(Locale.ROOT);

        rejectIfTaken(normalisedEmail, normalisedUsername);

        AppUser user = new AppUser(name, normalisedUsername, normalisedEmail,
                passwordEncoder.encode(password), Role.USER);
        try {
            // saveAndFlush, not save: the INSERT runs now, inside this try,
            // so a unique-constraint failure is caught here rather than
            // surfacing later as a 500.
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException raced) {
            // Two requests with the same email (or username) at the same
            // moment: both passed the checks above, and the database's unique
            // constraint refused the second INSERT. Asking again names which
            // one clashed and gives the same 409 as the ordinary case. If
            // neither exists, the failure was something else, so it goes on
            // to the 500 handler.
            rejectIfTaken(normalisedEmail, normalisedUsername);
            throw raced;
        }
    }

    // Each a 409 with its own message (decision 31). Email first: when both
    // are taken, the caller most likely already has an account, so that's
    // the more useful thing to tell them.
    //
    // This 409 does reveal that an email has an account, the opposite of
    // what login is careful about. That's a known trade-off of having a 409
    // at all (decision 32).
    private void rejectIfTaken(String normalisedEmail, String normalisedUsername) {
        if (users.existsByEmail(normalisedEmail)) {
            throw new ConflictException(EMAIL_TAKEN);
        }
        if (users.existsByUsername(normalisedUsername)) {
            throw new ConflictException(USERNAME_TAKEN);
        }
    }

    // Returns a token and the signed-in user, or throws
    // InvalidCredentialsException: the same exception, and so the same 401,
    // whether the email is unknown or the password is wrong.
    public LoginResult login(String email, String password) {
        AppUser user = users.findByEmail(email.toLowerCase(Locale.ROOT)).orElse(null);

        // The password is checked even when no user has this email, against
        // a hash that can never match. Without this, an unknown email would
        // answer in about a millisecond and a known one only after BCrypt's
        // deliberate delay (around a tenth of a second), and that difference
        // alone would reveal which emails have accounts, even though the two
        // responses are identical.
        String hash = user != null ? user.getPasswordHash() : unknownUserHash;
        boolean passwordMatches = passwordEncoder.matches(password, hash);

        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        return new LoginResult(jwtService.generateToken(user), user);
    }

    public record LoginResult(String token, AppUser user) {

        // The generated toString() would print the signed token, which is a
        // working credential until it expires (decision 72: never log a token,
        // or any part of one). The user is named by id only.
        @Override
        public String toString() {
            return "LoginResult[token=<redacted>, user=" + user.getId() + "]";
        }
    }
}
