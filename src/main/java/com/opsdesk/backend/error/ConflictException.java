package com.opsdesk.backend.error;

// 409 CONFLICT: the request clashes with data that already exists, such as
// registering an email that's already taken. The message is the contract's
// and goes to the client as it is, so it must never contain anything the
// caller didn't send.
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
