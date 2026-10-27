package com.opsdesk.backend.ticket;

import java.util.regex.Pattern;

// Turns the {id} text from a ticket URL into a Long, or throws
// TicketNotFoundException (a 404).
//
// Its own class, not a private method on the controller, because two places
// need exactly the same rule: the controller, and the global exception
// handler, which has to read the id from the URL of a PATCH whose body Spring
// couldn't read or validate (NOTES.md, decision 6).
public final class TicketIds {

    // Digits only: no sign, no decimal point, no spaces, no hex.
    private static final Pattern WHOLE_NUMBER = Pattern.compile("^\\d+$");

    private TicketIds() {
    }

    // Why the id arrives as a String instead of letting Spring convert it to a
    // Long: Spring's conversion is more forgiving than the contract. It accepts
    // "+3" and "0x3" (hexadecimal) and quietly returns ticket 3, and "-1"
    // becomes a real Long. The contract says an id that isn't a positive whole
    // number is treated like one that doesn't exist: 404.
    //
    // The regex is the same one the frontend uses in
    // opsdesk-frontend/src/utils/parseId.ts, so the backend and the frontend
    // agree exactly on which ids are valid. "abc", "3.5", "-1", "+3" and "0x3"
    // are all rejected by both, and the 404 message quotes the id exactly as
    // it appeared in the URL.
    public static Long parse(String rawId) {
        if (!WHOLE_NUMBER.matcher(rawId).matches()) {
            throw new TicketNotFoundException(rawId);
        }
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException tooBig) {
            // Digits only, but too many to fit in a Long
            // (over 9,223,372,036,854,775,807). No ticket can have that id.
            throw new TicketNotFoundException(rawId);
        }
    }
}
