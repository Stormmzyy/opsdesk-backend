package com.opsdesk.backend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.UserResponse;

import jakarta.validation.Valid;

// POST /api/v1/auth/register and POST /api/v1/auth/login (NOTES.md,
// decision 18). These are the only two paths that work without a token;
// SecurityConfig opens exactly these two.
//
// Check order on both (decision 22): BAD_REQUEST if the body can't be read,
// then VALIDATION_ERROR, both raised by Spring before these methods run.
// Only then does the service look at the data: a taken email or username on
// register (409), wrong credentials on login (401).
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // 201 Created: registering creates a row in app_user. The body is the
    // contract's User object, built by UserResponse, which has no password
    // field of any kind.
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        AppUser registered = authService.register(
                request.nameText(), request.usernameText(), request.emailText(), request.passwordText());
        // A normal event, at INFO (decision 72). Logged here, after the
        // service has returned, so it's only written for an account that was
        // really saved. The new user's id and role only: not the name, the
        // username or the email, and of course not the password.
        log.info("User {} registered, with role {}", registered.getId(), registered.getRole());
        return UserResponse.from(registered);
    }

    // 200 OK, not 201 (decision 20): a JWT is stateless, so logging in
    // creates nothing on the server. 200 is the default, so no
    // @ResponseStatus is needed.
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.emailText(), request.passwordText());
        return new LoginResponse(result.token(), UserResponse.from(result.user()));
    }

    // GET /api/v1/auth/me: the signed-in user, in the contract's User shape.
    // Not one of the two open paths, so a request without a valid token never
    // gets here: SecurityConfig answers 401 first. The user is the one
    // JwtAuthFilter loaded from the database for this request (decision 28),
    // so it reflects the account as it is now, not as it was when the token
    // was issued.
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AppUser user) {
        return UserResponse.from(user);
    }
}
