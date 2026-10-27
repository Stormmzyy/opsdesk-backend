package com.opsdesk.backend.ticket;

import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

// The WHERE clause for GET /tickets with filters, built from optional parts.
//
// A derived query (like findByStatus) has a fixed set of conditions in its
// name. Here, status and search may each be sent or not: four combinations.
// A Specification is one condition as an object; they combine with and(), so
// each filter that was sent adds one part, and no filters means no WHERE.
final class TicketSpecifications {

    private TicketSpecifications() {
    }

    static Specification<Ticket> matching(TicketStatus status, String search) {
        // "No filter at all" as a starting point.
        // Boot 3 code usually started from Specification.where(null); Spring Data 4 has unrestricted() for this.
        Specification<Ticket> spec = Specification.unrestricted();
        if (status != null) {
            spec = spec.and(hasStatus(status));
        }
        if (search != null && !search.isEmpty()) {
            spec = spec.and(titleOrDescriptionContains(search));
        }
        return spec;
    }

    private static Specification<Ticket> hasStatus(TicketStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    // The contract: title OR description contains the text, ignoring case.
    // (The caller has already trimmed it.) Both sides are lower-cased, so
    // "PRINTER" finds "Printer on the second floor".
    //
    // The search text goes in as a bound parameter, never pasted into the SQL,
    // so it can't inject SQL. But inside a LIKE pattern, % and _ are wildcards.
    // Unescaped, a search for "50%" would match "50" followed by anything.
    // Escaping them makes the search literal, which is what "contains this
    // text" means.
    private static Specification<Ticket> titleOrDescriptionContains(String search) {
        String pattern = "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                cb.like(cb.lower(root.get("description")), pattern, '\\'));
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
