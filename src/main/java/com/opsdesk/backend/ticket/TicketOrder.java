package com.opsdesk.backend.ticket;

// How a ticket list is ordered: one field from a closed list, and a
// direction (NOTES.md, the sorting decision).
//
// Deliberately an enum, not a field name. The client's text is matched
// against this list in TicketListParams and never reaches the query as a
// property path. Spring Data's Sort takes a property path and follows
// associations, so "createdBy.passwordHash" is a path it would try to
// resolve through the createdBy link: at best an error, at worst rows
// ordered by a value the caller can't read. That second case is an oracle:
// the order of the rows leaks the hidden value, one comparison at a time,
// without it ever appearing in a response. With an enum there's no string to validate, so
// there's nothing to get wrong. TicketQueriesImpl maps each constant to a
// fixed expression.
//
// Every order ends with id ascending as the tiebreaker, so two tickets with
// the same priority always come back in the same order, and a page boundary
// can't fall differently on two requests (NOTES.md, decision 7).
record TicketOrder(Field field, boolean descending) {

    // The contract's order since v1: by id, oldest first.
    static final TicketOrder DEFAULT = new TicketOrder(Field.ID, false);

    enum Field {
        ID,
        // Case-insensitive, so "printer" doesn't sort after "Zebra".
        TITLE,
        // Workflow order: OPEN, IN_PROGRESS, RESOLVED, CLOSED. Not
        // alphabetical, which would put CLOSED first.
        STATUS,
        // By rank: LOW, MEDIUM, HIGH, URGENT. Alphabetical would give HIGH,
        // LOW, MEDIUM, URGENT, which looks plausible and is wrong.
        PRIORITY,
        // Alphabetical by label (Engineering ... Support). The stored names
        // are in the same order, so the column sorts correctly as it is.
        TEAM
    }
}
