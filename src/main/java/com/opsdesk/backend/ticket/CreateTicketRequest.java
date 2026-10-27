package com.opsdesk.backend.ticket;

// The body of POST /tickets. It has no id and no status: the contract says the
// server picks the id and every new ticket starts as OPEN. If a client sends
// either one, Jackson finds no matching field here and ignores it, which is
// exactly what the contract asks for.
//
// A record, because a request body is read once and never changed.
public record CreateTicketRequest(
        String title,
        String description,
        Priority priority,
        Team team) {
}
