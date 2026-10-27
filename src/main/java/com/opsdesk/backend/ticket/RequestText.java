package com.opsdesk.backend.ticket;

// Trimming for the free-text fields of the ticket request records.
final class RequestText {

    private RequestText() {
    }

    // Trims a value only if it's a string. A number or array is left alone,
    // so validation can still report it as "must be text".
    //
    // strip() rather than trim(): strip() removes all Unicode whitespace, the
    // same as the frontend's JavaScript trim(); trim() only removes ASCII
    // spaces and control characters.
    static Object trimIfText(Object value) {
        return value instanceof String text ? text.strip() : value;
    }
}
