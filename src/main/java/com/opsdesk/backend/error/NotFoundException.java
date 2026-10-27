package com.opsdesk.backend.error;

// "The thing asked for doesn't exist": the contract's 404 NOT_FOUND.
//
// Each resource has its own subclass (TicketNotFoundException,
// ProjectNotFoundException) so its message names the right thing, and
// GlobalExceptionHandler needs only one handler for all of them.
public abstract class NotFoundException extends RuntimeException {

    protected NotFoundException(String message) {
        super(message);
    }
}
