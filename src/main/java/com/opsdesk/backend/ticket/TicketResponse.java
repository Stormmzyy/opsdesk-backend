package com.opsdesk.backend.ticket;

// The shape of a ticket in every API response: exactly the contract's six
// fields, in the contract's order. Jackson writes a record's fields in the
// order they're declared, so id comes first.
//
// The Ticket entity is the database's shape: it also has a project and an
// assignee, and it can hold lazy proxies that only work while a database
// session is open. This record is the API's shape. Keeping the two separate
// means the entity can gain columns and relationships without the JSON
// changing. (NOTES.md, decision 8.)
public record TicketResponse(
        Long id,
        String title,
        String description,
        Priority priority,
        TicketStatus status,
        Team team) {

    // The one place a Ticket becomes a TicketResponse. Every endpoint goes
    // through here, so a new field reaches the API only when it's added here
    // on purpose.
    //
    // It only reads plain columns, never getProject() or getAssignee(), so it
    // never touches a lazy proxy and works after the database session has
    // closed.
    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getPriority(),
                ticket.getStatus(),
                ticket.getTeam());
    }
}
