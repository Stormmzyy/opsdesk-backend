package com.opsdesk.backend.ticket;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.opsdesk.backend.user.AppUser;

// The only queries that return more than one ticket. Every one of them takes
// the caller, none takes a Specification, and none takes a Spring Data Sort,
// whose property paths the client could steer (NOTES.md, the filtering and
// sorting decisions). The order is a TicketOrder: a closed list.
//
// This is a Spring Data "fragment": TicketRepository extends this interface,
// and Spring Data sends these calls to TicketQueriesImpl, found by the "Impl"
// suffix. The service sees them as ordinary repository methods.
interface TicketQueries {

    // Every ticket the caller may see that matches the filters, in this order.
    List<Ticket> findVisible(AppUser actor, TicketFilters filters, TicketOrder order);

    // One page of them. The count behind totalItems uses the same WHERE
    // clause. Only the page's number and size are used; any Sort inside the
    // Pageable is dropped, so the order can only come from TicketOrder.
    Page<Ticket> findVisible(AppUser actor, TicketFilters filters, TicketOrder order, Pageable pageable);

    // How many there are, for a page too far out to ask the database for.
    long countVisible(AppUser actor, TicketFilters filters);
}
