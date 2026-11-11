package com.opsdesk.backend.auth;

import com.opsdesk.backend.validation.PatternField;
import com.opsdesk.backend.validation.TextField;

// The body of POST /api/v1/auth/register. Every message is copied exactly
// from the contract's register table, which gives each field one message, so
// a missing field, a non-text value and a broken rule all get that message.
//
// There is no role field, on purpose. Registering always creates a USER
// (AuthService). If a client sends "role": "ADMIN", Jackson finds no field
// for it here and ignores it, so nobody can register themselves as an admin.
//
// Every field is Object, for the reason given on CreateTicketRequest: a
// number or an array is reported against its field instead of the whole body
// failing to read.
public record RegisterRequest(

        @TextField(required = true,
                requiredMessage = "Name is required.",
                notTextMessage = "Name is required.")
        Object name,

        // Letters (A to Z, either case), digits, "_" and "." only, 3 to 30 of
        // them. ASCII letters only: the contract's "letters" is read narrowly,
        // since a handle shown as @username is easier to type and to match
        // when it can't contain look-alike characters from other alphabets.
        // Either case is accepted here, and it's stored in lowercase
        // (NOTES.md, decision 30).
        @PatternField(regexp = "[A-Za-z0-9_.]{3,30}",
                message = "Username must be 3 to 30 letters, numbers, dots or underscores.")
        Object username,

        // Something, an @, something, a dot, something, with no spaces and no
        // second @. Deliberately loose: the only real test of an email
        // address is sending mail to it. This rule catches typos such as a
        // missing @ or domain, and nothing more. Jakarta's @Email would also
        // accept "ama@example", with no dot in the domain.
        @PatternField(regexp = "[^\\s@]+@[^\\s@]+\\.[^\\s@]+",
                message = "Enter a valid email address.")
        Object email,

        // At least 8 characters, counted like the title's minimum. Never
        // trimmed: spaces are legitimate password characters, and trimming
        // would mean the stored hash is of a different password from the one
        // the user typed.
        //
        // At most 72 bytes of UTF-8, because that's all BCrypt accepts
        // (NOTES.md, decision 29). Without this check, a longer password
        // reached the encoder and answered 500. The message says bytes, not
        // characters, because one character can take up to four bytes.
        @TextField(required = true, minLength = 8, maxUtf8Bytes = 72,
                requiredMessage = "Password must be at least 8 characters.",
                tooShortMessage = "Password must be at least 8 characters.",
                notTextMessage = "Password must be at least 8 characters.",
                tooLongMessage = "Password must be at most 72 bytes.")
        Object password) {

    // Only the name is trimmed, before validation, because the contract says
    // "not empty after trimming" for it and for nothing else here. Same rule
    // as RequestText in the ticket package: strip() removes all Unicode
    // whitespace, like JavaScript's trim().
    //
    // The email is not trimmed: " ama@example.com" is reported as invalid
    // rather than silently changed. It is lowercased later, in AuthService
    // (NOTES.md, decision 26), after validation has accepted it.
    public RegisterRequest {
        name = name instanceof String text ? text.strip() : name;
    }

    // Typed values for the service. Only call these after @Valid has passed,
    // when every field is known to be a String.
    String nameText() {
        return (String) name;
    }

    String usernameText() {
        return (String) username;
    }

    String emailText() {
        return (String) email;
    }

    String passwordText() {
        return (String) password;
    }

    // A record's generated toString() prints every field: here the password,
    // and the name, username and email, all personal. Nothing logs a request
    // today, but one log.debug("{}", request) would leak them all (decision
    // 72's never-log list), so it prints only which fields were sent.
    @Override
    public String toString() {
        return "RegisterRequest[name=" + sent(name) + ", username=" + sent(username) + ", email=" + sent(email)
                + ", password=" + sent(password) + "]";
    }

    private static String sent(Object value) {
        return value == null ? "absent" : "<redacted>";
    }
}
