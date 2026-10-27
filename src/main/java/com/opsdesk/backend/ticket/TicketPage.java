package com.opsdesk.backend.ticket;

import java.util.List;

import org.springframework.data.domain.Page;

// The contract's paginated envelope for GET /tickets:
//   { "items": [...], "page": 2, "size": 10, "totalItems": 11, "totalPages": 2 }
//
// Our own record, never Spring's Page. Jackson would happily serialise a
// Spring Page, but its JSON is Spring's shape, not the contract's: "content"
// instead of "items", 0-based page numbers, plus "pageable", "sort", "first",
// "last" and other fields, all of which can change between Spring versions.
// The same reason TicketResponse exists: the API's shape is ours to decide,
// not the library's.
public record TicketPage(
        List<TicketResponse> items,
        long page,
        int size,
        long totalItems,
        long totalPages) {

    // page and size are the values the client asked for (1-based page), not
    // read back from Spring's Page, so the response echoes the request even
    // for a page past the last one.
    static TicketPage from(Page<Ticket> result, long page, int size) {
        long totalItems = result.getTotalElements();
        return new TicketPage(
                result.getContent().stream().map(TicketResponse::from).toList(),
                page,
                size,
                totalItems,
                totalPages(totalItems, size));
    }

    // The contract: ceil(totalItems / size), and 0 when nothing matches.
    // Worked out here rather than taken from Spring, so the rule is visible
    // in our code and doesn't depend on a library's edge-case behaviour.
    // (totalItems + size - 1) / size is ceil() in whole numbers, and gives 0
    // for 0 items.
    private static long totalPages(long totalItems, int size) {
        return (totalItems + size - 1) / size;
    }
}
