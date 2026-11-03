package com.opsdesk.backend.ticket;

import com.opsdesk.backend.common.PathIds;

// Turns the {id} text from a ticket URL into a Long, or throws
// TicketNotFoundException (a 404).
//
// Its own class rather than a private method, so there's exactly one rule.
// TicketService.getVisibleTicket is its caller: every ticket URL, and the
// PATCH interceptor, comes through there.
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
