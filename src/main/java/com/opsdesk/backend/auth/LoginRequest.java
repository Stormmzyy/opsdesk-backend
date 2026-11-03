package com.opsdesk.backend.auth;

import com.opsdesk.backend.validation.TextField;

// The body of POST /api/v1/auth/login. The contract only requires both
// fields to be present: no email format check and no password length check.
// A malformed email or a short password simply doesn't match any account, so
// it gets the same 401 as any other wrong login. Validating them here would
// answer 400 instead and tell the caller something about the rules.
public record LoginRequest(

        @TextField(required = true,
                requiredMessage = "Email is required.",
                notTextMessage = "Email is required.")
        Object email,

        // Never trimmed, for the reason given on RegisterRequest.password.
        @TextField(required = true,
                requiredMessage = "Password is required.",
                notTextMessage = "Password is required.")
        Object password) {

    String emailText() {
        return (String) email;
    }

    String passwordText() {
        return (String) password;
    }
}
