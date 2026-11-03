package com.opsdesk.backend.ticket;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.opsdesk.backend.user.AppUser;

// The only queries that return more than one ticket. Every one of them takes
// the caller, and none takes a Specification (NOTES.md, the filtering
// decision).
//
// This is a Spring Data "fragment": TicketRepository extends this interface,
// and Spring Data sends these calls to TicketQueriesImpl, found by the "Impl"
// suffix. The service sees them as ordinary repository methods.
interface TicketQueries {

    // Every ticket the caller may see that matches the filters, in this order.
    List<Ticket> findVisible(AppUser actor, TicketFilters filters, Sort sort);

    // One page of them. The count behind totalItems uses the same WHERE clause.
    Page<Ticket> findVisible(AppUser actor, TicketFilters filters, Pageable pageable);

    // How many there are, for a page too far out to ask the database for.
    long countVisible(AppUser actor, TicketFilters filters);
}
