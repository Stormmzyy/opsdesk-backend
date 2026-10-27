package com.opsdesk.backend.ticket;

// Thrown by the service, which knows nothing about HTTP, to say "that ticket
// doesn't exist". The controller decides that this means a 404. The service
// would throw the same exception if a scheduled job asked for a missing ticket.
//
// A RuntimeException, so methods don't need "throws" clauses all the way up.
public class TicketNotFoundException extends RuntimeException {

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
