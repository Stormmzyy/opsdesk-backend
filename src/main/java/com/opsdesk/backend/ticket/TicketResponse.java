package com.opsdesk.backend.ticket;

import com.opsdesk.backend.user.AppUser;

import io.swagger.v3.oas.annotations.media.Schema;

// The shape of a ticket in every API response, in the contract's order.
// Jackson writes a record's fields in the order they're declared, so id
// comes first.
//
// The Ticket entity is the database's shape: it also has a project, and it
// can hold lazy proxies that only work while a database session is open.
// This record is the API's shape. Keeping the two separate means the entity
// can gain columns and relationships without the JSON changing. (NOTES.md,
// decision 8.)
//
// The people on a ticket, and its version (NOTES.md, the ticket shape
// decision). All five are output only; a request that sends one is refused.
//   - assigneeId / assigneeName: null and null when nobody is assigned.
//   - createdById / createdByName: always set; every ticket has a creator.
//   - version: the optimistic-lock version (decision 38), so a client, and
//     Friday's tests, can see it. Read only; nothing accepts it back yet.
// Flat, like the rest of the shape, and named like the assign endpoint's
// own {"assigneeId": n}, so the same name means the same thing in and out.
//
// A name, never an email. GET /users is ADMIN and MANAGER only, so without a
// name here an AGENT couldn't turn an assignee id into a person at all. An
// email is more than that needs: it's the login identifier, so it would hand
// every AGENT and USER half of a credential for everyone on their tickets.
public record TicketResponse(
        Long id,
        String title,
        String description,
        Priority priority,
        TicketStatus status,
        Team team,
        // null when nobody is assigned, and both together (the contract:
        // "integer or null", "null exactly when assigneeId is"). Without
        // these, Swagger described them as a plain integer and string.
        @Schema(types = {"integer", "null"}, format = "int64")
        Long assigneeId,
        @Schema(types = {"string", "null"})
        String assigneeName,
        Long createdById,
        String createdByName,
        Long version) {

    // The one place a Ticket becomes a TicketResponse. Every endpoint goes
    // through here, so a new field reaches the API only when it's added here
    // on purpose.
    //
    // Reading getAssignee().getId() or getCreatedBy().getId() is free: a lazy
    // proxy already holds its id. Reading getName() is not: it makes
    // Hibernate load that user's row, which needs an open database session.
    // So whatever query produced this ticket must have loaded the assignee
    // and the creator already, or this throws LazyInitializationException
    // (open-in-view is off, so no session outlives the service).
    public static TicketResponse from(Ticket ticket) {
        AppUser assignee = ticket.getAssignee();
        AppUser createdBy = ticket.getCreatedBy();
        return new TicketResponse(
                ticket.getId(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getPriority(),
                ticket.getStatus(),
                ticket.getTeam(),
                assignee == null ? null : assignee.getId(),
                assignee == null ? null : assignee.getName(),
                createdBy.getId(),
                createdBy.getName(),
                ticket.getVersion());
    }
}
