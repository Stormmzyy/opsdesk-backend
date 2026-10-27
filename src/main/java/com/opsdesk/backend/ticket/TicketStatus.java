package com.opsdesk.backend.ticket;

// Named TicketStatus, not Status, to avoid clashing with Spring's HttpStatus
// in the controller and Project's status later in the week.
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED
}
