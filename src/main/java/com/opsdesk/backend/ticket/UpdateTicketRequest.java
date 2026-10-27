package com.opsdesk.backend.ticket;

// The body of PATCH /tickets/{id}. Every field is optional: a field that
// wasn't sent arrives as null, and null means "leave this one as it is".
// That's what lets the frontend send just { "status": "RESOLVED" } to move a
// ticket on the board. There's no id field, so an id in the body is ignored.
public record UpdateTicketRequest(
        String title,
        String description,
        Priority priority,
        TicketStatus status,
        Team team) {
}
