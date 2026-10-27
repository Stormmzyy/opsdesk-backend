package com.opsdesk.backend.common;

import java.util.Optional;
import java.util.regex.Pattern;

// Turns the {id} text from a URL into a Long, for every resource (tickets,
// projects). Empty means "not a valid id", and each caller turns that into its
// own 404 (No ticket with id "abc". / No project with id "abc.").
//
// Why the id arrives as a String instead of letting Spring convert it to a
// Long: Spring's conversion is more forgiving than the contract. It accepts
// "+3" and "0x3" (hexadecimal) and quietly returns item 3, and "-1" becomes a
// real Long. The contract says an id that isn't a positive whole number is
// treated like one that doesn't exist: 404.
//
// The regex is the same one the frontend uses in
// opsdesk-frontend/src/utils/parseId.ts, for tickets and projects alike, so
// the backend and the frontend agree exactly on which ids are valid.
public final class PathIds {

    // Digits only: no sign, no decimal point, no spaces, no hex.
    private static final Pattern WHOLE_NUMBER = Pattern.compile("^\\d+$");

    private PathIds() {
    }

    public static Optional<Long> parse(String rawId) {
        if (!WHOLE_NUMBER.matcher(rawId).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(rawId));
        } catch (NumberFormatException tooBig) {
            // Digits only, but too many to fit in a Long
            // (over 9,223,372,036,854,775,807). Nothing can have that id.
            return Optional.empty();
        }
    }
}
