package com.opsdesk.backend.ticket;

import com.opsdesk.backend.error.NotFoundException;

// Thrown by the service, which knows nothing about HTTP, to say "that ticket
// doesn't exist". The controller decides that this means a 404. The service
// would throw the same exception if a scheduled job asked for a missing ticket.
//
// A RuntimeException (through NotFoundException), so methods don't need
// "throws" clauses all the way up. NotFoundException is shared with projects,
// so one handler answers 404 for both.
public class TicketNotFoundException extends NotFoundException {

    // Takes the id as text, not a Long, because the same message is used for
    // ids that aren't numbers at all ("abc"), and the contract quotes whatever
    // was in the URL.
    public TicketNotFoundException(String rawId) {
        super("No ticket with id \"" + rawId + "\".");
    }

    public TicketNotFoundException(Long id) {
        this(String.valueOf(id));
    }
}
