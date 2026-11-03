package com.opsdesk.backend.auth;

// A login with an email that isn't registered, or the wrong password. One
// exception for both, and so one answer for both: 401 UNAUTHORIZED, "The
// email or password is incorrect." (GlobalExceptionHandler). A caller can't
// tell which of the two was wrong, so login can't be used to find out which
// emails have accounts.
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("The email or password is incorrect.");
    }
}
