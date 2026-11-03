package com.opsdesk.backend.ticket;

import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.Role;

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

    // Which tickets this caller may see (README.md, Permissions; NOTES.md,
    // decision 27). ADMIN, MANAGER and AGENT see every ticket, so no
    // condition. A USER sees only the tickets she created, so the condition
    // is created_by_id = her id.
    //
    // It's a condition in the query itself, not a filter applied to the rows
    // afterwards, because the same WHERE clause feeds the COUNT that gives
    // totalElements and totalPages. Filtering a loaded page instead would
    // count tickets she can't see (totalElements 12 for a caller who can see
    // 2), and a page of 20 would come back with whatever survived the filter.
    //
    // root.get("createdBy").get("id") compares the foreign key column
    // directly; Hibernate doesn't need to join app_user for it.
    static Specification<Ticket> visibleTo(AppUser actor) {
        if (actor.getRole() != Role.USER) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.equal(root.get("createdBy").get("id"), actor.getId());
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
