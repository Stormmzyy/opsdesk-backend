package com.opsdesk.backend.user;

// What a user is allowed to do. README.md's permissions table says what each
// role may do.
//
// Stored in app_user.role as the constant's name ("ADMIN"), not its position,
// for the reason given on Ticket.priority: an ordinal is unreadable in psql,
// and reordering or inserting a constant would silently give every existing
// user a different role.
public enum Role {
    ADMIN,
    MANAGER,
    AGENT,
    USER
}
