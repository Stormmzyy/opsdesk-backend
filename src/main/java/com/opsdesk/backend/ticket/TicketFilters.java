package com.opsdesk.backend.ticket;

// The filters of a ticket list, as plain values: which status, priority and
// team to keep, and what text to search for. null means "don't filter on it".
//
// Deliberately data, not a Specification. A Specification is a query
// fragment that can be run on its own, and one built from these filters
// alone would be a query with no visibility condition in it: a USER's
// ?status=OPEN answering with everyone's open tickets. Only
// TicketQueriesImpl turns filters into a query, and it always starts from
// the caller's visibility (NOTES.md, the filtering decision).
record TicketFilters(TicketStatus status, Priority priority, Team team, String search) {

    static final TicketFilters NONE = new TicketFilters(null, null, null, null);
}
