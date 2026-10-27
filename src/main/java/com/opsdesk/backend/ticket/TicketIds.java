package com.opsdesk.backend.ticket;

import com.opsdesk.backend.common.PathIds;

// Turns the {id} text from a ticket URL into a Long, or throws
// TicketNotFoundException (a 404).
//
// Its own class, not a private method on the controller, because two places
// need exactly the same rule: the controller, and the global exception
// handler, which has to read the id from the URL of a PATCH whose body Spring
// couldn't read or validate (NOTES.md, decision 6).
//
// The rule itself (digits only, the same regex as the frontend's parseId.ts)
// lives in PathIds, shared with projects.
public final class TicketIds {

    private TicketIds() {
    }

    // The 404 message quotes the id exactly as it appeared in the URL.
    public static Long parse(String rawId) {
        return PathIds.parse(rawId).orElseThrow(() -> new TicketNotFoundException(rawId));
    }
}
