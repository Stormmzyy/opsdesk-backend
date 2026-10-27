package com.opsdesk.backend.ticket;

import java.math.BigInteger;

import com.opsdesk.backend.validation.OneOf;
import com.opsdesk.backend.validation.WholeNumber;

// The query parameters of GET /tickets: ?page=2&size=10&status=OPEN&search=printer
//
// Spring builds this record from the query string, matching parameter names
// to field names, and @Valid in the controller checks it like a request body.
// A bad value therefore reaches GlobalExceptionHandler as a field error, and
// the client gets a VALIDATION_ERROR with the parameter's name as the key in
// "fields", using the contract's messages.
//
// Every field is a String, and null means "not sent". Any other query
// parameter has no field here, so it's ignored, as the contract says.
public record TicketListParams(

        @WholeNumber(min = 1, message = "Page must be a whole number, 1 or more.")
        String page,

        @WholeNumber(min = 1, max = 100, message = "Size must be a whole number from 1 to 100.")
        String size,

        @OneOf(required = false, values = {"OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"},
                message = "Status must be one of OPEN, IN_PROGRESS, RESOLVED or CLOSED.")
        String status,

        // Any text is a valid search, so there's nothing to check.
        String search) {

    private static final int DEFAULT_SIZE = 10;

    // The contract's rule: if none of the four is sent, GET /tickets returns
    // the plain v1 array, so existing clients keep working. Sending any one of
    // them, even "?page=1", asks for the paged envelope.
    boolean anySent() {
        return page != null || size != null || status != null || search != null;
    }

    // 1-based, as the contract counts. Only call these after @Valid has passed.
    // A page too big for a long is still a valid page number (just past the
    // last page), so it's capped rather than rejected.
    long pageNumber() {
        if (page == null) {
            return 1;
        }
        return new BigInteger(page).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    int pageSize() {
        return size == null ? DEFAULT_SIZE : Integer.parseInt(size);
    }

    TicketStatus statusValue() {
        return status == null ? null : TicketStatus.valueOf(status);
    }

    // The contract: search ignores leading and trailing spaces.
    String searchText() {
        return search == null ? null : search.strip();
    }
}
